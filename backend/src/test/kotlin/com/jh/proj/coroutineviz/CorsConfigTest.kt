package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * PROD-03 regression test: CORS allowed origins come from config (application.yaml / env var),
 * not from hardcoded literals.
 *
 * application.yaml configures:
 *   cors.allowedOrigins: ${CORS_ALLOWED_ORIGINS:"http://localhost:3000,http://127.0.0.1:3000"}
 *
 * This test:
 * 1. Asserts that a request with an Origin matching the configured default yields
 *    an Access-Control-Allow-Origin response header.
 * 2. Asserts that a request with an Origin NOT in the config does NOT yield
 *    Access-Control-Allow-Origin, demonstrating the value comes from config rather
 *    than a wildcard literal.
 */
class CorsConfigTest {
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
    fun `configured allowed origin receives Access-Control-Allow-Origin header`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            // "http://localhost:3000" is in the default cors.allowedOrigins config
            val response =
                client.get("/health") {
                    header(HttpHeaders.Origin, "http://localhost:3000")
                }

            val acao = response.headers[HttpHeaders.AccessControlAllowOrigin]
            assertEquals(
                "http://localhost:3000",
                acao,
                "Configured origin must be echoed back in Access-Control-Allow-Origin",
            )
        }

    @Test
    fun `non-configured origin does not receive Access-Control-Allow-Origin header`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            // "http://evil.example.com" is NOT in the config — should be rejected
            val response =
                client.get("/health") {
                    header(HttpHeaders.Origin, "http://evil.example.com")
                }

            val acao = response.headers[HttpHeaders.AccessControlAllowOrigin]
            assertNull(
                acao,
                "Non-configured origin must not receive Access-Control-Allow-Origin (value was: $acao)",
            )
        }
}
