package com.jh.proj.coroutineviz.client

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.PolymorphicSerializer

/**
 * Open one authenticated client WebSocket to the backend ingest route and forward
 * every event the local [session] publishes on its bus.
 *
 * Connects to `{backendUrl}/api/sessions/{sessionId}/ingest` with the JWT in the
 * `Authorization: Bearer` HEADER (never the URL — T-07-02). Inside the socket it
 * collects `session.bus.stream()` and sends each [VizEvent] as a `Frame.Text`
 * serialized with the SHARED core [appJson] (Pitfall 3 — wire format never drifts
 * from the backend ingest / SSE / FE format).
 *
 * The collect runs until the connection drops or the calling scope is cancelled;
 * the caller ([VizcoreClient]) owns the reconnect/backoff loop around this call.
 */
suspend fun stream(
    httpClient: HttpClient,
    backendUrl: String,
    sessionId: String,
    token: String,
    session: VizSession,
) {
    httpClient.webSocket(
        urlString = "$backendUrl/api/sessions/$sessionId/ingest",
        request = { header(HttpHeaders.Authorization, "Bearer $token") },
    ) {
        session.bus.stream().collect { event ->
            send(Frame.Text(appJson.encodeToString(PolymorphicSerializer(VizEvent::class), event)))
        }
    }
}
