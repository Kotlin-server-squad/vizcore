package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.routes.HealthEvents.sec
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The ingest socket hands watermarks to [IngestStates] and records how each connection ended (spec C-3, C-7). */
class IngestWatermarkTest {
    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
        IngestStates.clear()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.clearAll()
        IngestStates.clear()
    }

    private fun ApplicationTestBuilder.wsClient() = createClient { install(WebSockets) }

    private suspend fun eventCount(
        client: io.ktor.client.HttpClient,
        sessionId: String,
    ): Int =
        com.jh.proj.coroutineviz.appJson
            .parseToJsonElement(client.get("/api/sessions/$sessionId/events").bodyAsText())
            .jsonArray.size

    @Test
    fun `a watermark is transport state, not a stored event`() =
        testApplication {
            application { module() }
            val client = wsClient()
            val sessionId = client.createSessionNamed("wm-app")

            client.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(HealthEvents.created(sessionId, "c1", at = sec(1)).frameText()))
                send(Frame.Text(HealthEvents.watermark(sessionId, at = sec(2)).frameText()))
                eventually { IngestStates.snapshot(sessionId).watermark != null }
            }

            assertEquals(1, eventCount(client, sessionId), "only the coroutine event is stored")
            val state = IngestStates.snapshot(sessionId)
            assertEquals(sec(2), state.watermark?.appNanos)
            assertNotNull(state.lastIngestEpochMs)
        }

    @Test
    fun `a watermark alone does not count as received data`() =
        testApplication {
            application { module() }
            val client = wsClient()
            val sessionId = client.createSessionNamed("wm-only")

            client.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(HealthEvents.watermark(sessionId, at = sec(2)).frameText()))
                eventually { IngestStates.snapshot(sessionId).watermark != null }
            }

            assertNull(IngestStates.snapshot(sessionId).lastIngestEpochMs)
        }

    @Test
    fun `a connection that ends after a final watermark closed cleanly`() =
        testApplication {
            application { module() }
            val client = wsClient()
            val sessionId = client.createSessionNamed("wm-clean")

            client.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(HealthEvents.created(sessionId, "c1", at = sec(1)).frameText()))
                send(Frame.Text(HealthEvents.watermark(sessionId, at = sec(2), final = true).frameText()))
                eventually { IngestStates.snapshot(sessionId).watermark != null }
            }
            eventually { !IngestConnections.isConnected(sessionId) }

            assertEquals(false, IngestStates.snapshot(sessionId).transport.deliveryUncertain)
        }

    @Test
    fun `a connection that ends without a final watermark makes delivery uncertain from its first seq`() =
        testApplication {
            application { module() }
            val client = wsClient()
            val sessionId = client.createSessionNamed("wm-unclean")

            client.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(HealthEvents.created(sessionId, "c1", at = sec(1)).frameText()))
                eventually { eventCount(client, sessionId) == 1 }
            }
            client.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(HealthEvents.created(sessionId, "c2", at = sec(2)).frameText()))
                send(Frame.Text(HealthEvents.watermark(sessionId, at = sec(3), final = true).frameText()))
                eventually { IngestStates.snapshot(sessionId).watermark != null }
            }
            eventually { !IngestConnections.isConnected(sessionId) }

            val transport = IngestStates.snapshot(sessionId).transport
            assertTrue(transport.deliveryUncertain, "the first connection never sent a final watermark")
            assertEquals(1L, transport.sinceSeq)
        }

    @Test
    fun `a frame the backend cannot read makes delivery uncertain`() =
        testApplication {
            application { module() }
            val client = wsClient()
            val sessionId = client.createSessionNamed("wm-garbage")

            client.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(HealthEvents.created(sessionId, "c1", at = sec(1)).frameText()))
                send(Frame.Text("{not json"))
                send(Frame.Text(HealthEvents.watermark(sessionId, at = sec(3), lost = 2, final = true).frameText()))
                eventually { IngestStates.snapshot(sessionId).watermark != null }
            }

            val transport = IngestStates.snapshot(sessionId).transport
            assertTrue(transport.deliveryUncertain)
            assertEquals(2L, transport.lostEvents)
            assertEquals(1L, transport.sinceSeq)
        }

    @Test
    fun `origin and provenance arrive with the events`() =
        testApplication {
            application { module() }
            val client = wsClient()
            val sessionId = client.createSessionNamed("wm-origin")

            client.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(HealthEvents.created(sessionId, "c1", at = sec(1)).frameText()))
                send(Frame.Text(HealthEvents.started(sessionId, "c1", at = sec(1)).frameText()))
                send(Frame.Text(HealthEvents.suspended(sessionId, "c1", at = sec(2)).frameText()))
                eventually { eventCount(client, sessionId) == 3 }
            }

            val summary = SessionManager.getSession(sessionId)!!.readSummaries { it.get("c1") }!!
            assertEquals(com.jh.proj.coroutineviz.events.CoroutineOrigin.APP, summary.origin)
            assertEquals(com.jh.proj.coroutineviz.events.SuspensionProvenance.REAL_FRAME, summary.lastSuspensionProvenance)
        }
}
