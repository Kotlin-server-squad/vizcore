package com.jh.proj.coroutineviz.client

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.VizEvent
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.serialization.PolymorphicSerializer

/**
 * Open one authenticated client WebSocket to the backend ingest route and forward
 * every event held by the lifetime-scoped [buffer].
 *
 * Connects to `{backendUrl}/api/sessions/{sessionId}/ingest` with the JWT in the
 * `Authorization: Bearer` HEADER (never the URL — T-07-02). Inside the socket it
 * DRAINS the [OutboundBuffer] (NOT `session.bus` directly — that per-socket subscribe
 * is exactly the CR-01 bug that lost events outside the open window) and sends each
 * [VizEvent] as a `Frame.Text` serialized with the SHARED core [appJson] (Pitfall 3 —
 * wire format never drifts from the backend ingest / SSE / FE format).
 *
 * The drain runs until the connection drops or the calling scope is cancelled; on
 * either, the buffer and its retained events SURVIVE for the next socket. The caller
 * ([VizcoreClient]) owns the reconnect/backoff loop around this call and feeds the
 * buffer exactly once for the client lifetime.
 */
suspend fun stream(
    httpClient: HttpClient,
    backendUrl: String,
    sessionId: String,
    token: String,
    buffer: OutboundBuffer,
) {
    // Ktor's client WebSockets plugin only performs the upgrade handshake for the ws/wss
    // scheme; an http/https URL is sent as a plain GET (NoTransformationFoundException). Convert
    // the REST backendUrl scheme to its WebSocket equivalent. A scheme-less value (e.g. "" in the
    // in-process round-trip test) is left untouched so the relative-URL path still works.
    val wsBackendUrl =
        when {
            backendUrl.startsWith("https://", ignoreCase = true) -> "wss://" + backendUrl.substring("https://".length)
            backendUrl.startsWith("http://", ignoreCase = true) -> "ws://" + backendUrl.substring("http://".length)
            else -> backendUrl
        }
    httpClient.webSocket(
        urlString = "$wsBackendUrl/api/sessions/$sessionId/ingest",
        request = { header(HttpHeaders.Authorization, "Bearer $token") },
    ) {
        buffer.drain { event ->
            send(Frame.Text(appJson.encodeToString(PolymorphicSerializer(VizEvent::class), event)))
        }
    }
}
