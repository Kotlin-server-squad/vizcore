@file:Suppress("DEPRECATION")

package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.auth.JwtConfig
import com.jh.proj.coroutineviz.auth.Role
import com.jh.proj.coroutineviz.auth.TenantContext
import com.jh.proj.coroutineviz.auth.UserPrincipal
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.persistence.DatabaseFactory
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
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
import kotlin.time.ExperimentalTime

/**
 * RCO-07 coverage for `GET /api/sessions/{id}/metrics` (T-08-04/T-08-05). Wires the
 * REAL session routes behind a REAL JWT block against an H2-backed
 * [ExposedSessionStore] so tenant scoping resolves exactly as production does
 * (mirrors [com.jh.proj.coroutineviz.auth.TenantIsolationE2ETest]).
 *
 * The route rebuilds the metrics projection from the store on every read
 * (VizSession.rehydrateFromStore → metricsProjection.rebuildFrom), so a session
 * seeded with live events returns a non-empty snapshot.
 */
@OptIn(ExperimentalTime::class)
@Suppress("TooManyFunctions") // test fixture helpers (jwt/client/seed/h2) + 3 cases exceed the default threshold
class MetricsRouteTest {
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

    private fun token(
        userId: String,
        role: Role = Role.RUNNER,
    ): String = jwtConfig().sign(userId, role).token

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ContentNegotiation) { json() } }

    private fun ApplicationTestBuilder.installAuthedApp() {
        val jwtConfig = jwtConfig()
        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json(appJson)
            }
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

    /** Seed a session owned by [tenant] with two active coroutines on different dispatchers. */
    private fun seedActiveSession(tenant: String): String {
        val session: VizSession = runBlocking { store.createSession("$tenant-work", TenantContext.Scoped(tenant)) }
        var seq = 0L
        listOf("Dispatchers.IO" to "c-io", "Dispatchers.Default" to "c-def").forEach { (disp, id) ->
            session.send(
                CoroutineCreated(
                    sessionId = session.sessionId,
                    seq = ++seq,
                    tsNanos = 1_000,
                    coroutineId = id,
                    jobId = "j-$id",
                    parentCoroutineId = null,
                    scopeId = disp,
                    label = "task-$id",
                ),
            )
            session.send(
                CoroutineStarted(
                    sessionId = session.sessionId,
                    seq = ++seq,
                    tsNanos = 2_000,
                    coroutineId = id,
                    jobId = "j-$id",
                    parentCoroutineId = null,
                    scopeId = disp,
                    label = "task-$id",
                ),
            )
        }
        return session.sessionId
    }

    @Test
    fun `owner gets 200 with the metrics shape for an owned session`() =
        testApplication {
            installAuthedApp()
            val client = jsonClient()
            val sid = seedActiveSession("alice")

            val resp = client.get("/api/sessions/$sid/metrics") { header("Authorization", "Bearer ${token("alice")}") }
            assertEquals(HttpStatusCode.OK, resp.status)

            val body = appJson.decodeFromString(MetricsResponse.serializer(), resp.bodyAsText())
            assertEquals(2, body.active, "two active coroutines seeded")
            assertTrue(body.peak >= 2, "peak high-water mark >= 2")
            assertEquals(1, body.dispatcherUtilization["Dispatchers.IO"], "one active on IO")
            assertEquals(1, body.dispatcherUtilization["Dispatchers.Default"], "one active on Default")
            assertEquals(DEFAULT_LEAK_MS, body.leakThresholdMs, "default threshold echoed when absent")
        }

    @Test
    fun `cross-tenant session id returns 404 not 403 and does not leak existence`() =
        testApplication {
            installAuthedApp()
            val client = jsonClient()
            val sid = seedActiveSession("alice")

            val resp = client.get("/api/sessions/$sid/metrics") { header("Authorization", "Bearer ${token("bob")}") }
            assertEquals(HttpStatusCode.NotFound, resp.status, "cross-tenant must be 404 (not 403)")
            assertFalse(resp.bodyAsText().contains("Dispatchers.IO"), "404 body must not leak alice's metrics")
        }

    @Test
    fun `leakThresholdMs override is echoed and a negative value is clamped to the floor`() =
        testApplication {
            installAuthedApp()
            val client = jsonClient()
            val sid = seedActiveSession("alice")
            val alice = token("alice")

            // Explicit override is honored.
            val overridden =
                client
                    .get("/api/sessions/$sid/metrics?leakThresholdMs=5000") { header("Authorization", "Bearer $alice") }
            val ov = appJson.decodeFromString(MetricsResponse.serializer(), overridden.bodyAsText())
            assertEquals(5_000L, ov.leakThresholdMs, "explicit threshold must be honored")

            // A negative value is clamped to the sane floor (V5), never echoed verbatim.
            val negative =
                client
                    .get("/api/sessions/$sid/metrics?leakThresholdMs=-99999") { header("Authorization", "Bearer $alice") }
            val ng = appJson.decodeFromString(MetricsResponse.serializer(), negative.bodyAsText())
            assertTrue(ng.leakThresholdMs >= MIN_LEAK_MS, "negative threshold must be clamped to >= MIN_LEAK_MS; was ${ng.leakThresholdMs}")

            // An absurdly large value is clamped to the ceiling.
            val huge =
                client
                    .get("/api/sessions/$sid/metrics?leakThresholdMs=999999999999") { header("Authorization", "Bearer $alice") }
            val hg = appJson.decodeFromString(MetricsResponse.serializer(), huge.bodyAsText())
            assertTrue(hg.leakThresholdMs <= MAX_LEAK_MS, "huge threshold must be clamped to <= MAX_LEAK_MS; was ${hg.leakThresholdMs}")
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
