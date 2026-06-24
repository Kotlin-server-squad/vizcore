package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.serialization.PolymorphicSerializer
import org.slf4j.LoggerFactory

private val ingestLogger = LoggerFactory.getLogger("CoroutineVizIngest")

/**
 * Register the WebSocket ingest endpoint (RCO-05): the server half of real-app
 * transport. A remote client opens an authenticated WebSocket to
 * `/api/sessions/{id}/ingest` and streams text frames, each carrying one
 * serialized [VizEvent]; the server deserializes it and publishes it via
 * [com.jh.proj.coroutineviz.session.VizSession.send], feeding the EXISTING
 * EventStore → snapshot → EventBus → SSE → FE pipeline with zero downstream
 * changes.
 *
 * This route MUST be registered inside `authenticatedApi { rateLimit("api") { } }`
 * (see Routing.kt) so it inherits the same auth (D-04a fail-open / fail-closed),
 * per-IP rate limit (60/min), and tenant scoping as every other protected route.
 *
 * Security invariants:
 *  - **AUTH-04 / T-07-01:** the target session is resolved server-side via
 *    [resolveScopedSession], which returns null for a cross-tenant or missing id
 *    → the handshake is refused (close VIOLATED_POLICY) with NO write.
 *  - **T-07-03:** the server NEVER trusts the frame's `sessionId`; it always
 *    publishes into the server-resolved session, and `send()` re-stamps `seq`.
 *  - **T-07-05:** a malformed frame is skipped (per-frame `runCatching`) and the
 *    stream stays open — one bad frame never drops the connection.
 *  - **T-07-04:** a single frame is capped at 1 MiB by the WebSockets plugin
 *    (`maxFrameSize`), and the route inherits the per-IP `rateLimit("api")` bucket.
 */
fun Route.registerIngestRoutes() {
    webSocket("/api/sessions/{id}/ingest") {
        val sessionId = call.parameters["id"]
        if (sessionId == null) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing session ID"))
            return@webSocket
        }

        // AUTH-04: resolve the session under the request's tenant scope. A
        // cross-tenant or unknown id resolves to null → refuse with no write.
        val session = call.resolveScopedSession(sessionId)
        if (session == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Session not found"))
            return@webSocket
        }

        for (frame in incoming) {
            if (frame is Frame.Text) {
                publishFrame(session, sessionId, frame.readText())
            }
        }
    }
}

/**
 * Deserialize one text frame and publish it into the SERVER-resolved [session].
 *
 *  - T-07-05: a malformed frame is skipped (logged at debug); the caller keeps
 *    looping so the stream stays open — one bad frame never drops the connection.
 *  - T-07-03: the event is published into the server-resolved session, never a
 *    target looked up from the frame body; `send()` re-stamps `seq` for ordering.
 */
private fun publishFrame(
    session: VizSession,
    sessionId: String,
    text: String,
) {
    val event =
        runCatching {
            appJson.decodeFromString(PolymorphicSerializer(VizEvent::class), text)
        }.getOrElse {
            ingestLogger.debug("Skipped malformed ingest frame for session {}: {}", sessionId, it.message)
            return
        }
    session.send(event)
}
