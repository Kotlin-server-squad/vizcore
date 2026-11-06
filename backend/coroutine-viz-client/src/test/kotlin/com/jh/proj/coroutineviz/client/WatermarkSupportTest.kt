package com.jh.proj.coroutineviz.client

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.PollWatermark
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.PolymorphicSerializer
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** P-11: a 1.2 server would store every watermark as an event, so watermarks go only to servers that serve health. */
class WatermarkSupportTest {
    @Test
    fun `the health answer says whether the server takes watermarks`() =
        testApplication {
            routing {
                get("/api/sessions/new/health") { call.respondText("{}") }
                get("/api/sessions/trimmed/health") {
                    call.respondText("""{"error":"x","code":"history-trimmed"}""", status = HttpStatusCode.ServiceUnavailable)
                }
                get("/api/sessions/proxy/health") {
                    call.respondText("<html>bad gateway</html>", status = HttpStatusCode.ServiceUnavailable)
                }
                get("/api/sessions/denied/health") { call.respondText("", status = HttpStatusCode.Forbidden) }
            }
            val http = createClient { }
            assertEquals(true, takesWatermarks(http, "", "new", "t", 5_000))
            assertEquals(true, takesWatermarks(http, "", "trimmed", "t", 5_000))
            assertEquals(false, takesWatermarks(http, "", "old", "t", 5_000), "404: a server before session health")
            assertNull(takesWatermarks(http, "", "proxy", "t", 5_000))
            assertNull(takesWatermarks(http, "", "denied", "t", 5_000))
        }

    @Test
    fun `a socket to an older server carries the events and no watermark`() =
        testApplication {
            val received = CopyOnWriteArrayList<VizEvent>()
            application {
                install(ServerWebSockets)
                routing {
                    webSocket("/api/sessions/{id}/ingest") {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                received += appJson.decodeFromString(PolymorphicSerializer(VizEvent::class), frame.readText())
                            }
                        }
                    }
                }
            }
            val http = createClient { install(WebSockets) }
            val session = VizSession("old")
            val scope = CoroutineScope(SupervisorJob())
            val buffer = OutboundBuffer()
            buffer.feed(session, scope)
            buffer.poll(session, 1, 1)
            session.send(CoroutineCreated("old", 1, 1, "c1", "j1", null, "sc", "e1"))
            buffer.poll(session, 2_000_000_000, 2)
            buffer.cutoff(session)
            val transport = scope.launch { stream(http, "", "old", "t", buffer, IngestTimeouts(), sendWatermarks = false) }
            transport.join() // the drain ends at the cutoff, the socket closes normally
            pollFor { received.takeIf { it.isNotEmpty() } }
            assertEquals(listOf("CoroutineCreated"), received.map { it.kind }, "the final watermark is held back too")
            scope.cancel()
            session.close()
        }
}
