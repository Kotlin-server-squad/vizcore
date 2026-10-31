package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.withSessionId
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.launch
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.decodeFromJsonElement
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

private val ingestLogger = LoggerFactory.getLogger("CoroutineVizIngest")

/**
 * Register the WebSocket ingest endpoint (RCO-05): the server half of real-app
 * transport. A remote client opens an authenticated WebSocket to
 * `/api/sessions/{id}/ingest` and streams text frames, each carrying one
 * serialized [VizEvent]; the server deserializes it and publishes it via
 * [com.jh.proj.coroutineviz.session.VizSession.ingest], feeding the EXISTING
 * EventStore → snapshot → EventBus → SSE → FE pipeline with zero downstream
 * changes.
 *
 * This route MUST be registered inside `authenticatedApi { rateLimit("api") { } }`
 * (see Routing.kt) so it inherits the same auth (D-04a fail-open / fail-closed),
 * per-client rate limit (the "api" stream bucket, rateLimit.stream), and tenant
 * scoping as every other protected route.
 *
 * Security invariants:
 *  - **AUTH-04 / T-07-01:** the target session is resolved server-side via
 *    [resolveScopedSession], which returns null for a cross-tenant or missing id
 *    → the handshake is refused (close VIOLATED_POLICY) with NO write.
 *  - **T-07-03:** the server NEVER trusts the frame's `sessionId`; it always
 *    publishes into the server-resolved session, and the server assigns `seq`:
 *    `ingest()` always replaces the frame's `seq` with the session's next seq,
 *    so a restarted, duplicate or out-of-range client `seq` never drops an event.
 *  - **T-07-05:** a malformed frame is skipped (per-frame `runCatching`) and the
 *    stream stays open — one bad frame never drops the connection.
 *  - **T-07-04:** a single frame is capped at 1 MiB by the WebSockets plugin
 *    (`maxFrameSize`), and the connect draws from the `rateLimit("api")` stream bucket.
 *
 * Lifetime: the handler registers the open socket on the session
 * ([com.jh.proj.coroutineviz.session.VizSession.ingestSocketOpened]) and releases it in a
 * `finally`, so [com.jh.proj.coroutineviz.session.RetentionPolicy] never evicts the session
 * a client is still streaming into (#129). If the session is deleted while the socket is
 * open, the socket is closed with `VIOLATED_POLICY` "Session not found", so the client
 * recreates a session instead of streaming into one that no longer exists.
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

        // Retention never evicts a session with an open ingest socket (#129). The
        // counter is incremented BEFORE the receive loop and decremented in a
        // `finally`, so it is exact on every exit path — normal close, client
        // disconnect, coroutine cancellation and an unexpected throw alike.
        session.ingestSocketOpened()
        // An open socket would otherwise keep publishing into a deleted session, where nobody
        // can read the events, and the client would never learn it should recreate one. Close
        // with the same "Session not found" the handshake uses, which the SDK treats as
        // "create a fresh session".
        val removed = AtomicBoolean(false)
        val sessionRemoved =
            launch {
                awaitSessionRemoved(session)
                removed.set(true)
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Session not found"))
            }
        // Registered right before the try and released first in the finally, so nothing between
        // the two can leave the session marked connected (which would hide Checks failures). A
        // retention decision may have deleted the session while this socket waited to register;
        // then no frame may be stored into it, and the client must learn to recreate it (#261).
        if (!IngestConnections.openedIfPresent(sessionId) { sessionStillExists(sessionId) }) {
            sessionRemoved.cancel()
            session.ingestSocketClosed()
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Session not found"))
            return@webSocket
        }
        try {
            for (frame in incoming) {
                // Frames already queued when the session was deleted must not land in it;
                // the watcher above is closing the socket.
                if (removed.get()) break
                if (frame is Frame.Text) {
                    publishFrame(session, sessionId, frame.readText())
                }
            }
        } finally {
            IngestConnections.closed(sessionId)
            sessionRemoved.cancel()
            session.ingestSocketClosed()
        }
    }
}

/**
 * Whether [sessionId] still exists, without rebuilding the session: a row lookup in database mode,
 * the in-memory map otherwise.
 */
private fun sessionStillExists(sessionId: String): Boolean =
    (SessionManager.backingStore() as? ExposedSessionStore)?.sessionExists(sessionId)
        ?: (SessionManager.getSession(sessionId) != null)

/**
 * Deserialize one text frame and publish it into the SERVER-resolved [session].
 *
 *  - T-07-05: a malformed frame is skipped (logged at debug); the caller keeps
 *    looping so the stream stays open — one bad frame never drops the connection.
 *  - T-07-03: the frame's own `sessionId` is REWRITTEN to the server-resolved
 *    [VizSession.sessionId] before the event is decoded ([withSessionId]), so the
 *    stored, replayed and streamed event carries the session the request resolved
 *    to — whatever id the client put in the body, including one naming another
 *    tenant's session or a session that does not exist. Only the target session is
 *    ever written to; the frame body never selects a destination.
 *  - The server also assigns `seq` (`ingest()` never keeps the client's value), so
 *    session order stays strictly increasing.
 */
private fun publishFrame(
    session: VizSession,
    sessionId: String,
    text: String,
) {
    val event =
        runCatching {
            val stamped = appJson.parseToJsonElement(text).withSessionId(session.sessionId)
            appJson.decodeFromJsonElement(PolymorphicSerializer(VizEvent::class), stamped)
        }.getOrElse {
            ingestLogger.debug("Skipped malformed ingest frame for session {}: {}", sessionId, it.message)
            return
        }
    session.ingest(event)
}
