package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.routes.HealthEvents.sec
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionFactsTest {
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

    @Test
    fun `a demo session reports its kind, start, capture start and retention`() =
        testApplication {
            application { module() }
            val sessionId = client.createSessionNamed("scenario-facts")
            val session = SessionManager.getSession(sessionId)!!
            session.send(HealthEvents.created(sessionId, "a", at = sec(7)))
            session.send(HealthEvents.started(sessionId, "a", at = sec(5)))

            val body = client.getJson("/api/sessions/$sessionId")

            assertEquals("DEMO", body["kind"]!!.jsonPrimitive.content)
            assertEquals(session.createdAtMs, body["startedAtEpochMs"]!!.jsonPrimitive.long)
            assertEquals(sec(5), body["captureStartAppNanos"]!!.jsonPrimitive.long)
            assertEquals("REPORTED", body["origin"]!!.jsonPrimitive.content)
            assertEquals(false, body["appConnected"]!!.jsonPrimitive.boolean)
            assertEquals(JsonNull, body["lastWatermark"])
            assertEquals(1L, body["oldestRetainedSeq"]!!.jsonPrimitive.long)
            assertEquals(false, body["trimmed"]!!.jsonPrimitive.boolean)
            // Existing fields are unchanged.
            assertEquals(2, body["eventCount"]!!.jsonPrimitive.content.toInt())
        }

    @Test
    fun `a connected app reports its connection, last data and last watermark`() =
        testApplication {
            application { module() }
            val ws = createClient { install(WebSockets) }
            val sessionId = ws.createSessionNamed("facts-app")

            ws.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(HealthEvents.created(sessionId, "a", at = sec(1), origin = null).frameText()))
                send(Frame.Text(HealthEvents.watermark(sessionId, at = sec(2)).frameText()))
                eventually { IngestStates.snapshot(sessionId).watermark != null }

                val body = ws.getJson("/api/sessions/$sessionId")

                assertEquals("ATTACHED", body["kind"]!!.jsonPrimitive.content)
                assertEquals(true, body["appConnected"]!!.jsonPrimitive.boolean)
                assertTrue(body["lastIngestEpochMs"]!!.jsonPrimitive.long > 0)
                assertEquals(sec(2), body["lastWatermark"]!!.jsonObject["appNanos"]!!.jsonPrimitive.long)
                assertEquals("UNKNOWN", body["origin"]!!.jsonPrimitive.content, "no coroutine reported an origin")
            }
        }
}
