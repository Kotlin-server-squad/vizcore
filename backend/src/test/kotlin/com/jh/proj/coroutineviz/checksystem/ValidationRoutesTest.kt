package com.jh.proj.coroutineviz.checksystem

import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidationRoutesTest {
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
    fun `GET validate rules returns rule list`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            val response = client.get("/api/validate/rules")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertTrue(body.size > 0, "Should have at least one rule")

            // Check that each rule has name and description
            for (rule in body) {
                val obj = rule.jsonObject
                assertTrue(obj.containsKey("name"), "Rule should have a name")
                assertTrue(obj.containsKey("description"), "Rule should have a description")
            }

            // Check for specific expected rules
            val ruleNames = body.map { it.jsonObject["name"]!!.jsonPrimitive.content }
            assertTrue("CreatedHasStarted" in ruleNames, "Should include CreatedHasStarted rule")
            assertTrue("NoDuplicateSequenceNumbers" in ruleNames, "Should include NoDuplicateSequenceNumbers rule")
            assertTrue("EventsInExactOrder" in ruleNames, "Should advertise the EventsInExactOrder rule")
            assertTrue("EventOrdering" !in ruleNames, "Dead EventOrdering catalog entry should be retired")
        }

    @Test
    fun `POST validate session returns results for existing session`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            // Create a session first
            val createResponse = client.post("/api/sessions?name=validate-test")
            val createBody = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
            val sessionId = createBody["sessionId"]!!.jsonPrimitive.content

            // Validate the session (empty session, should still return results)
            val validateResponse = client.post("/api/validate/session/$sessionId")
            assertEquals(HttpStatusCode.OK, validateResponse.status)

            val body = Json.parseToJsonElement(validateResponse.bodyAsText()).jsonObject
            assertEquals(sessionId, body["sessionId"]?.jsonPrimitive?.content, "Should return correct session ID")
            assertTrue(body.containsKey("results"), "Response should contain results")
            assertTrue(body.containsKey("timing"), "Response should contain timing report")

            // The EventsInExactOrder rule must actually run (not just be advertised).
            val resultRules =
                body["results"]!!.jsonArray.map { it.jsonObject["ruleName"]?.jsonPrimitive?.content }
            assertTrue("EventsInExactOrder" in resultRules, "EventsInExactOrder must be among the run results")

            // Check timing report structure
            val timing = body["timing"]!!.jsonObject
            assertTrue(timing.containsKey("coroutineDurations"), "Timing should have coroutineDurations")
            assertTrue(timing.containsKey("suspensionDurations"), "Timing should have suspensionDurations")
            assertTrue(timing.containsKey("totalDuration"), "Timing should have totalDuration")
        }

    @Test
    fun `POST validate session returns 404 for non-existent session`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            val response = client.post("/api/validate/session/non-existent")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
}
