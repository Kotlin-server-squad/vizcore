package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.flow.FlowBackpressure
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end integration test for GET /api/sessions/{id}/events.
 *
 * This is the acceptance test for FIX-01: before the fix, the endpoint threw
 * SerializationException when encoding List<VizEvent> because VizEvent was not
 * registered in a polymorphic SerializersModule.
 *
 * After FIX-01:
 * - appJson is wired into ContentNegotiation
 * - All 66 VizEvent subclasses are registered in vizEventSerializersModule
 * - GET /api/sessions/{id}/events returns a valid JSON array with type discriminators
 */
class SessionEventsIntegrationTest {
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
                json(appJson)
            }
        }

    @Test
    fun `GET events endpoint returns 200 with polymorphic VizEvent JSON array (FIX-01)`() =
        testApplication {
            application { module() }

            val session = SessionManager.createSession("integration-test")
            val sessionId = session.sessionId

            // Emit a mix of VizEvent types including flow events (the ones that had missing @SerialName)
            session.send(
                CoroutineCreated(
                    sessionId = sessionId,
                    seq = 1L,
                    tsNanos = 0L,
                    coroutineId = "c-1",
                    jobId = "j-1",
                    parentCoroutineId = null,
                    scopeId = "scope-1",
                    label = "root",
                ),
            )
            session.send(
                FlowBackpressure(
                    sessionId = sessionId,
                    seq = 2L,
                    tsNanos = 0L,
                    flowId = "f-1",
                    collectorId = "c-1",
                    reason = "slow_collector",
                    pendingEmissions = 3,
                    bufferCapacity = 10,
                    durationNanos = null,
                ),
            )

            val client = jsonClient()
            val response = client.get("/api/sessions/$sessionId/events")

            // Before FIX-01 this would return 500 with SerializationException
            assertEquals(HttpStatusCode.OK, response.status, "GET /events should return 200 OK")

            val body = response.bodyAsText()
            assertTrue(body.isNotBlank(), "Response body should not be blank")

            // Verify it's a valid JSON array
            val jsonArray = Json.parseToJsonElement(body).jsonArray
            assertEquals(2, jsonArray.size, "Response should contain 2 events")

            // Verify polymorphic type discriminator is present
            val firstEvent = jsonArray[0].jsonObject
            assertNotNull(firstEvent["type"], "Polymorphic events must include 'type' discriminator field")
            val firstType = firstEvent["type"]?.jsonPrimitive?.content
            assertTrue(
                firstType?.contains("CoroutineCreated") == true,
                "First event type discriminator should contain 'CoroutineCreated', got: $firstType",
            )

            val secondEvent = jsonArray[1].jsonObject
            assertNotNull(secondEvent["type"], "FlowBackpressure event must include 'type' discriminator")
            val secondType = secondEvent["type"]?.jsonPrimitive?.content
            assertTrue(
                secondType?.contains("FlowBackpressure") == true,
                "Second event type discriminator should contain 'FlowBackpressure', got: $secondType",
            )
        }

    @Test
    fun `GET events endpoint response round-trips to VizEvent via polymorphic decoder (FIX-01)`() =
        testApplication {
            application { module() }

            val session = SessionManager.createSession("round-trip-test")
            val sessionId = session.sessionId

            val originalEvent =
                CoroutineCreated(
                    sessionId = sessionId,
                    seq = 1L,
                    tsNanos = 42L,
                    coroutineId = "c-1",
                    jobId = "j-1",
                    parentCoroutineId = null,
                    scopeId = "scope-1",
                    label = "test-label",
                )
            session.send(originalEvent)

            val client = jsonClient()
            val response = client.get("/api/sessions/$sessionId/events")
            assertEquals(HttpStatusCode.OK, response.status)

            // The JSON array from the endpoint should be decodable as List<VizEvent>
            val body = response.bodyAsText()
            val jsonArray = Json.parseToJsonElement(body).jsonArray
            assertEquals(1, jsonArray.size)

            // Round-trip each element: the JSON produced by the server should decode back
            // into the correct VizEvent subtype via the polymorphic serializer
            for (element in jsonArray) {
                val decoded = appJson.decodeFromJsonElement(PolymorphicSerializer(VizEvent::class), element)
                assertTrue(
                    decoded is CoroutineCreated,
                    "Decoded event should be CoroutineCreated, got: ${decoded::class.simpleName}",
                )
                val decodedCreated = decoded as CoroutineCreated
                assertEquals("c-1", decodedCreated.coroutineId)
                assertEquals("test-label", decodedCreated.label)
                assertEquals(42L, decodedCreated.tsNanos)
            }
        }

    @Test
    fun `GET events returns empty array for session with no events`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            val session = SessionManager.createSession("empty-events-test")
            val sessionId = session.sessionId

            val response = client.get("/api/sessions/$sessionId/events")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = response.bodyAsText()
            val jsonArray = Json.parseToJsonElement(body).jsonArray
            assertEquals(0, jsonArray.size, "Empty session should return empty array")
        }

    @Test
    fun `GET events returns 404 for non-existent session`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            val response = client.get("/api/sessions/nonexistent-id/events")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
}
