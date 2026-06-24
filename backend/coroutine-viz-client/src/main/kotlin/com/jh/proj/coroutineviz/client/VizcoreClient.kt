package com.jh.proj.coroutineviz.client

import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.session.source.InstrumentationSource
import com.jh.proj.coroutineviz.session.source.debugprobes.DebugProbesSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.random.Random

/**
 * The embeddable client half of real-app transport (RCO-04).
 *
 * [start] authenticates with a JWT, creates a server session via
 * `POST /api/sessions`, builds a LOCAL [VizSession] carrying the SERVER-assigned
 * `sessionId` (Pitfall 1 / T-07-03 — so ingested events carry the correct
 * immutable id), starts a [DebugProbesSource] against it, and forwards every
 * synthesized event over a Ktor client WebSocket to the backend ingest route —
 * with capped exponential backoff on a dropped connection (T-07-08) and a clean
 * idempotent [stop].
 *
 * Events are serialized with the SHARED core `appJson` (see [IngestTransport]) so
 * the wire format never drifts from the backend ingest / SSE / FE format
 * (Pitfall 3).
 *
 * Zero overhead when disabled: nothing is installed and no connection is opened
 * unless [start] is called. The reconnect loop and [DebugProbesSource] live on a
 * private `SupervisorJob` scope — NEVER `GlobalScope` (CLAUDE.md).
 *
 * SECURITY (T-07-07): [DebugProbesSource] attaches creation-stack `file:line`
 * attribution to events that cross the process boundary to the backend. This is a
 * DEVELOPMENT tool — do NOT enable it against sensitive production stacks.
 */
class VizcoreClient internal constructor(
    private val httpClient: HttpClient,
    private val backendUrl: String,
    private val sessionId: String,
    private val token: String,
    val session: VizSession,
    private val source: InstrumentationSource,
    private val scope: CoroutineScope,
    private val backoff: Backoff = Backoff(),
    /**
     * The lifetime-scoped outbound bridge (CR-01). Fed ONCE at [start] by the single
     * source-side collector that subscribes to `session.bus.stream()` and SURVIVES
     * every reconnect; drained by whichever socket is currently active. Injectable so
     * a test can supply a buffer fed from its own local session.
     */
    private val buffer: OutboundBuffer = OutboundBuffer(),
    /**
     * The single-connection transport attempt. Defaults to the real Ktor client WS
     * send loop ([stream]) draining the lifetime [buffer]; injectable so [ReconnectTest]
     * drives a fake transport (e.g. one that throws) under runTest virtual time.
     * Returns when the socket closes; throws on a connection error (→ the loop
     * reconnects with backoff).
     */
    private val transport: suspend () -> Unit = {
        stream(httpClient, backendUrl, sessionId, token, buffer)
    },
) {
    private val logger = LoggerFactory.getLogger(VizcoreClient::class.java)

    @Volatile
    private var running = false

    /**
     * Start producing + forwarding events. Idempotent: a second call while running
     * is a no-op.
     *
     * Closes the CR-01 startup race deterministically: launches the SINGLE lifetime
     * feed (`buffer.feed(session, scope)`, which awaits its own bus subscription) and
     * ONLY AFTER it returns drives the [source] — so no synthesized event precedes the
     * feed collector. The feed is created exactly once per client lifetime and survives
     * every reconnect. The reconnect loop runs in parallel and DRAINS the buffer, which
     * already retains everything once the feed is subscribed (covering the entire
     * no-socket-yet startup window AND every backoff window).
     */
    fun start() {
        if (running) return
        running = true
        // Feed-then-source ordering: subscribe the bus and await the subscription
        // before the source can emit, then drive the source. The feed collector lives
        // for the whole client lifetime on the scope.
        scope.launch {
            buffer.feed(session, scope)
            source.start()
        }
        scope.launch {
            var attempt = 0
            while (isActive && running) {
                try {
                    transport()
                    // A clean return means the socket closed; treat as a drop and reconnect.
                    attempt = 0
                } catch (ce: CancellationException) {
                    // Cooperative cancellation (stop()/scope cancel) must propagate; never retry.
                    throw ce
                } catch (t: Throwable) {
                    logger.warn("Ingest connection dropped; reconnecting with backoff", t)
                }
                if (!isActive || !running) break
                delay(backoff.delayMillis(attempt))
                attempt++
            }
        }
    }

    /**
     * Stop the source, close the outbound [buffer] (so the feed collector + any active
     * drain terminate), close the WebSocket/HTTP client, and cancel the client scope.
     * Idempotent and leak-free: after [stop] the [source] is no longer running, the
     * buffer is closed, and the scope is cancelled.
     */
    fun stop() {
        if (!running) return
        running = false
        source.stop()
        buffer.close()
        httpClient.close()
        scope.cancel()
    }

    companion object {
        /**
         * Build and [start] an embeddable client bound to [backendUrl] for [appName],
         * authenticating with [token]. Creates the server session, constructs the
         * local [VizSession] with the SERVER id, drives a real [DebugProbesSource],
         * and begins forwarding. This is the ONLY entry point that installs/connects.
         */
        fun start(
            appName: String,
            backendUrl: String,
            token: String,
        ): VizcoreClient {
            val httpClient =
                HttpClient(CIO) {
                    install(WebSockets)
                }
            // createSession is suspend; bootstrap is a one-shot blocking call before
            // the async loop takes over — acceptable for a start() entry point.
            val serverSessionId = runBlocking { createSession(httpClient, backendUrl, appName, token) }
            val session = VizSession(sessionId = serverSessionId)
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val source = DebugProbesSource(session = session)
            return VizcoreClient(
                httpClient = httpClient,
                backendUrl = backendUrl,
                sessionId = serverSessionId,
                token = token,
                session = session,
                source = source,
                scope = scope,
            ).also { it.start() }
        }
    }
}

/**
 * Capped exponential backoff with jitter for ingest reconnection (T-07-08).
 *
 * `delay = min(base * 2^attempt, cap)` plus up to 20% positive jitter so a fleet
 * of clients does not reconnect in lockstep. Defaults: 500ms base, 30s cap.
 */
class Backoff(
    private val baseMillis: Long = 500L,
    private val capMillis: Long = 30_000L,
    private val random: Random = Random.Default,
) {
    fun delayMillis(attempt: Int): Long {
        val exp = baseMillis shl attempt.coerceAtMost(MAX_SHIFT)
        val capped = exp.coerceIn(baseMillis, capMillis)
        val jitter = (capped * JITTER_FRACTION * random.nextDouble()).toLong()
        return (capped + jitter).coerceAtMost(capMillis)
    }

    private companion object {
        const val MAX_SHIFT = 16
        const val JITTER_FRACTION = 0.2
    }
}
