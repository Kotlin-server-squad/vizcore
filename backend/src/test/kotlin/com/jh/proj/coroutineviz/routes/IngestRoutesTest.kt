package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RCO-05 ingest publish path (memory mode, auth-off — D-04a). Proves that a text
 * frame sent over the ingest WebSocket is deserialized into a [VizEvent] and
 * published into the SERVER-resolved session, then replays over the existing
 * /events path with the server's SSE id prefix (Pitfall-1 contract, T-07-03), and
 * that a malformed frame is skipped without dropping the stream (T-07-05).
 */
class IngestRoutesTest {
    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.clearAll()
    }

    private fun ApplicationTestBuilder.wsClient() =
        createClient {
            install(ContentNegotiation) { json(appJson) }
            install(WebSockets)
        }

    private fun event(
        sessionId: String,
        coroutineId: String,
        label: String,
    ): CoroutineCreated =
        CoroutineCreated(
            sessionId = sessionId,
            seq = 1L,
            tsNanos = 0L,
            coroutineId = coroutineId,
            jobId = "j-1",
            parentCoroutineId = null,
            scopeId = "scope-1",
            label = label,
        )

    private fun frameText(e: VizEvent): String = appJson.encodeToString(PolymorphicSerializer(VizEvent::class), e)

    private suspend fun createSession(client: io.ktor.client.HttpClient): String {
        val resp = client.post("/api/sessions?name=ingest-test")
        assertEquals(HttpStatusCode.Created, resp.status)
        return Json
            .parseToJsonElement(resp.bodyAsText())
            .jsonObject["sessionId"]!!
            .jsonPrimitive.content
    }

    /**
     * Read /events for [sessionId], retrying until exactly [expected] events are
     * present (or the attempt budget is exhausted). The ingest publish happens
     * asynchronously relative to the client closing the WS, so a single read can
     * race ahead of the server consuming the frame — poll to make it deterministic.
     */
    private suspend fun eventsWhenSettled(
        client: io.ktor.client.HttpClient,
        sessionId: String,
        expected: Int,
    ): kotlinx.serialization.json.JsonArray {
        var arr = kotlinx.serialization.json.JsonArray(emptyList())
        repeat(100) {
            val resp = client.get("/api/sessions/$sessionId/events")
            assertEquals(HttpStatusCode.OK, resp.status)
            arr = Json.parseToJsonElement(resp.bodyAsText()).jsonArray
            if (arr.size >= expected) return arr
            kotlinx.coroutines.delay(20)
        }
        return arr
    }

    @Test
    fun `ingest publishes events into the session and they appear over events with SSE replay`() =
        testApplication {
            application { module() }
            val client = wsClient()

            val sessionId = createSession(client)

            // Construct the event WITH the server-resolved sessionId — the contract
            // the real client honors (Pitfall-1). The server publishes into the
            // server-resolved session regardless, and send() re-stamps seq.
            val synthetic = event(sessionId, "c-ingest", "ingested")
            client.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(frameText(synthetic)))
            }

            val arr = eventsWhenSettled(client, sessionId, expected = 1)
            assertEquals(1, arr.size, "the ingested event must land in the session")
            val first = arr[0].jsonObject
            assertEquals("c-ingest", first["coroutineId"]?.jsonPrimitive?.content)
            // T-07-03: the persisted event carries the SERVER session id, proving
            // the publish targeted the server-resolved session (not a wire-claimed id).
            assertEquals(sessionId, first["sessionId"]?.jsonPrimitive?.content)
        }

    @Test
    fun `malformed frame is skipped and the stream stays open`() =
        testApplication {
            application { module() }
            val client = wsClient()

            val sessionId = createSession(client)
            val good = event(sessionId, "c-good", "valid")

            client.webSocket("/api/sessions/$sessionId/ingest") {
                // One bad frame, then a valid frame on the SAME connection.
                send(Frame.Text("not json {"))
                send(Frame.Text(frameText(good)))
            }

            val arr = eventsWhenSettled(client, sessionId, expected = 1)
            assertEquals(1, arr.size, "only the valid event must land; the bad frame is skipped")
            assertTrue(
                arr[0].jsonObject["coroutineId"]?.jsonPrimitive?.content == "c-good",
                "the valid frame sent AFTER the malformed one must still be accepted (stream stayed open)",
            )
        }
}
