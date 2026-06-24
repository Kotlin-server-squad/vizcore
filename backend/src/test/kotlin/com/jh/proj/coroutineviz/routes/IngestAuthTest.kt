@file:Suppress("DEPRECATION")

package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.auth.JwtConfig
import com.jh.proj.coroutineviz.auth.Role
import com.jh.proj.coroutineviz.auth.TenantContext
import com.jh.proj.coroutineviz.auth.UserPrincipal
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.persistence.DatabaseFactory
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * AUTH-04 + credential enforcement for the ingest WebSocket (RCO-05). Wires the
 * REAL ingest route behind a REAL JWT `authenticate("jwt")` block over an
 * H2-backed [ExposedSessionStore], so `call.resolveScopedSession` resolves the
 * tenant from the verified JWT `sub` exactly as production does.
 *
 * Invariants under test:
 *  - T-07-01/AUTH-04: a tenant-A token CANNOT ingest into a tenant-B session — the
 *    handshake is refused (VIOLATED_POLICY) and tenant-B's session gets NO write.
 *  - AUTH-05/D-04a: with auth CONFIGURED, an ingest connection with no Bearer
 *    header is rejected on the upgrade. The JWT travels in the Authorization
 *    header, never the URL (T-07-02).
 */
class IngestAuthTest {
    private val testSecret = "test-jwt-secret-do-not-use-in-prod"

    private lateinit var dataSource: HikariDataSource
    private lateinit var db: Database
    private lateinit var store: ExposedSessionStore

    @BeforeEach
    fun setUp() {
        val name = "test_${UUID.randomUUID().toString().replace("-", "")}"
        dataSource = h2DataSource("jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE")
        db = DatabaseFactory.init(dataSource as DataSource)
        store = ExposedSessionStore(db)
        SessionManager.useStore(store)
    }

    @AfterEach
    fun tearDown() {
        SessionManager.useStore(null)
        dataSource.close()
    }

    private val jwtConfig: JwtConfig by lazy {
        JwtConfig.fromConfig(
            MapApplicationConfig(
                "auth.jwt.secret" to testSecret,
                "auth.jwt.issuer" to "coroutineviz",
                "auth.jwt.audience" to "coroutineviz-api",
                "auth.jwt.realm" to "coroutineviz",
                "auth.jwt.accessTtlMinutes" to "60",
            ),
        )
    }

    private fun token(
        userId: String,
        role: Role = Role.RUNNER,
    ): String = jwtConfig.sign(userId, role).token

    private fun ApplicationTestBuilder.wsClient() =
        createClient {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json(appJson) }
            install(WebSockets)
        }

    /** Wire the ingest route behind a real jwt provider over the H2 store. */
    private fun ApplicationTestBuilder.installAuthedApp() {
        val cfg = jwtConfig
        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json(appJson)
            }
            install(io.ktor.server.websocket.WebSockets)
            install(io.ktor.server.sse.SSE)
            install(Authentication) {
                jwt("jwt") {
                    realm = cfg.realm
                    verifier(cfg.verifier()!!)
                    validate { cred ->
                        val sub = cred.payload.subject ?: return@validate null
                        val role = Role.fromConfig(cred.payload.getClaim("role").asString())
                        UserPrincipal(userId = sub, role = role)
                    }
                }
            }
            install(RateLimit) {
                register(RateLimitName("api")) {
                    rateLimiter(limit = 10_000, refillPeriod = 1.minutes)
                }
                register(RateLimitName("session-create")) {
                    rateLimiter(limit = 10_000, refillPeriod = 1.minutes)
                }
            }
            routing {
                authenticate("jwt") {
                    registerSessionRoutes()
                    registerIngestRoutes()
                }
            }
        }
    }

    private fun seedOwnedSession(tenant: String): String {
        val session: VizSession = runBlocking { store.createSession("$tenant-work", TenantContext.Scoped(tenant)) }
        return session.sessionId
    }

    private fun syntheticFrame(sessionId: String): String {
        val e: VizEvent =
            CoroutineCreated(
                sessionId = sessionId,
                seq = 1L,
                tsNanos = 0L,
                coroutineId = "c-cross",
                jobId = "j-1",
                parentCoroutineId = null,
                scopeId = "scope-1",
                label = "cross-tenant",
            )
        return appJson.encodeToString(PolymorphicSerializer(VizEvent::class), e)
    }

    @Test
    fun `AUTH-04 — tenant-A JWT cannot ingest into tenant-B session`() =
        testApplication {
            installAuthedApp()
            val client = wsClient()

            val bSessionId = seedOwnedSession("bob")
            val aliceToken = token("alice")

            // alice opens the ingest WS to bob's session id with HER token.
            // resolveScopedSession returns null cross-tenant → close(VIOLATED_POLICY), no write.
            var closeCode: Short? = null
            client.webSocket(
                "/api/sessions/$bSessionId/ingest",
                request = { header("Authorization", "Bearer $aliceToken") },
            ) {
                // Attempt a write anyway; the server must have refused before consuming it.
                runCatching { send(Frame.Text(syntheticFrame(bSessionId))) }
                closeCode = closeReason.await()?.code
            }
            assertEquals(
                CloseReason.Codes.VIOLATED_POLICY.code,
                closeCode,
                "cross-tenant ingest must be closed with VIOLATED_POLICY",
            )

            // bob's session must contain NO event from alice (no cross-tenant write).
            val bobView =
                client.get("/api/sessions/$bSessionId/events") {
                    header("Authorization", "Bearer ${token("bob")}")
                }
            assertEquals(HttpStatusCode.OK, bobView.status)
            val arr = Json.parseToJsonElement(bobView.bodyAsText()).jsonArray
            assertTrue(arr.isEmpty(), "tenant B's session must have no cross-tenant write; got $arr")
            assertFalse(bobView.bodyAsText().contains("c-cross"), "alice's event must not leak into bob's session")
        }

    @Test
    fun `credential enforcement — ingest with no Bearer header is rejected (AUTH-05 D-04a)`() =
        testApplication {
            installAuthedApp()
            val client = wsClient()

            val bSessionId = seedOwnedSession("bob")

            // No Authorization header on the upgrade → the jwt provider rejects it
            // (401 on the WS handshake, never reaching the 101 upgrade). The client
            // surfaces the non-101 status as a thrown handshake/upgrade failure.
            val rejected =
                runCatching {
                    client.webSocket("/api/sessions/$bSessionId/ingest") {
                        send(Frame.Text(syntheticFrame(bSessionId)))
                    }
                }.exceptionOrNull()
            assertTrue(
                rejected != null,
                "an unauthenticated ingest upgrade must be rejected (no Bearer → 401), but the handshake succeeded",
            )

            // bob's session is untouched (the rejected connection wrote nothing).
            val bobView =
                client.get("/api/sessions/$bSessionId/events") {
                    header("Authorization", "Bearer ${token("bob")}")
                }
            val arr = Json.parseToJsonElement(bobView.bodyAsText()).jsonArray
            assertTrue(arr.isEmpty(), "an unauthenticated connection must not write; got $arr")
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
