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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * How long an ingest socket must stay up before its close is treated as the end of a
 * HEALTHY connection (and therefore resets the reconnect backoff). Anything shorter is
 * an immediate close and must keep the backoff growing.
 */
private const val DEFAULT_STABLE_CONNECTION_MS = 5_000L

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
 * It also SURVIVES losing its server session (#129): when the backend reports the
 * session is gone — a `VIOLATED_POLICY` "Session not found" close or a `404` on the
 * session lookup — the loop bootstraps a replacement session and keeps streaming into
 * it ([currentSessionId] follows). An immediate close does NOT reset the backoff, so a
 * backend that accepts and instantly drops every socket is retried ever more slowly
 * rather than in a hot loop.
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
    sessionId: String,
    private val token: String,
    val session: VizSession,
    private val source: InstrumentationSource,
    private val scope: CoroutineScope,
    private val backoff: Backoff = Backoff(),
    /**
     * App name used when the client has to bootstrap a REPLACEMENT session
     * (see [recreateSession]). Defaults to the initial session id so existing
     * call sites keep compiling and the new session is still recognizable.
     */
    private val appName: String = sessionId,
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
    private val transport: suspend (sessionId: String) -> Unit = { id ->
        stream(httpClient, backendUrl, id, token, buffer)
    },
    /**
     * Bootstrap a REPLACEMENT server session and return its id, used when the
     * backend reports the current session is gone ([SessionGoneException]).
     * Injectable so a test can drive recovery without a live REST backend.
     */
    private val recreateSession: suspend () -> String = {
        createSession(httpClient, backendUrl, appName, token)
    },
    /**
     * Wall-clock source used ONLY to decide whether a socket streamed long enough to
     * count as a healthy connection (see the reconnect loop). Injectable so a test
     * can drive the decision under `runTest` virtual time.
     */
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * How long a socket must live before it is treated as a HEALTHY connection whose
     * close resets the backoff. Shorter-lived sockets (the "accepted then closed
     * immediately" case) keep the backoff growing.
     */
    private val stableConnectionMillis: Long = DEFAULT_STABLE_CONNECTION_MS,
) {
    private val logger = LoggerFactory.getLogger(VizcoreClient::class.java)

    @Volatile
    private var running = false

    // Serializes start()/stop() against the async source start, so a stop() that lands
    // while the feed is still subscribing cannot be undone by a late source.start().
    private val lifecycleLock = Any()

    /**
     * The server session currently being streamed into. MUTABLE because the backend
     * can lose the session under us (eviction, restart) and the client then
     * bootstraps a replacement and keeps sending to that one (#129). Read by the
     * transport on every connect attempt, so a recovery mid-run takes effect on the
     * next socket with no restart.
     */
    private val sessionIdRef = AtomicReference(sessionId)

    /** The id of the server session events are currently being sent to. */
    val currentSessionId: String get() = sessionIdRef.get()

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
        synchronized(lifecycleLock) {
            if (running) return
            running = true
        }
        // Feed-then-source ordering: subscribe the bus and await the subscription
        // before the source can emit, then drive the source. The feed collector lives
        // for the whole client lifetime on the scope.
        scope.launch {
            buffer.feed(session, scope)
            // The source owns its own scope, so a source started after stop() would keep
            // polling (and hold its probe reference) forever. Start it only if still running.
            synchronized(lifecycleLock) {
                if (running) source.start()
            }
        }
        scope.launch { reconnectLoop() }
    }

    /**
     * The single reconnect loop: connect, stream until the socket ends, back off,
     * repeat — for the client's whole lifetime.
     *
     * Backoff reset rule (#129): ONLY a socket that streamed for at least
     * [stableConnectionMillis] counts as a healthy connection and resets the attempt
     * counter. A socket that returns immediately — which is what an
     * accepted-then-closed handshake against a dead backend or a refusing route does
     * — keeps the counter growing, so the loop backs off instead of spinning at the
     * base delay forever. The previous code reset on every clean return, turning that
     * case into a 500 ms hot loop.
     *
     * Session recovery: a [SessionGoneException] means retrying this id can never
     * work, so the loop bootstraps a REPLACEMENT session and keeps streaming into it.
     * The local [session] keeps its original id — the backend stamps the
     * server-resolved id onto every ingested event, so events still land under the
     * session they were actually ingested into.
     */
    private suspend fun reconnectLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive && running) {
            val connectedAtMs = clock()
            var sessionGone = false
            try {
                transport(sessionIdRef.get())
            } catch (ce: CancellationException) {
                // Cooperative cancellation (stop()/scope cancel) must propagate; never retry.
                throw ce
            } catch (gone: SessionGoneException) {
                sessionGone = true
                logger.warn("Backend no longer has session {}", sessionIdRef.get(), gone)
            } catch (t: Throwable) {
                logger.warn("Ingest connection dropped; reconnecting with backoff", t)
            }
            if (!currentCoroutineContext().isActive || !running) break
            if (clock() - connectedAtMs >= stableConnectionMillis) attempt = 0
            if (sessionGone) recoverSession()
            delay(backoff.delayMillis(attempt))
            attempt++
        }
    }

    /**
     * Bootstrap a replacement server session and point the transport at it. A failure
     * here is logged and swallowed: the loop simply backs off and tries the whole
     * recovery again on the next pass rather than killing the client.
     */
    @Suppress("TooGenericExceptionCaught") // a failed bootstrap must not kill the reconnect loop
    private suspend fun recoverSession() {
        try {
            val replacement = recreateSession()
            val previous = sessionIdRef.getAndSet(replacement)
            logger.info("Recovered ingest: session {} replaced by {}", previous, replacement)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            logger.warn("Could not create a replacement session; will retry after backoff", t)
        }
    }

    /**
     * Stop the source, close the outbound [buffer] (so the feed collector + any active
     * drain terminate), close the WebSocket/HTTP client, and cancel the client scope.
     * Idempotent and leak-free: after [stop] the [source] is no longer running, the
     * buffer is closed, and the scope is cancelled.
     */
    fun stop() {
        synchronized(lifecycleLock) {
            if (!running) return
            running = false
            source.stop()
        }
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
         *
         * An OPTIONAL [correlation] token (defaulted to `null`, the established
         * Phase 07/08 additive-param convention) is forwarded into the session-create
         * call so the backend records it against the session it actually creates
         * (CORR-01) — letting a watcher/IDE that supplied the same token resolve THIS
         * real session id. Existing 3-arg `start(appName, backendUrl, token)` call
         * sites compile and behave identically. The client forwards an OPAQUE string
         * and imports no `TenantContext`/server-auth type, so this module stays
         * pure-Kotlin JVM-17 (D-12); correlation is create-path metadata only and does
         * NOT touch `VizSession.send()` (D-13).
         *
         * @throws IllegalStateException when the app's kotlin-stdlib or kotlinx-coroutines is older than
         *   the SDK needs (see [RuntimeRequirements]).
         */
        fun start(
            appName: String,
            backendUrl: String,
            token: String,
            correlation: String? = null,
        ): VizcoreClient {
            // Before the Ktor client is built: with too old a kotlin-stdlib or kotlinx-coroutines that
            // fails with a NoSuchMethodError instead of a message saying what to change.
            RuntimeRequirements.check()
            return try {
                start(
                    appName = appName,
                    backendUrl = backendUrl,
                    token = token,
                    correlation = correlation,
                    httpClient = HttpClient(CIO) { install(WebSockets) },
                )
            } catch (e: LinkageError) {
                throw RuntimeRequirements.linkageFailure(e)
            }
        }

        /** [start] with an injected [httpClient], so tests can drive the real factory in-process. */
        internal fun start(
            appName: String,
            backendUrl: String,
            token: String,
            correlation: String?,
            httpClient: HttpClient,
        ): VizcoreClient {
            // createSession is suspend; bootstrap is a one-shot blocking call before
            // the async loop takes over — acceptable for a start() entry point.
            val serverSessionId =
                runBlocking { createSession(httpClient, backendUrl, appName, token, correlation) }
            val session = VizSession(sessionId = serverSessionId)
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val source = DebugProbesSource(session = session)
            // Install the probes before returning: start() below begins polling
            // asynchronously, and DebugProbes miss every coroutine created before
            // installation, so work the app launches right after start(...) was lost.
            source.install()
            return VizcoreClient(
                httpClient = httpClient,
                backendUrl = backendUrl,
                sessionId = serverSessionId,
                token = token,
                session = session,
                source = source,
                scope = scope,
                // Carried so a replacement session (after the backend loses this one)
                // is created under the SAME app name, not under a session id.
                appName = appName,
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
