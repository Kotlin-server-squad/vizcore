package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.sseClientsGauge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.PolymorphicSerializer
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("CoroutineVizRouting")

/**
 * Per-client SSE live buffer capacity. Bounds the memory a slow/stalled SSE client
 * can pin on the server (WR-14): when the writer cannot keep up, the OLDEST buffered
 * events are dropped (with a logged warning) instead of growing without limit.
 */
private const val SSE_LIVE_BUFFER_CAPACITY = 4096

fun Route.registerSessionRoutes() {
    post("/api/sessions") {
        val name = call.request.queryParameters["name"]
        val session = SessionManager.createSession(name)

        logger.info("Created new session via API: ${session.sessionId}")

        call.respond(
            HttpStatusCode.Created,
            mapOf(
                "sessionId" to session.sessionId,
                "message" to "Session created successfully",
            ),
        )
    }

    get("/api/sessions") {
        val sessions = SessionManager.listSessions()
        logger.debug("Listing sessions: ${sessions.size} active")
        call.respond(HttpStatusCode.OK, sessions)
    }

    get("/api/sessions/{id}") {
        val sessionId =
            call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session ID"))
                return@get
            }

        val session = SessionManager.getSession(sessionId)
        if (session == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
            return@get
        }

        val snapshot =
            SessionSnapshotResponse(
                sessionId = session.sessionId,
                coroutineCount = session.snapshot.coroutines.size,
                eventCount = session.store.all().size,
                coroutines =
                    session.snapshot.coroutines.values.map { node ->
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

        val success = SessionManager.closeSession(sessionId)
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

        val session = SessionManager.getSession(sessionId)
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

        val session = SessionManager.getSession(sessionId)
        if (session == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
            return@get
        }

        val scopeId = call.request.queryParameters["scopeId"]
        val tree = session.projectionService.getHierarchyTree(scopeId)

        call.respond(HttpStatusCode.OK, tree)
    }

    get("/api/sessions/{id}/threads") {
        val sessionId =
            call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session ID"))
                return@get
            }

        val session = SessionManager.getSession(sessionId)
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

        val session = SessionManager.getSession(sessionId)
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

    sse("/api/sessions/{id}/stream") {
        val sessionId =
            call.parameters["id"] ?: run {
                logger.warn("SSE connection attempted without session ID")
                return@sse
            }

        val session = SessionManager.getSession(sessionId)
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

            coroutineScope {
                // Subscribe to live events BEFORE snapshotting the store: EventBus has
                // replay = 0, so any event emitted between the store snapshot and the
                // subscription would otherwise be permanently lost. Live events are
                // buffered during replay and drained with a seq filter to deduplicate.
                //
                // The buffer is BOUNDED (WR-14): an unbounded channel let a slow or
                // half-open client grow server memory without limit, because the
                // collector below never suspends. On overflow the oldest buffered
                // event is dropped and logged; the client can re-sync via /events.
                // (onUndeliveredElement fires only for overflow drops here — this
                // channel is never cancelled or closed-for-send explicitly.)
                val liveBuffer =
                    Channel<VizEvent>(
                        capacity = SSE_LIVE_BUFFER_CAPACITY,
                        onBufferOverflow = BufferOverflow.DROP_OLDEST,
                        onUndeliveredElement = { dropped ->
                            logger.warn(
                                "SSE live buffer overflow for session {} — dropped event " +
                                    "kind={} seq={} (slow client; stream gap, refetch /events)",
                                sessionId,
                                dropped.kind,
                                dropped.seq,
                            )
                        },
                    )
                launch {
                    session.bus.stream().collect { liveBuffer.send(it) }
                }

                // 1️⃣ Replay all stored events (history) — snapshot AFTER subscribing
                val storedEvents = session.store.all()
                logger.info("Replaying ${storedEvents.size} stored events for session: $sessionId")
                for (event in storedEvents) {
                    send(event.toSse())
                }

                // Track the last seq we've sent to avoid duplicates. The max-seq
                // watermark is sound because VizSession.send finalizes seq atomically
                // with the store append (WR-02/WR-12): store order == seq order ==
                // bus delivery order, so any live event with seq <= the snapshot max
                // was already part of the replayed snapshot.
                val lastReplayedSeq = storedEvents.maxOfOrNull { it.seq } ?: 0L

                // 2️⃣ Drain buffered + live events (filtering out already-replayed ones)
                for (event in liveBuffer) {
                    if (event.seq > lastReplayedSeq) {
                        send(event.toSse())
                    }
                }
            }
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

private fun VizEvent.toSse(): ServerSentEvent =
    ServerSentEvent(
        data = appJson.encodeToString(PolymorphicSerializer(VizEvent::class), this),
        event = kind,
        id = "$sessionId-$seq",
    )
