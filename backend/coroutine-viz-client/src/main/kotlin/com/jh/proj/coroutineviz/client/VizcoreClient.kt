package com.jh.proj.coroutineviz.client

import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.session.source.InstrumentationSource
import com.jh.proj.coroutineviz.session.source.debugprobes.DebugProbesSource
import com.jh.proj.coroutineviz.session.source.debugprobes.VizcoreInternal
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.ContinuationInterceptor
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How long an ingest socket must stay up before its close is treated as the end of a
 * HEALTHY connection (and therefore resets the reconnect backoff). Anything shorter is
 * an immediate close and must keep the backoff growing.
 */
private const val DEFAULT_STABLE_CONNECTION_MS = 5_000L

// Threads the SDK's Ktor client may use at once: its selector loop holds one, the WebSocket the rest.
private const val TRANSPORT_PARALLELISM = 4

/**
 * How long [VizcoreClient.start] waits for the backend to create the session before it returns
 * without one and leaves the create to the background loop (#254). Long enough for
 * [createSession]'s own short retry against a refusing port; short enough that a backend that
 * accepts connections and never answers cannot hold up the app's startup for the HTTP timeouts.
 *
 * A create the client gives up on may still have succeeded on a backend that answers this slowly;
 * that session then stays empty on the backend, and the client streams into the one it creates
 * next. The create endpoint has no idempotency key to prevent that.
 */
private const val START_SESSION_TIMEOUT_MS = 3_000L

/**
 * How long one background attempt to create the first session may take (#254), so a backend
 * that accepts the connection and never answers cannot stall the loop for the HTTP timeouts.
 * The same bound as the ingest handshake.
 */
private const val BACKGROUND_SESSION_TIMEOUT_MS = 10_000L

/**
 * The embeddable client half of real-app transport (RCO-04).
 *
 * [start] authenticates with a JWT, creates a server session via
 * `POST /api/sessions`, builds a LOCAL [VizSession] carrying the SERVER-assigned
 * `sessionId` (Pitfall 1 / T-07-03), or an empty id when the backend could not be
 * reached yet (#254; the backend stamps its own id on every ingested event either
 * way), starts a [DebugProbesSource] against it, and forwards every
 * synthesized event over a Ktor client WebSocket to the backend ingest route —
 * with capped exponential backoff on a dropped connection (T-07-08) and a clean
 * idempotent [stop].
 *
 * It does not need the backend to be up (#254): when [start] cannot create the server session —
 * the backend is down, unreachable or not answering — it logs a WARN and still returns a running
 * client. The client keeps creating the session in the background with the same capped backoff as
 * the reconnect loop, buffering the app's events in the meantime, and streams them once the
 * backend answers. Until then [sessionIdOrNull] is `null` and [currentSessionId] is empty.
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
    /** The server session to stream into; `null` when [start] could not create one (#254). */
    sessionId: String?,
    private val token: String,
    /**
     * The LOCAL session the SDK records the app's coroutines into before sending them. Its
     * `sessionId` is the server session the client started with, or empty when the backend was
     * unreachable at start; it does not follow a session created later or a replacement (#129).
     * The backend files every event under the session it ingested it into, so read
     * [sessionIdOrNull] for the server's id.
     */
    val session: VizSession,
    private val source: InstrumentationSource,
    private val scope: CoroutineScope,
    private val backoff: Backoff = Backoff(),
    /**
     * App name used when the client has to bootstrap a REPLACEMENT session
     * (see [recreateSession]). Defaults to the initial session id so existing
     * call sites keep compiling and the new session is still recognizable.
     */
    private val appName: String = sessionId ?: session.sessionId,
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
     * Records in its `SocketLifetime` when the socket opened and ended, which is what
     * the backoff reset is timed from. Returns when the socket closes; throws on a connection error (→ the loop
     * reconnects with backoff).
     */
    private val transport: suspend (sessionId: String, lifetime: SocketLifetime) -> Unit = { id, lifetime ->
        stream(httpClient, backendUrl, id, token, buffer, IngestTimeouts(), lifetime)
    },
    /**
     * Bootstrap a REPLACEMENT server session and return its id, used when the
     * backend reports the current session is gone ([SessionGoneException]).
     * Injectable so a test can drive recovery without a live REST backend.
     */
    private val recreateSession: suspend () -> String = {
        createSession(httpClient, backendUrl, appName, token)
    },
    /** The correlation token forwarded when the FIRST session is created in the background. */
    private val correlation: String? = null,
    /**
     * Create the FIRST server session when [start] returned without one because the backend
     * could not be reached (#254). Carries [correlation], like the create in [start] would have.
     * Injectable so a test can drive the background create without a live REST backend.
     */
    private val createInitialSession: suspend () -> String = {
        createSession(httpClient, backendUrl, appName, token, correlation)
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
    /** How long one background attempt at [createInitialSession] may take before it counts as failed. */
    private val sessionCreateTimeoutMillis: Long = BACKGROUND_SESSION_TIMEOUT_MS,
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
     * next socket with no restart. `null` until the first session exists (#254).
     */
    private val sessionIdRef = AtomicReference(sessionId)

    /**
     * The id of the server session events are currently being sent to, or an empty string while
     * there is none yet: when the backend could not be reached at [start], the client creates the
     * session in the background once it can (#254). [sessionIdOrNull] says the same with `null`.
     */
    val currentSessionId: String get() = sessionIdRef.get().orEmpty()

    /**
     * The id of the server session events are currently being sent to, or `null` while the
     * backend has not created one yet because it could not be reached (#254).
     */
    val sessionIdOrNull: String? get() = sessionIdRef.get()

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
     * counter, timed from when the transport reports it open to when it reports it
     * ended: an attempt that never opened a socket (a handshake timeout, a refused
     * upgrade) never resets it, and neither does a session lookup after a socket failed,
     * however long either took. A socket that returns immediately — which is what an
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
     *
     * No session yet (#254): when [start] could not reach the backend, each pass first tries
     * to create the session, and a failed create backs off exactly like a failed socket. The
     * app's events wait in the [buffer] meanwhile.
     */
    private suspend fun reconnectLoop() {
        var attempt = 0
        while (currentCoroutineContext().isActive && running) {
            // Creating the session does not reset the backoff: only a socket that stays open does (#129).
            val sessionId = sessionIdRef.get() ?: createFirstSession()
            if (sessionId != null && streamOnce(sessionId)) attempt = 0
            if (!currentCoroutineContext().isActive || !running) break
            delay(backoff.delayMillis(attempt))
            attempt++
        }
    }

    /**
     * One connection attempt of the [reconnectLoop] for [sessionId]. Returns whether it was a
     * healthy connection, which resets the backoff; replaces the session first when the backend
     * no longer has it.
     */
    @Suppress("TooGenericExceptionCaught") // any connection failure is retried with backoff
    private suspend fun streamOnce(sessionId: String): Boolean {
        val lifetime = SocketLifetime(clock)
        var sessionGone = false
        try {
            transport(sessionId, lifetime)
        } catch (ce: CancellationException) {
            // Cooperative cancellation (stop()/scope cancel) must propagate; never retry.
            throw ce
        } catch (gone: SessionGoneException) {
            sessionGone = true
            logger.warn("Backend no longer has session {}", sessionId, gone)
        } catch (silent: IngestHandshakeTimeoutException) {
            // Its stack says nothing the message does not.
            logger.warn("Ingest connection dropped; reconnecting with backoff: {}", silent.message)
        } catch (t: Throwable) {
            logger.warn("Ingest connection dropped; reconnecting with backoff", t)
        }
        if (!currentCoroutineContext().isActive || !running) return false
        // Read before the recovery below, whose time is not socket time.
        val healthy = lifetime.lastedAtLeast(stableConnectionMillis)
        if (sessionGone) recoverSession()
        return healthy
    }

    /**
     * Create the first server session, when [start] could not reach the backend (#254). Returns
     * its id, or `null` after logging the failure, so the loop backs off and tries again.
     *
     * An app runtime the SDK cannot run on ([LinkageError], e.g. a class first needed to read
     * the backend's answer) can never succeed, so it is reported once and the client stops.
     */
    @Suppress("TooGenericExceptionCaught") // an unreachable backend must not kill the loop
    private suspend fun createFirstSession(): String? {
        var created: String? = null
        val failure: String? =
            try {
                // withTimeoutOrNull, not withTimeout: its TimeoutCancellationException would read as
                // "client stopping" below.
                created = withTimeoutOrNull(sessionCreateTimeoutMillis) { createInitialSession() }
                if (created == null) "no answer within $sessionCreateTimeoutMillis ms" else null
            } catch (ce: CancellationException) {
                // Only the client's own cancellation (stop()) ends the loop. An HTTP client can fail a
                // request with a CancellationException of its own, which is a failed attempt.
                currentCoroutineContext().ensureActive()
                ce.message ?: ce.toString()
            } catch (linkage: LinkageError) {
                logger.error(
                    "vizcore stops: it cannot create a session at {} on this app's runtime",
                    backendUrl,
                    RuntimeRequirements.linkageFailure(linkage),
                )
                // Releases the probes, the feed and the HTTP client too, and ends this loop.
                stop()
                null
            } catch (t: Throwable) {
                t.message ?: t.toString()
            }
        // Published only while running, so a create that lands as stop() runs leaves no session behind.
        if (created != null && !publishIfRunning(created)) created = null
        if (created != null) {
            logger.info("Connected to the vizcore backend at {}: streaming into session {}", backendUrl, created)
        } else if (failure != null) {
            logger.warn(
                "vizcore backend at {} is still unreachable; retrying the session create after backoff: {}",
                backendUrl,
                failure,
            )
        }
        return created
    }

    private fun publishIfRunning(sessionId: String): Boolean =
        synchronized(lifecycleLock) {
            if (running) sessionIdRef.set(sessionId)
            running
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
         * local [VizSession] with the SERVER id (empty when the backend could not be
         * reached; see below), drives a real [DebugProbesSource],
         * and begins forwarding. This is the ONLY entry point that installs/connects.
         *
         * An OPTIONAL [correlation] token (defaulted to `null`, the established
         * Phase 07/08 additive-param convention) is forwarded into the session-create
         * call so the backend records it against the session it actually creates
         * (CORR-01) — letting a watcher/IDE that supplied the same token resolve THIS
         * real session id. Existing 3-arg `start(appName, backendUrl, token)` call
         * sites compile and link unchanged. The client forwards an OPAQUE string
         * and imports no `TenantContext`/server-auth type, so this module stays
         * pure-Kotlin JVM-17 (D-12); correlation is create-path metadata only and does
         * NOT touch `VizSession.send()` (D-13).
         *
         * An unreachable backend does not make this throw (#254): when the session cannot be
         * created within a few seconds — the backend is down, the URL is wrong, or nothing answers —
         * it logs a WARN with the cause and returns a running client anyway. That client keeps
         * creating the session in the background with capped backoff and streams the app's
         * buffered events once it succeeds; until then [sessionIdOrNull] is `null` and
         * [currentSessionId] is empty. So an app can call this during its own startup.
         *
         * @throws IllegalStateException when the app's kotlin-stdlib or kotlinx-coroutines is older than
         *   the SDK needs (see [RuntimeRequirements]).
         */
        fun start(
            appName: String,
            backendUrl: String,
            token: String,
            correlation: String? = null,
        ): VizcoreClient = start(appName, backendUrl, token, DEFAULT_POLL_INTERVAL_MILLIS, correlation)

        /**
         * [start] with how often, in milliseconds, the SDK samples the app's coroutines. A
         * coroutine that starts and finishes between two samples is not seen, so a shorter
         * interval catches shorter work, at the cost of one DebugProbes dump per interval
         * (#206). The default is [DEFAULT_POLL_INTERVAL_MILLIS]; values below
         * [MIN_POLL_INTERVAL_MILLIS] are rejected.
         *
         * A separate overload rather than a new parameter of [start], so apps compiled against
         * the four-argument signature keep working.
         *
         * Like [start], it returns a running client when the backend is unreachable (#254).
         *
         * @throws IllegalArgumentException when [pollIntervalMillis] is below [MIN_POLL_INTERVAL_MILLIS].
         * @throws IllegalStateException when the app's kotlin-stdlib or kotlinx-coroutines is older than
         *   the SDK needs (see [RuntimeRequirements]).
         */
        @JvmOverloads
        fun start(
            appName: String,
            backendUrl: String,
            token: String,
            pollIntervalMillis: Long,
            correlation: String? = null,
        ): VizcoreClient {
            require(pollIntervalMillis >= MIN_POLL_INTERVAL_MILLIS) {
                "pollIntervalMillis must be at least $MIN_POLL_INTERVAL_MILLIS, was $pollIntervalMillis"
            }
            // Before the Ktor client is built: with too old a kotlin-stdlib or kotlinx-coroutines that
            // fails with a NoSuchMethodError instead of a message saying what to change.
            RuntimeRequirements.check()
            return try {
                // A dispatcher of the SDK's own: every coroutine Ktor runs for this client is on it,
                // which is how the DebugProbes source tells them from the app's (#225).
                val transport = Dispatchers.IO.limitedParallelism(TRANSPORT_PARALLELISM)
                start(
                    appName = appName,
                    backendUrl = backendUrl,
                    token = token,
                    correlation = correlation,
                    httpClient =
                        HttpClient(CIO) {
                            engine { dispatcher = transport }
                            install(WebSockets)
                        },
                    internalDispatchers = setOf(transport),
                    pollInterval = pollIntervalMillis.milliseconds,
                )
            } catch (e: LinkageError) {
                throw RuntimeRequirements.linkageFailure(e)
            }
        }

        /** How often [start] samples coroutines unless told otherwise. */
        const val DEFAULT_POLL_INTERVAL_MILLIS: Long = 150

        /** The shortest interval [start] accepts; each sample dumps every coroutine in the JVM. */
        const val MIN_POLL_INTERVAL_MILLIS: Long = 10

        /** [start] with an injected [httpClient], so tests can drive the real factory in-process. */
        @Suppress("TooGenericExceptionCaught") // cleans up whatever the failure, then rethrows it
        internal fun start(
            appName: String,
            backendUrl: String,
            token: String,
            correlation: String?,
            httpClient: HttpClient,
            internalDispatchers: Set<ContinuationInterceptor> = emptySet(),
            pollInterval: Duration = DEFAULT_POLL_INTERVAL_MILLIS.milliseconds,
            sessionTimeout: Duration = START_SESSION_TIMEOUT_MS.milliseconds,
        ): VizcoreClient {
            var session: VizSession? = null
            var scope: CoroutineScope? = null
            var source: DebugProbesSource? = null
            return try {
                // Created here when the backend answers, so the id is known when start() returns, as
                // before; otherwise null, and the reconnect loop creates it once the backend is up.
                val serverSessionId =
                    sessionAtStart(backendUrl, sessionTimeout) {
                        createSession(httpClient, backendUrl, appName, token, correlation)
                    }
                val local = VizSession(sessionId = serverSessionId.orEmpty()).also { session = it }
                // The client's own loops (feed, transport, reconnect) stay out of the app's session.
                val clientScope =
                    CoroutineScope(Dispatchers.Default + SupervisorJob() + VizcoreInternal).also { scope = it }
                val probes =
                    DebugProbesSource(
                        session = local,
                        pollInterval = pollInterval,
                        internalDispatchers = internalDispatchers,
                    ).also { source = it }
                // Install the probes before returning: start() below begins polling
                // asynchronously, and DebugProbes miss every coroutine created before
                // installation, so work the app launches right after start(...) was lost.
                probes.install()
                VizcoreClient(
                    httpClient = httpClient,
                    backendUrl = backendUrl,
                    sessionId = serverSessionId,
                    token = token,
                    session = local,
                    source = probes,
                    scope = clientScope,
                    // Carried so a replacement session (after the backend loses this one)
                    // is created under the SAME app name, not under a session id.
                    appName = appName,
                    correlation = correlation,
                ).also { it.start() }
            } catch (failure: Throwable) {
                // No client reaches the caller, so nothing else could ever close what this opened.
                source?.stop()
                scope?.cancel()
                session?.close()
                httpClient.close()
                throw failure
            }
        }

        /**
         * Run the session [create] within [timeout], or log a WARN and return `null` when the
         * backend cannot be reached (#254): an observability SDK must not stop the app it observes
         * from starting. Only the session create is forgiven; a [LinkageError] (an app runtime the
         * SDK cannot run on) still propagates, and so does a stop of the calling thread.
         */
        @Suppress("TooGenericExceptionCaught") // whatever the create failed with, the app must start
        private fun sessionAtStart(
            backendUrl: String,
            timeout: Duration,
            create: suspend () -> String,
        ): String? {
            val logger = LoggerFactory.getLogger(VizcoreClient::class.java)
            val failure =
                try {
                    // createSession is suspend; this is a one-shot blocking call before the async loop
                    // takes over. Bounded, because a forwarder or proxy in front of a backend that is
                    // down can accept the connection and never answer.
                    runBlocking { withTimeoutOrNull(timeout) { create() } }?.let { return it }
                    "no answer within ${timeout.inWholeMilliseconds} ms"
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: e.toString()
                }
            logger.warn(
                "Could not create a vizcore session at {}; the app runs on and the session is created in the " +
                    "background once the backend answers: {}",
                backendUrl,
                failure,
            )
            return null
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
