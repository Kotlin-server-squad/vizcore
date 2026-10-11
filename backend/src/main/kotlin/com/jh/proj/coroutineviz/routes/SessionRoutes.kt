package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.SESSION_CREATE_RATE_LIMIT_NAME
import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.auth.TenantScopedSessionStore
import com.jh.proj.coroutineviz.auth.resolveTenant
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.sseClientsGauge
import com.jh.proj.coroutineviz.sseSamplingDroppedGauge
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.PolymorphicSerializer
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("CoroutineVizRouting")

/**
 * Default leak-detection age threshold for `GET /api/sessions/{id}/metrics` when
 * the `?leakThresholdMs=` query param is absent or unparseable (~30s, D-07).
 */
const val DEFAULT_LEAK_MS: Long = 30_000

/** Lower clamp bound for the leak threshold — guards against absurdly-small values (V5). */
const val MIN_LEAK_MS: Long = 1_000

/** Upper clamp bound for the leak threshold — guards against Long.MAX / DoS values (T-08-05/V5). */
const val MAX_LEAK_MS: Long = 3_600_000

/**
 * The active tenant-scoped backing store, if persistence is on AND the store
 * implements [TenantScopedSessionStore]. In memory mode (or when persistence is
 * off) this is null and routes fall back to the unscoped [SessionManager] calls
 * — i.e. global visibility (D-04b).
 */
internal fun tenantScopedStore(): TenantScopedSessionStore? = SessionManager.backingStore() as? TenantScopedSessionStore

/**
 * Resolve a session by [sessionId] using the SAME tenant scoping the top-level
 * GET /api/sessions/{id} applies (CR-01 / D-03): when the tenant-scoped store is
 * active, resolve via `store.getSession(sessionId, resolveTenant())` so a
 * cross-tenant id returns null (→ 404, never leaking another tenant's content);
 * when null (memory / auth-off mode) fall back to the unscoped
 * [SessionManager.getSession] (preserves D-04b global visibility). Every
 * authenticated session-bound read path (events/hierarchy/threads/timeline/SSE)
 * MUST go through this helper, NOT the bare [SessionManager.getSession].
 */
internal fun ApplicationCall.resolveScopedSession(sessionId: String): VizSession? {
    val store = tenantScopedStore()
    return if (store != null) {
        store.getSession(sessionId, resolveTenant())
    } else {
        SessionManager.getSession(sessionId)
    }
}

fun Route.registerSessionRoutes() {
    // Session creation is rate-limited (ADR-029, rateLimit.sessionCreate, default 10/min) AND
    // tenant-scoped (T-03): a scoped store stamps ownership, else fall back to the in-memory
    // manager (D-04b).
    rateLimit(RateLimitName(SESSION_CREATE_RATE_LIMIT_NAME)) {
        post("/api/sessions") {
            val name = call.request.queryParameters["name"]
            val store = tenantScopedStore()
            val session =
                if (store != null) {
                    store.createSession(name, call.resolveTenant())
                } else {
                    SessionManager.createSession(name)
                }

            logger.info("Created new session via API: ${session.sessionId}")

            // CORR-01 (D-11): record an optional client-minted correlation token against the
            // session the backend ACTUALLY creates, atomically at create time (no separately-minted
            // id). Omitting the param records nothing — wire-level back-compat for old clients. This
            // is create-path metadata only; it does NOT touch VizSession.send() (invariant #1 / D-13).
            val correlation = call.request.queryParameters["correlation"]
            if (!correlation.isNullOrBlank()) {
                CorrelationRegistry.bind(correlation, session.sessionId)
            }

            call.respond(
                HttpStatusCode.Created,
                mapOf(
                    "sessionId" to session.sessionId,
                    "message" to "Session created successfully",
                ),
            )
        }
    }

    get("/api/sessions") {
        val store = tenantScopedStore()
        val sessions =
            if (store != null) {
                store.listSessions(call.resolveTenant())
            } else {
                SessionManager.listSessions()
            }
        logger.debug("Listing sessions: ${sessions.size} active")
        call.respond(HttpStatusCode.OK, sessions)
    }

    // Resolve a client-minted correlation token to the REAL live session id (CORR-02, D-04/D-05).
    // A constant-segment path, so Ktor's routing priority keeps it from being captured by the
    // parameterized "/api/sessions/{id}" below (same coexistence proven for "/compare" in 2-01).
    // Registered inside registerSessionRoutes() so it inherits auth + the "api" read bucket
    // + D-04a fail-open from Routing.kt for free.
    get("/api/sessions/resolve") {
        val correlation = call.request.queryParameters["correlation"]
        if (correlation.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing correlation token"))
            return@get
        }

        // Last-write-wins lookup (D-09). A token that was never bound (or whose session was
        // evicted on close) resolves to null → 404, indistinguishable from not-found.
        val sessionId = CorrelationRegistry.resolve(correlation)
        if (sessionId == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
            return@get
        }

        // Re-validate tenant visibility through the SAME helper the other read routes use (D-05):
        // a cross-tenant or vanished id resolves to null → 404, NEVER 403 (no existence leak,
        // success criterion #2). No reimplemented tenant predicate.
        val session = call.resolveScopedSession(sessionId)
        if (session == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
            return@get
        }

        call.respond(HttpStatusCode.OK, mapOf("sessionId" to session.sessionId))
    }

    get("/api/sessions/{id}") {
        val sessionId =
            call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session ID"))
                return@get
            }

        val store = tenantScopedStore()
        val session =
            if (store != null) {
                store.getSession(sessionId, call.resolveTenant())
            } else {
                SessionManager.getSession(sessionId)
            }
        if (session == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
            return@get
        }

        // One point-in-time copy so the count and the list agree while events keep arriving.
        val nodes = session.snapshot.nodes()
        val snapshot =
            SessionSnapshotResponse(
                sessionId = session.sessionId,
                coroutineCount = nodes.size,
                eventCount = session.store.all().size,
                coroutines =
                    nodes.map { node ->
                        CoroutineNodeDto(
                            id = node.id,
                            jobId = node.jobId,
                            parentId = node.parentId,
                            scopeId = node.scopeId,
                            label = node.label,
                            state = node.state.toString(),
                        )
                    },
            )

        call.respond(HttpStatusCode.OK, snapshot)
    }

    delete("/api/sessions/{id}") {
        val sessionId =
            call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session ID"))
                return@delete
            }

        val store = tenantScopedStore()
        val success =
            if (store != null) {
                // Tenant-filtered delete: a cross-tenant id is a no-op (NotFound).
                store.deleteSession(sessionId, call.resolveTenant())
            } else {
                SessionManager.closeSession(sessionId)
            }
        if (success) {
            call.respond(HttpStatusCode.OK, mapOf("message" to "Session closed"))
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
        }
    }

    get("/api/sessions/{id}/events") {
        val sessionId =
            call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session ID"))
                return@get
            }

        val session = call.resolveScopedSession(sessionId)
        if (session == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
            return@get
        }

        val events = session.store.all()
        call.respond(HttpStatusCode.OK, events)
    }

    get("/api/sessions/{id}/hierarchy") {
        val sessionId =
            call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session ID"))
                return@get
            }

        val session = call.resolveScopedSession(sessionId)
        if (session == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
            return@get
        }

        val scopeId = call.request.queryParameters["scopeId"]
        val tree = session.projectionService.getHierarchyTree(scopeId)

        call.respond(HttpStatusCode.OK, tree)
    }

    get("/api/sessions/{id}/metrics") {
        val sessionId =
            call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session ID"))
                return@get
            }

        // MANDATORY tenant scoping (Pitfall 3 / T-08-04): a cross-tenant id resolves
        // to null → 404 (never 403, never leaking another tenant's metrics existence).
        val session = call.resolveScopedSession(sessionId)
        if (session == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
            return@get
        }

        // Validate + clamp the leak threshold (T-08-05/V5): an unparseable/absent value
        // falls back to the server default; negative/absurd values are clamped to bounds.
        val leakThresholdMs =
            call.request.queryParameters["leakThresholdMs"]
                ?.toLongOrNull()
                ?.coerceIn(MIN_LEAK_MS, MAX_LEAK_MS)
                ?: DEFAULT_LEAK_MS

        // Wall-clock read basis (CR-01): leak age must subtract a fixed-origin epoch-millis
        // clock so it stays well-defined on the DB-rehydrated path across a restart.
        val snapshot = session.metricsProjection.snapshot(System.currentTimeMillis(), leakThresholdMs)
        call.respond(
            HttpStatusCode.OK,
            MetricsResponse(
                active = snapshot.active,
                peak = snapshot.peak,
                throughputPerSec = snapshot.throughputPerSec,
                dispatcherUtilization = snapshot.dispatcherUtilization,
                leaks = snapshot.leaks.map { LeakDto(it.coroutineId, it.label, it.aliveMs) },
                leakThresholdMs = leakThresholdMs,
            ),
        )
    }

    get("/api/sessions/{id}/threads") {
        val sessionId =
            call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session ID"))
                return@get
            }

        val session = call.resolveScopedSession(sessionId)
        if (session == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
            return@get
        }

        val activity = session.projectionService.getThreadActivity()

        call.respond(HttpStatusCode.OK, activity)
    }

    get("/api/sessions/{id}/coroutines/{coroutineId}/timeline") {
        val sessionId =
            call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session ID"))
                return@get
            }

        val coroutineId =
            call.parameters["coroutineId"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing coroutine ID"))
                return@get
            }

        val session = call.resolveScopedSession(sessionId)
        if (session == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
            return@get
        }

        val timeline = session.projectionService.getCoroutineTimeline(coroutineId)
        if (timeline == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Coroutine not found"))
            return@get
        }

        call.respond(HttpStatusCode.OK, timeline)
    }

    // Authenticated SSE stream. This route is registered INSIDE authenticatedApi { } (see Routing.kt),
    // so when auth is on it requires a credential. Browser EventSource cannot set an Authorization
    // header (Pitfall 2), so the jwt provider also reads the JWT from the `?token=<jwt>` query param
    // (SSE_TOKEN_QUERY_PARAM = "token"). No separate auth scheme here — the wrapper + jwt authHeader
    // fallback handle it. When auth is off, this passes through publicly (D-04a).
    //
    // Anti-buffering headers (PERF-03, Pitfall P3) MUST be appended BEFORE the SSE response
    // commits — setting them inside the `sse{}` handler no-ops, because that body runs in
    // SSEServerContent.writeTo() AFTER status+headers are flushed. A route-scoped intercept on
    // the stream path (and ONLY that path) appends them ahead of the SSE content (RESEARCH §4):
    //   X-Accel-Buffering: no   → tells nginx/proxies not to buffer the stream
    //   Cache-Control:   no-cache → defeats intermediary response caching
    route("/api/sessions/{id}/stream") {
        intercept(ApplicationCallPipeline.Plugins) {
            call.response.headers.append("X-Accel-Buffering", "no")
            call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
        }
        sse {
            val sessionId =
                call.parameters["id"] ?: run {
                    logger.warn("SSE connection attempted without session ID")
                    return@sse
                }

            // PRE-STREAM tenant scoping (CR-01 / D-03): resolve the session through the
            // tenant filter BEFORE the "connected" frame, the gauge increment, and any
            // bus subscription/replay. A cross-tenant id is indistinguishable from a
            // missing id (do not leak existence) and yields the SAME 404 error event —
            // tenant B never opens a stream nor triggers a replay of tenant A's events.
            val session = call.resolveScopedSession(sessionId)
            if (session == null) {
                logger.warn("SSE connection attempted for non-existent session: $sessionId")
                send(
                    ServerSentEvent(
                        data = """{"error": "Session not found"}""",
                        event = "error",
                    ),
                )
                return@sse
            }

            logger.info("SSE stream started for session: $sessionId")
            sseClientsGauge.incrementAndGet()

            try {
                // Flush status line + headers immediately: on a session with ZERO stored events
                // the replay loop writes nothing, so without this frame the response never
                // reaches the client (curl HTTP 000; the Vite proxy turns it into a 500, which
                // EventSource treats as FATAL — no auto-reconnect). A comment frame is invisible
                // to browser EventSource listeners, so no frontend changes are required.
                send(ServerSentEvent(comments = "connected"))

                // History replay, then live events — see sessionSseFrames for the subscribe-before-
                // snapshot ordering and the exactly-once watermark (#138).
                sessionSseFrames(
                    session = session,
                    config = call.application.egressConfig(),
                    onShedDelta = { delta -> sseSamplingDroppedGauge.addAndGet(delta) },
                ).collect { frame -> send(frame) }
            } catch (e: CancellationException) {
                // Normal client disconnect — Ktor cancels the handler. Rethrow to honor
                // cooperative cancellation; the finally block still decrements the gauge.
                logger.debug("SSE stream cancelled for session: {}", sessionId)
                throw e
            } catch (e: Exception) {
                logger.error("Error in SSE stream for session $sessionId", e)
            } finally {
                sseClientsGauge.decrementAndGet()
                logger.info("SSE stream ended for session: $sessionId")
            }
        }
    }
}

/**
 * The SSE frames for one client of [session]: the stored history (replay), then live events
 * through the structural-aware egress chain ([sseEgressFrames]).
 *
 * Ordering (#138): the live bus subscription is registered BEFORE the store is snapshotted
 * for replay — the snapshot is taken only once [com.jh.proj.coroutineviz.session.EventBus.stream]
 * reports the subscriber as registered. [VizSession.send] records and publishes each event
 * under one lock with a strictly increasing seq (store order == seq order == bus order), so
 * an event sent during setup is either in the snapshot (seq <= the snapshot's max seq, and
 * filtered out of the live stream) or arrives live with a higher seq: it is delivered
 * exactly once.
 *
 * There is no intermediate drop-oldest bridge in front of the egress chain: while replay
 * frames are written, live events queue in the chain's per-subscriber StructuralAwareBuffer
 * (lifecycle events are never shed; shed events are reported in `event: dropped` frames).
 * If this subscriber still falls behind the bus capacity, the evicted events are counted
 * by the bus (`events.dropped.bus`).
 *
 * Replay is never sampled, shed or batched — history must be complete.
 */
internal fun sessionSseFrames(
    session: VizSession,
    config: EgressConfig,
    onShedDelta: (Long) -> Unit = {},
): Flow<ServerSentEvent> =
    flow {
        coroutineScope {
            val subscribed = CompletableDeferred<Unit>()
            val watermark = CompletableDeferred<Long>()
            val live =
                session.bus
                    .stream(onSubscribed = { subscribed.complete(Unit) })
                    .filter { it.seq > watermark.await() }

            // Live frames are handed over one at a time (rendezvous); until replay is written,
            // the egress chain keeps draining the bus into its structural-aware buffer.
            val liveFrames = Channel<ServerSentEvent>(Channel.RENDEZVOUS)
            launch {
                try {
                    sseEgressFrames(upstream = live, config = config, onShedDelta = onShedDelta)
                        .collect { frame -> liveFrames.send(frame) }
                } finally {
                    liveFrames.close()
                }
            }

            subscribed.await()
            val storedEvents = session.store.all()
            watermark.complete(storedEvents.maxOfOrNull { it.seq } ?: 0L)
            logger.info("Replaying {} stored events for session: {}", storedEvents.size, session.sessionId)
            for (event in storedEvents) {
                emit(event.toSse())
            }
            for (frame in liveFrames) {
                emit(frame)
            }
        }
    }

internal fun VizEvent.toSse(): ServerSentEvent =
    ServerSentEvent(
        data = appJson.encodeToString(PolymorphicSerializer(VizEvent::class), this),
        event = kind,
        id = "$sessionId-$seq",
    )
