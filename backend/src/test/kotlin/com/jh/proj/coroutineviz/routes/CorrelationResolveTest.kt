@file:Suppress("DEPRECATION")

package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.auth.JwtConfig
import com.jh.proj.coroutineviz.auth.Role
import com.jh.proj.coroutineviz.auth.UserPrincipal
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.persistence.DatabaseFactory
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.session.SessionManager
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

/**
 * Contract proofs for session correlation (CORR-01/CORR-02, D-04/D-05/D-09/D-10).
 *
 * The unscoped cases (404-until-bound, 200-after, last-write-wins, evict-on-close, 400-on-missing)
 * run through the full [module] (memory mode → global visibility, D-04b) so the real
 * `POST /api/sessions` create path, the resolve route, and the `addOnSessionClosed` eviction hook
 * wired in `configureRouting()` are all exercised. The cross-tenant case wires the REAL routes
 * behind a REAL JWT block over an H2-backed [ExposedSessionStore] (mirroring [MetricsRouteTest])
 * so `resolveScopedSession` actually filters and a cross-tenant token returns 404 — NEVER 403.
 *
 * [CorrelationRegistry] is an object singleton shared across tests, and [SessionManager] accretes
 * close-listeners, so both are reset in @BeforeEach/@AfterEach.
 */
@OptIn(ExperimentalTime::class)
@Suppress("TooManyFunctions") // fixture helpers (jwt/client/h2) + 6 contract cases exceed the default threshold
class CorrelationResolveTest {
    private val testSecret = "test-jwt-secret-do-not-use-in-prod"

    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
        SessionManager.clearSessionListeners()
        CorrelationRegistry.clear()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.useStore(null)
        SessionManager.clearAll()
        SessionManager.clearSessionListeners()
        CorrelationRegistry.clear()
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ContentNegotiation) { json() } }

    // --- Unscoped (memory-mode, full module) cases -------------------------------------------

    @Test
    fun `resolve returns 404 before any binding`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            val resp = client.get("/api/sessions/resolve?correlation=never-bound")
            assertEquals(HttpStatusCode.NotFound, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("Session not found", body["error"]?.jsonPrimitive?.content)
        }

    @Test
    fun `POST with correlation then resolve returns 200 with the created session id`() =
        testApplication {
            application { module() }
            val client = jsonClient()
            val token = UUID.randomUUID().toString()

            val createResp = client.post("/api/sessions?name=order-service&correlation=$token")
            assertEquals(HttpStatusCode.Created, createResp.status)
            val createdId =
                Json
                    .parseToJsonElement(createResp.bodyAsText())
                    .jsonObject["sessionId"]
                    ?.jsonPrimitive
                    ?.content
            assertNotNull(createdId)

            val resolveResp = client.get("/api/sessions/resolve?correlation=$token")
            assertEquals(HttpStatusCode.OK, resolveResp.status)
            val resolvedId =
                Json
                    .parseToJsonElement(resolveResp.bodyAsText())
                    .jsonObject["sessionId"]
                    ?.jsonPrimitive
                    ?.content
            assertEquals(createdId, resolvedId, "resolve must return the id the backend actually created")
        }

    @Test
    fun `last-write-wins - re-posting the same token re-points resolve to the newest session`() =
        testApplication {
            application { module() }
            val client = jsonClient()
            val token = UUID.randomUUID().toString()

            val firstId =
                Json
                    .parseToJsonElement(
                        client.post("/api/sessions?name=first&correlation=$token").bodyAsText(),
                    ).jsonObject["sessionId"]
                    ?.jsonPrimitive
                    ?.content
            val secondId =
                Json
                    .parseToJsonElement(
                        client.post("/api/sessions?name=second&correlation=$token").bodyAsText(),
                    ).jsonObject["sessionId"]
                    ?.jsonPrimitive
                    ?.content
            assertNotNull(secondId)
            assertFalse(firstId == secondId, "the two creates must mint distinct session ids")

            val resolvedId =
                Json
                    .parseToJsonElement(
                        client.get("/api/sessions/resolve?correlation=$token").bodyAsText(),
                    ).jsonObject["sessionId"]
                    ?.jsonPrimitive
                    ?.content
            assertEquals(secondId, resolvedId, "resolve must return the SECOND (newest) session id (D-09)")
        }

    @Test
    fun `closing the bound session evicts the binding so resolve returns 404 again`() =
        testApplication {
            application { module() }
            val client = jsonClient()
            val token = UUID.randomUUID().toString()

            val createdId =
                Json
                    .parseToJsonElement(
                        client.post("/api/sessions?name=ephemeral&correlation=$token").bodyAsText(),
                    ).jsonObject["sessionId"]
                    ?.jsonPrimitive
                    ?.content
            assertNotNull(createdId)
            assertEquals(HttpStatusCode.OK, client.get("/api/sessions/resolve?correlation=$token").status)

            // Closing the session must fire addOnSessionClosed → CorrelationRegistry.evict (D-10).
            client.delete("/api/sessions/$createdId")

            val afterClose = client.get("/api/sessions/resolve?correlation=$token")
            assertEquals(HttpStatusCode.NotFound, afterClose.status, "binding must be evicted on close")
        }

    @Test
    fun `resolve with a missing correlation param returns 400`() =
        testApplication {
            application { module() }
            val client = jsonClient()

            val resp = client.get("/api/sessions/resolve")
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("Missing correlation token", body["error"]?.jsonPrimitive?.content)
        }

    // --- Tenant-scoped (H2 + JWT) cross-tenant case ------------------------------------------

    @Test
    fun `cross-tenant token returns 404 not 403 and does not leak the bound session id`() =
        testApplication {
            val name = "test_${UUID.randomUUID().toString().replace("-", "")}"
            val dataSource = h2DataSource("jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE")
            val db: Database = DatabaseFactory.init(dataSource as DataSource)
            SessionManager.useStore(ExposedSessionStore(db))

            installAuthedApp()
            val client = jsonClient()
            val token = UUID.randomUUID().toString()

            // Alice creates a session WITH the correlation token (binding is recorded at create time).
            val createResp =
                client.post("/api/sessions?name=alice-work&correlation=$token") {
                    header("Authorization", "Bearer ${jwt("alice")}")
                }
            assertEquals(HttpStatusCode.Created, createResp.status)
            val aliceSessionId =
                Json
                    .parseToJsonElement(createResp.bodyAsText())
                    .jsonObject["sessionId"]
                    ?.jsonPrimitive
                    ?.content
            assertNotNull(aliceSessionId)

            // Bob resolves the SAME token: the registry hit is re-validated via resolveScopedSession,
            // which filters it out for Bob → 404, NEVER 403, and never echoes Alice's session id.
            val bobResp =
                client.get("/api/sessions/resolve?correlation=$token") {
                    header("Authorization", "Bearer ${jwt("bob")}")
                }
            assertEquals(HttpStatusCode.NotFound, bobResp.status, "cross-tenant must be 404 (not 403)")
            assertFalse(bobResp.bodyAsText().contains(aliceSessionId), "404 body must not leak alice's session id")

            // Sanity: Alice herself still resolves to her session (the binding exists; only the tenant gate differs).
            val aliceResp =
                client.get("/api/sessions/resolve?correlation=$token") {
                    header("Authorization", "Bearer ${jwt("alice")}")
                }
            assertEquals(HttpStatusCode.OK, aliceResp.status, "owner must still resolve her own session")

            dataSource.close()
        }

    // --- Tenant-scoped harness helpers (mirror MetricsRouteTest) -------------------------------

    private fun jwtConfig(): JwtConfig =
        JwtConfig.fromConfig(
            MapApplicationConfig(
                "auth.jwt.secret" to testSecret,
                "auth.jwt.issuer" to "coroutineviz",
                "auth.jwt.audience" to "coroutineviz-api",
                "auth.jwt.realm" to "coroutineviz",
                "auth.jwt.accessTtlMinutes" to "60",
            ),
        )

    private fun jwt(
        userId: String,
        role: Role = Role.RUNNER,
    ): String = jwtConfig().sign(userId, role).token

    private fun ApplicationTestBuilder.installAuthedApp() {
        val jwtConfig = jwtConfig()
        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json(appJson) }
            install(io.ktor.server.sse.SSE)
            install(Authentication) {
                jwt("jwt") {
                    realm = jwtConfig.realm
                    verifier(jwtConfig.verifier()!!)
                    validate { cred ->
                        val sub = cred.payload.subject ?: return@validate null
                        val role = Role.fromConfig(cred.payload.getClaim("role").asString())
                        UserPrincipal(userId = sub, role = role)
                    }
                }
            }
            install(io.ktor.server.plugins.ratelimit.RateLimit) {
                register(
                    io.ktor.server.plugins.ratelimit
                        .RateLimitName("session-create"),
                ) {
                    rateLimiter(limit = 10_000, refillPeriod = 1.minutes)
                }
            }
            routing {
                authenticate("jwt") {
                    registerSessionRoutes()
                }
            }
        }
    }

    private fun h2DataSource(url: String): HikariDataSource {
        val config =
            HikariConfig().apply {
                jdbcUrl = url
                driverClassName = "org.h2.Driver"
                username = "sa"
                password = ""
                maximumPoolSize = 4
                isAutoCommit = false
            }
        return HikariDataSource(config)
    }
}
