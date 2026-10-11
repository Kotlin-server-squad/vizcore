package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #124 (backend): the API rate limit is split into per-client read / write / stream
 * buckets plus a small login bucket, all sized from the `rateLimit.*` config block,
 * so a normal SPA session cannot exhaust its own budget. Runs the full [module] so the
 * real install(RateLimit) and route wrapping are exercised.
 */
class RateLimitPolicyTest {
    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.clearAll()
    }

    private fun ApplicationTestBuilder.fullModule(vararg overrides: Pair<String, String>) {
        environment {
            config = MapApplicationConfig(*overrides)
        }
        application { module() }
    }

    private fun HttpResponse.assertTooManyWithRetryAfter() {
        assertEquals(HttpStatusCode.TooManyRequests, status)
        val retryAfter = headers[HttpHeaders.RetryAfter]
        assertNotNull(retryAfter, "a 429 must carry Retry-After")
        assertTrue(retryAfter.toLong() >= 0, "Retry-After must be a delay in seconds, was $retryAfter")
    }

    @Test
    fun `default policy allows 600 read GETs from one client within a minute`() =
        testApplication {
            fullModule()
            repeat(600) { i ->
                val status = client.get("/api/sessions").status
                assertEquals(HttpStatusCode.OK, status, "read #${i + 1} was limited")
            }
        }

    @Test
    fun `reads and stream connects draw from separate buckets`() =
        testApplication {
            fullModule(
                "rateLimit.read.requestsPerMinute" to "5",
                "rateLimit.stream.requestsPerMinute" to "3",
            )
            // An unknown session id makes the SSE handler emit one error event and end the
            // stream, so each connect completes instead of holding the test open.
            repeat(3) {
                assertEquals(HttpStatusCode.OK, client.get("/api/sessions/missing/stream").status)
            }
            client.get("/api/sessions/missing/stream").assertTooManyWithRetryAfter()

            // Stream bucket exhausted; the read bucket is untouched.
            repeat(5) {
                assertEquals(HttpStatusCode.OK, client.get("/api/sessions").status)
            }
            client.get("/api/sessions").assertTooManyWithRetryAfter()
        }

    @Test
    fun `exhausted read bucket does not block stream connects`() =
        testApplication {
            fullModule(
                "rateLimit.read.requestsPerMinute" to "2",
                "rateLimit.stream.requestsPerMinute" to "2",
            )
            repeat(2) { assertEquals(HttpStatusCode.OK, client.get("/api/sessions").status) }
            client.get("/api/sessions").assertTooManyWithRetryAfter()

            assertEquals(HttpStatusCode.OK, client.get("/api/sessions/missing/stream").status)
        }

    @Test
    fun `login bucket returns 429 with Retry-After after its limit`() =
        testApplication {
            fullModule("rateLimit.login.requestsPerMinute" to "3")
            // No users are configured, so each attempt is rejected 401 — but it still counts.
            repeat(3) {
                assertEquals(HttpStatusCode.Unauthorized, client.post("/api/auth/token").status)
            }
            client.post("/api/auth/token").assertTooManyWithRetryAfter()

            // Login exhaustion does not spill into the API buckets.
            assertEquals(HttpStatusCode.OK, client.get("/api/sessions").status)
        }

    @Test
    fun `default login bucket is small`() =
        testApplication {
            fullModule()
            repeat(RateLimitPolicy.DEFAULT_LOGIN_RPM) {
                assertEquals(HttpStatusCode.Unauthorized, client.post("/api/auth/token").status)
            }
            client.post("/api/auth/token").assertTooManyWithRetryAfter()
        }

    @Test
    fun `session create still succeeds after a burst of reads`() =
        testApplication {
            fullModule()
            repeat(300) {
                assertEquals(HttpStatusCode.OK, client.get("/api/sessions").status)
            }
            val created = client.post("/api/sessions?name=after-burst&correlation=rl-policy-test")
            assertEquals(HttpStatusCode.Created, created.status)
        }

    @Test
    fun `writes have their own bucket`() =
        testApplication {
            fullModule(
                "rateLimit.write.requestsPerMinute" to "2",
                "rateLimit.sessionCreate.requestsPerMinute" to "100",
            )
            repeat(2) {
                assertEquals(HttpStatusCode.Created, client.post("/api/sessions?name=w$it").status)
            }
            client.post("/api/sessions?name=w-over").assertTooManyWithRetryAfter()
            assertEquals(HttpStatusCode.OK, client.get("/api/sessions").status)
        }

    @Test
    fun `policy defaults and config overrides`() {
        val defaults = RateLimitPolicy.fromConfig(MapApplicationConfig())
        assertTrue(defaults.read >= 1200)
        assertTrue(defaults.write >= 300)
        assertEquals(120, defaults.stream)
        assertEquals(10, defaults.login)
        assertEquals(10, defaults.sessionCreate)

        val overridden =
            RateLimitPolicy.fromConfig(
                MapApplicationConfig(
                    "rateLimit.read.requestsPerMinute" to "7",
                    "rateLimit.write.requestsPerMinute" to "not-a-number",
                    "rateLimit.stream.requestsPerMinute" to "0",
                ),
            )
        assertEquals(7, overridden.read)
        assertEquals(RateLimitPolicy.DEFAULT_WRITE_RPM, overridden.write)
        assertEquals(RateLimitPolicy.DEFAULT_STREAM_RPM, overridden.stream)
    }
}
