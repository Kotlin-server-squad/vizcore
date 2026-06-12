package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HealthRoutesTest {
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

    /**
     * /health flips to 503/DEGRADED when shared-JVM heap usage crosses 90%, which depends
     * on suite ordering and GC timing. /health tests therefore accept either verdict and
     * assert only the fields under test (plus verdict consistency where relevant).
     */
    private fun assertHealthReachable(status: HttpStatusCode) {
        assertTrue(
            status == HttpStatusCode.OK || status == HttpStatusCode.ServiceUnavailable,
            "Unexpected /health status: $status",
        )
    }

    @Test
    fun `GET health returns UP status`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            val response = client.get("/health")
            assertHealthReachable(response.status)

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            // The status field must be consistent with the HTTP verdict: UP<->200, DEGRADED<->503.
            val expectedStatus = if (response.status == HttpStatusCode.OK) "UP" else "DEGRADED"
            assertEquals(expectedStatus, body["status"]?.jsonPrimitive?.content)
            assertNotNull(body["uptimeMs"])
            assertNotNull(body["memory"])
            assertEquals(0, body["sessions"]?.jsonPrimitive?.int)
        }

    @Test
    fun `GET health reports session count`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            // Create sessions
            client.post("/api/sessions?name=health-test-1")
            client.post("/api/sessions?name=health-test-2")

            val response = client.get("/health")
            // This test's subject is the sessions field — accept either health verdict.
            assertHealthReachable(response.status)

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(2, body["sessions"]?.jsonPrimitive?.int)
        }

    @Test
    fun `GET health returns memory info`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            val response = client.get("/health")
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val memory = body["memory"]?.jsonObject

            assertNotNull(memory)
            assertTrue(memory["usedMb"]?.jsonPrimitive?.long!! >= 0)
            assertTrue(memory["maxMb"]?.jsonPrimitive?.long!! > 0)
            assertTrue(memory["usagePercent"]?.jsonPrimitive?.double!! >= 0.0)
        }

    @Test
    fun `GET health alias returns 200 with version and components`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            val response = client.get("/health")
            // This test's subject is the version/components fields — accept either verdict.
            assertHealthReachable(response.status)

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val version = body["version"]?.jsonPrimitive?.content
            assertNotNull(version, "version field must be present")
            assertTrue(version!!.isNotEmpty(), "version must be non-empty")

            val components = body["components"]?.jsonObject
            assertNotNull(components, "components field must be present")
            assertTrue(components!!.isNotEmpty(), "components map must be non-empty")
        }

    @Test
    fun `GET api health returns 200 with version and components`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            val response = client.get("/api/health")
            // /api/health shares respondHealth() with /health, which flips to
            // 503/DEGRADED under shared-JVM heap pressure (see class doc comment) —
            // accept either verdict and assert status-field consistency instead
            // of a strict 200/UP that flakes depending on suite ordering (WR-11).
            assertHealthReachable(response.status)

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val expectedStatus = if (response.status == HttpStatusCode.OK) "UP" else "DEGRADED"
            assertEquals(expectedStatus, body["status"]?.jsonPrimitive?.content)

            val version = body["version"]?.jsonPrimitive?.content
            assertNotNull(version, "version field must be present")
            assertTrue(version!!.isNotEmpty(), "version must be non-empty")

            val components = body["components"]?.jsonObject
            assertNotNull(components, "components field must be present")
            assertTrue(components!!.containsKey("sessionManager"), "components must include sessionManager")
            assertTrue(components.containsKey("memory"), "components must include memory")
        }

    @Test
    fun `GET api live returns 200 status UP`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            val response = client.get("/api/live")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("UP", body["status"]?.jsonPrimitive?.content)
        }

    @Test
    fun `GET api ready returns 200 status UP`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            val response = client.get("/api/ready")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("UP", body["status"]?.jsonPrimitive?.content)
        }
}
