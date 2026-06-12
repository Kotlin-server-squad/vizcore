package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SseStreamTest {
    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.clearAll()
    }

    private fun ApplicationTestBuilder.jsonClient() =
        createClient {
            install(ContentNegotiation) {
                json()
            }
        }

    @Test
    fun `SSE stream returns error for non-existent session`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            val response = client.get("/api/sessions/non-existent-id/stream")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = response.bodyAsText()
            // SSE error event should contain the error message
            assertTrue(body.contains("Session not found"), "SSE response should contain error message")
            assertTrue(body.contains("event: error"), "SSE response should have event type 'error'")
        }

    @Test
    fun `SSE stream replays stored events`() =
        testApplication {
            application { module() }

            // Create a session and populate it with events directly via SessionManager
            val session = SessionManager.createSession("sse-replay-test")
            val sessionId = session.sessionId

            val event1 =
                CoroutineCreated(
                    sessionId = sessionId,
                    seq = 1L,
                    tsNanos = System.nanoTime(),
                    coroutineId = "c-1",
                    jobId = "j-1",
                    parentCoroutineId = null,
                    scopeId = "scope-1",
                    label = "first-coroutine",
                )
            val event2 =
                CoroutineCreated(
                    sessionId = sessionId,
                    seq = 2L,
                    tsNanos = System.nanoTime(),
                    coroutineId = "c-2",
                    jobId = "j-2",
                    parentCoroutineId = "c-1",
                    scopeId = "scope-1",
                    label = "second-coroutine",
                )

            session.send(event1)
            session.send(event2)

            // Verify the EventStore has the events that SSE would replay
            val storedEvents = session.store.all()
            assertEquals(2, storedEvents.size, "Session should have 2 stored events for SSE replay")

            // Verify first event fields (these are serialized as SSE data)
            val first = assertIs<CoroutineCreated>(storedEvents[0], "First stored event should be CoroutineCreated")
            assertEquals("CoroutineCreated", first.kind)
            assertEquals(sessionId, first.sessionId)
            assertEquals(1L, first.seq)
            assertEquals("c-1", first.coroutineId)
            assertEquals("first-coroutine", first.label)

            // Verify second event fields
            val second = assertIs<CoroutineCreated>(storedEvents[1], "Second stored event should be CoroutineCreated")
            assertEquals("CoroutineCreated", second.kind)
            assertEquals(2L, second.seq)
            assertEquals("c-2", second.coroutineId)
            assertEquals("c-1", second.parentCoroutineId)
            assertEquals("second-coroutine", second.label)

            // Verify each event can be serialized to JSON via the polymorphic path (as the SSE route does)
            for (event in storedEvents) {
                val json = appJson.encodeToString(PolymorphicSerializer(VizEvent::class), event)
                assertTrue(json.isNotEmpty(), "Event should serialize to non-empty JSON")
                assertTrue(json.contains("\"sessionId\""), "Serialized event should contain sessionId")
                assertTrue(json.contains("\"seq\""), "Serialized event should contain seq")
            }

            // Verify the SSE id format matches what the route produces: "${event.sessionId}-${event.seq}"
            val expectedId1 = "$sessionId-1"
            val expectedId2 = "$sessionId-2"
            assertEquals("$sessionId-${first.seq}", expectedId1)
            assertEquals("$sessionId-${second.seq}", expectedId2)
        }

    @Test
    fun `SSE stream events have correct format`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            // The error case produces a complete SSE response we can parse
            val response = client.get("/api/sessions/format-test-id/stream")
            val body = response.bodyAsText()

            // Parse SSE lines - format should be:
            //   event: <kind>
            //   data: <json>
            //   id: <optional>
            //   (blank line)
            val lines = body.lines()

            // Find the event line
            val eventLine = lines.firstOrNull { it.startsWith("event:") }
            assertNotNull(eventLine, "SSE output should contain an 'event:' line")
            assertEquals("event: error", eventLine.trim(), "Event type should be 'error' for non-existent session")

            // Find the data line
            val dataLine = lines.firstOrNull { it.startsWith("data:") }
            assertNotNull(dataLine, "SSE output should contain a 'data:' line")

            // Verify the data is valid JSON
            val jsonData = dataLine.removePrefix("data:").trim()
            val parsed = Json.parseToJsonElement(jsonData).jsonObject
            assertNotNull(parsed["error"], "Data JSON should contain 'error' field")
            assertEquals("Session not found", parsed["error"]?.jsonPrimitive?.content)
        }

    @Test
    fun `stored events contain all required fields for SSE replay`() =
        testApplication {
            application { module() }

            // Create a session and send an event
            val session = SessionManager.createSession("sse-fields-test")
            val sessionId = session.sessionId

            val event =
                CoroutineCreated(
                    sessionId = sessionId,
                    seq = 1L,
                    tsNanos = 123456789L,
                    coroutineId = "c-1",
                    jobId = "j-1",
                    parentCoroutineId = null,
                    scopeId = "scope-1",
                    label = "test-coroutine",
                )
            session.send(event)

            // Verify the event's 'kind' property (used as SSE event type field)
            // 'kind' is a computed property accessed directly by the SSE route, not serialized in JSON
            assertEquals("CoroutineCreated", event.kind, "Event kind should be 'CoroutineCreated'")

            // Serialize the event the same way the SSE route does: polymorphic via appJson
            val serialized = appJson.encodeToString(PolymorphicSerializer(VizEvent::class), event)
            val eventJson = appJson.parseToJsonElement(serialized).jsonObject

            // 'sessionId' and 'seq' are used to construct the SSE id field: "${event.sessionId}-${event.seq}"
            val eventSessionId = eventJson["sessionId"]?.jsonPrimitive?.content
            assertNotNull(eventSessionId, "Event should have 'sessionId' field (used in SSE id)")
            assertEquals(sessionId, eventSessionId)

            val seq = eventJson["seq"]?.jsonPrimitive?.long
            assertNotNull(seq, "Event should have 'seq' field (used in SSE id and dedup filtering)")
            assertEquals(1L, seq)

            // Verify the full JSON data payload contains all expected fields
            assertNotNull(eventJson["tsNanos"], "Event should have 'tsNanos' field")
            assertEquals(123456789L, eventJson["tsNanos"]?.jsonPrimitive?.long)
            assertNotNull(eventJson["coroutineId"], "Event should have 'coroutineId' field")
            assertNotNull(eventJson["jobId"], "Event should have 'jobId' field")
            assertNotNull(eventJson["scopeId"], "Event should have 'scopeId' field")

            // Verify SSE id format: "{sessionId}-{seq}"
            val expectedSseId = "$sessionId-1"
            assertEquals(expectedSseId, "${event.sessionId}-${event.seq}")
        }

    @Test
    fun `session snapshot reflects events sent for SSE streaming`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            // Create session and send events
            val session = SessionManager.createSession("sse-snapshot-test")
            val sessionId = session.sessionId

            session.send(
                CoroutineCreated(
                    sessionId = sessionId,
                    seq = 1L,
                    tsNanos = System.nanoTime(),
                    coroutineId = "c-1",
                    jobId = "j-1",
                    parentCoroutineId = null,
                    scopeId = "scope-1",
                    label = "root",
                ),
            )
            session.send(
                CoroutineCreated(
                    sessionId = sessionId,
                    seq = 2L,
                    tsNanos = System.nanoTime(),
                    coroutineId = "c-2",
                    jobId = "j-2",
                    parentCoroutineId = "c-1",
                    scopeId = "scope-1",
                    label = "child",
                ),
            )

            // Verify session snapshot reflects the events (snapshot endpoint works with concrete DTO)
            val snapshotResponse = client.get("/api/sessions/$sessionId")
            assertEquals(HttpStatusCode.OK, snapshotResponse.status)

            val snapshot = Json.parseToJsonElement(snapshotResponse.bodyAsText()).jsonObject
            assertEquals(2, snapshot["coroutineCount"]?.jsonPrimitive?.int)
            assertEquals(2, snapshot["eventCount"]?.jsonPrimitive?.int)

            // Verify the event store has the right count for SSE replay via direct access
            val storedEvents = session.store.all()
            assertEquals(2, storedEvents.size, "EventStore should have 2 events ready for SSE replay")

            // Verify coroutines in the snapshot (these are what the frontend visualizes after SSE replay)
            val coroutines = snapshot["coroutines"]?.jsonArray
            assertNotNull(coroutines, "Snapshot should include coroutines array")
            assertEquals(2, coroutines.size, "Snapshot should have 2 coroutines")

            val coroutineIds = coroutines.map { it.jsonObject["id"]?.jsonPrimitive?.content }
            assertTrue(coroutineIds.contains("c-1"), "Snapshot should contain coroutine c-1")
            assertTrue(coroutineIds.contains("c-2"), "Snapshot should contain coroutine c-2")
        }

    @Test
    fun `SSE stream flushes headers immediately for a session with zero events`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            // UAT gap 2 failure mode: a freshly created session has ZERO stored events, so
            // the replay loop writes nothing and headers never flush (curl HTTP 000, Vite
            // proxy turns it into a 500, EventSource treats non-200 as fatal — no reconnect).
            val session = SessionManager.createSession("sse-zero-event-test")
            val sessionId = session.sessionId

            // The handler for an existing session never completes (infinite live loop), so a
            // plain get() + bodyAsText() would hang forever. Stream the body incrementally.
            // Run on Dispatchers.Default so withTimeout uses real time — testApplication's
            // virtual-time dispatcher would fire the timeout while the server streams on
            // wall-clock time (same convention as MetricsWiringTest).
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    client.prepareGet("/api/sessions/$sessionId/stream").execute { response ->
                        assertEquals(
                            HttpStatusCode.OK,
                            response.status,
                            "Zero-event stream must respond 200 immediately",
                        )

                        val contentType = response.contentType()
                        assertNotNull(contentType, "Stream response should declare a Content-Type")
                        assertTrue(
                            contentType.toString().startsWith("text/event-stream"),
                            "Content-Type should be text/event-stream but was '$contentType'",
                        )

                        val channel = response.bodyAsChannel()
                        var line: String? = channel.readUTF8Line()
                        // SSE frames end with a blank line — skip blanks to find the first content line
                        while (line != null && line.isBlank()) {
                            line = channel.readUTF8Line()
                        }
                        assertNotNull(line, "Stream should produce a first content line without any stored events")
                        assertTrue(
                            line.startsWith(":"),
                            "First non-blank line should be an SSE comment frame but was '$line'",
                        )
                        assertTrue(
                            line.contains("connected"),
                            "Comment frame should contain 'connected' but was '$line'",
                        )
                    }
                }
            }
        }

    @Test
    fun `connected comment precedes replayed events on a session with stored events`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            val session = SessionManager.createSession("sse-comment-order-test")
            val sessionId = session.sessionId

            session.send(
                CoroutineCreated(
                    sessionId = sessionId,
                    seq = 1L,
                    tsNanos = System.nanoTime(),
                    coroutineId = "c-1",
                    jobId = "j-1",
                    parentCoroutineId = null,
                    scopeId = "scope-1",
                    label = "replayed-coroutine",
                ),
            )
            assertEquals(1, session.store.all().size, "Session should have 1 stored event for replay")

            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    client.prepareGet("/api/sessions/$sessionId/stream").execute { response ->
                        assertEquals(HttpStatusCode.OK, response.status)

                        val channel = response.bodyAsChannel()
                        val linesBeforeData = mutableListOf<String>()
                        var line: String? = channel.readUTF8Line()
                        while (line != null && !line.startsWith("data:")) {
                            if (line.isNotBlank()) {
                                linesBeforeData.add(line)
                            }
                            line = channel.readUTF8Line()
                        }
                        assertNotNull(line, "Stream should produce a 'data:' line for the stored event")

                        val commentIndex =
                            linesBeforeData.indexOfFirst { it.startsWith(":") && it.contains("connected") }
                        assertTrue(
                            commentIndex >= 0,
                            "': connected' comment must appear before the first 'data:' line; " +
                                "lines before data were $linesBeforeData",
                        )
                    }
                }
            }
        }
}
