@file:Suppress("DEPRECATION")

package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.auth.ApiKeyStore
import com.jh.proj.coroutineviz.auth.JwtConfig
import com.jh.proj.coroutineviz.auth.Role
import com.jh.proj.coroutineviz.session.SessionManager
import com.password4j.Password
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.sse.ServerSentEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AuthTest {
    private val jwtSecret = "auth-test-jwt-secret"

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
            install(ContentNegotiation) { json() }
        }

    private fun mintToken(role: Role = Role.RUNNER): String {
        val config =
            MapApplicationConfig(
                "auth.jwt.secret" to jwtSecret,
                "auth.jwt.issuer" to "coroutineviz",
                "auth.jwt.audience" to "coroutineviz-api",
                "auth.jwt.realm" to "coroutineviz",
                "auth.jwt.accessTtlMinutes" to "60",
            )
        return JwtConfig.fromConfig(config).sign("alice", role).token
    }

    /**
     * Build a minimal app wired with the REAL [configureAuth]/[authenticatedApi] dual-provider
     * stack against an injected config. When [rawApiKey] / [userPasswordHash] are null the
     * respective provider is unconfigured; when BOTH are null auth is fully disabled (D-04a).
     */
    private fun ApplicationTestBuilder.installRealAuthApp(
        rawApiKey: String? = null,
        userPasswordHash: String? = null,
    ) {
        environment {
            config =
                MapApplicationConfig().apply {
                    if (rawApiKey != null) {
                        put("auth.keys.size", "1")
                        put("auth.keys.0.name", "frontend")
                        put("auth.keys.0.hash", ApiKeyStore.sha256Hex(rawApiKey))
                        put("auth.keys.0.role", "runner")
                    }
                    if (userPasswordHash != null) {
                        put("auth.users.size", "1")
                        put("auth.users.0.username", "alice")
                        put("auth.users.0.passwordHash", userPasswordHash)
                        put("auth.users.0.role", "runner")
                        put("auth.jwt.secret", jwtSecret)
                    }
                }
        }
        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json() }
            install(SSE)
            configureAuth()
            routing {
                get("/health") { call.respond(HttpStatusCode.OK, mapOf("status" to "UP")) }
                get("/openapi.json") { call.respond(HttpStatusCode.OK, mapOf("openapi" to "3.0.0")) }
                get("/") { call.respondText("Hello World!") }
                post("/api/auth/token") { call.respond(HttpStatusCode.OK, mapOf("token" to "stub")) }
                authenticatedApi {
                    get("/api/sessions") {
                        call.respond(HttpStatusCode.OK, SessionManager.listSessions())
                    }
                    post("/api/sessions") {
                        val session = SessionManager.createSession(call.request.queryParameters["name"])
                        call.respond(HttpStatusCode.Created, mapOf("sessionId" to session.sessionId))
                    }
                    // Mirror the real authenticated SSE stream route so the query-param token
                    // contract (Plan 05) is verified here, not just assumed.
                    sse("/api/sessions/{id}/stream") {
                        send(ServerSentEvent(comments = "connected"))
                    }
                }
            }
        }
    }

    // -- Fully unconfigured: pass-through (D-04a, incl. jwt-also-unconfigured) -------

    @Test
    fun `when no API key configured via full module, API requests pass without key`() =
        testApplication {
            application { module() }
            val client = jsonClient()
            assertEquals(HttpStatusCode.OK, client.get("/api/sessions").status)
        }

    @Test
    fun `when no API key configured via full module, health passes`() =
        testApplication {
            application { module() }
            val client = jsonClient()
            assertEquals(HttpStatusCode.OK, client.get("/health").status)
        }

    @Test
    fun `when NEITHER keys nor users configured, protected route passes with no credential`() =
        testApplication {
            installRealAuthApp(rawApiKey = null, userPasswordHash = null)
            val client = jsonClient()
            assertEquals(HttpStatusCode.OK, client.get("/api/sessions").status)
        }

    // -- Public allowlist holds when auth is on -------------------------------------

    @Test
    fun `public allowlist (health, openapi, token) stays public when keys configured`() =
        testApplication {
            installRealAuthApp(rawApiKey = "secret-key")
            val client = jsonClient()
            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            assertEquals(HttpStatusCode.OK, client.get("/openapi.json").status)
            assertEquals(HttpStatusCode.OK, client.post("/api/auth/token").status)
            assertEquals(HttpStatusCode.OK, client.get("/").status)
        }

    // -- API-key enforcement (SHA-256 hashed config) --------------------------------

    @Test
    fun `when keys configured, request without key gets 401`() =
        testApplication {
            installRealAuthApp(rawApiKey = "secret-key")
            val client = jsonClient()
            val response = client.get("/api/sessions")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("Unauthorized", body["error"]?.jsonPrimitive?.content)
        }

    @Test
    fun `when keys configured, wrong key gets 401`() =
        testApplication {
            installRealAuthApp(rawApiKey = "secret-key")
            val client = jsonClient()
            val response = client.get("/api/sessions") { header("X-API-Key", "wrong-key") }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `when keys configured, correct key passes`() =
        testApplication {
            installRealAuthApp(rawApiKey = "secret-key")
            val client = jsonClient()
            val response = client.get("/api/sessions") { header("X-API-Key", "secret-key") }
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `when keys configured, POST without key gets 401`() =
        testApplication {
            installRealAuthApp(rawApiKey = "secret-key")
            val client = jsonClient()
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/sessions?name=test").status)
        }

    @Test
    fun `when keys configured, POST with correct key passes`() =
        testApplication {
            installRealAuthApp(rawApiKey = "secret-key")
            val client = jsonClient()
            val response = client.post("/api/sessions?name=test") { header("X-API-Key", "secret-key") }
            assertEquals(HttpStatusCode.Created, response.status)
        }

    // -- Either-credential acceptance (D-08) ----------------------------------------

    @Test
    fun `a valid X-API-Key OR a valid JWT both reach a protected route`() =
        testApplication {
            val passwordHash = Password.hash("pw").withArgon2().result
            installRealAuthApp(rawApiKey = "secret-key", userPasswordHash = passwordHash)
            val client = jsonClient()

            // X-API-Key path
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/sessions") { header("X-API-Key", "secret-key") }.status,
            )
            // JWT path
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/sessions") { header("Authorization", "Bearer ${mintToken()}") }.status,
            )
        }

    // -- SSE query-param token (Pitfall 2; locked cross-plan contract) ---------------

    @Test
    fun `JWT via ?token= query param authenticates the protected SSE stream route`() =
        testApplication {
            val passwordHash = Password.hash("pw").withArgon2().result
            installRealAuthApp(rawApiKey = "secret-key", userPasswordHash = passwordHash)
            val client = jsonClient()

            val token = mintToken()
            val ok = client.get("/api/sessions/some-id/stream?token=$token")
            assertEquals(HttpStatusCode.OK, ok.status)
        }

    @Test
    fun `missing or garbage ?token= on the SSE stream route returns 401`() =
        testApplication {
            val passwordHash = Password.hash("pw").withArgon2().result
            installRealAuthApp(rawApiKey = "secret-key", userPasswordHash = passwordHash)
            val client = jsonClient()

            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/sessions/some-id/stream").status,
            )
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/sessions/some-id/stream?token=garbage").status,
            )
        }
}
