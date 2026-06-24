package com.jh.proj.coroutineviz

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlin.time.Duration.Companion.seconds

/**
 * Maximum size of a single inbound WebSocket frame, in bytes (1 MiB). Caps the
 * memory a single ingest frame can pin on the server before it is rejected
 * (T-07-04 DoS mitigation). One synthetic [com.jh.proj.coroutineviz.events.VizEvent]
 * serializes to well under this bound.
 */
private const val MAX_FRAME_SIZE_BYTES = 1L shl 20

/**
 * Install the Ktor [WebSockets] server plugin ONCE (Ktor forbids a double
 * install, so this is the single install site, mirroring [configureSerialization]).
 * Must run before any `webSocket { }` route is registered — see [module] ordering.
 *
 * The ingest route (`/api/sessions/{id}/ingest`, RCO-05) depends on this:
 *  - `pingPeriod`/`timeout` reap half-open clients (T-07-04).
 *  - `maxFrameSize` caps a single inbound frame at 1 MiB (T-07-04).
 *  - `masking = false` — the server does not mask the frames it sends (clients
 *    still mask their outbound frames per RFC 6455).
 */
fun Application.configureWebSockets() {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = MAX_FRAME_SIZE_BYTES
        masking = false
    }
}
