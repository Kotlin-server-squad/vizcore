package com.jh.proj.coroutineviz.share

import com.jh.proj.coroutineviz.SHARED_RATE_LIMIT_NAME
import com.jh.proj.coroutineviz.persistence.DatabaseFactory
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.session.SessionManager
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

/**
 * SHAR-02 / D-12 coverage: the public shared read is per-IP rate-limited. A low
 * test bucket (3/period) drives a 429 on the 4th request; requests within the
 * bucket return their normal status; the limiter is scoped to the shared route
 * only (a sibling route under no scope is never throttled).
 */
@OptIn(ExperimentalTime::class)
class RateLimitTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var db: Database
    private val testLimit = 3

    @BeforeEach
    fun setUp() {
        val name = "test_${UUID.randomUUID().toString().replace("-", "")}"
        dataSource = h2DataSource("jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE")
        db = DatabaseFactory.init(dataSource as DataSource)
        SessionManager.useStore(ExposedSessionStore(db))
    }

    @AfterEach
    fun tearDown() {
        SessionManager.useStore(null)
        dataSource.close()
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ContentNegotiation) { json() } }

    /** Wire a low-bucket RateLimit scope + the shared route (scoped) and one unscoped sibling. */
    private fun ApplicationTestBuilder.installRateLimitedApp() {
        val service = ShareService(db)
        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json(com.jh.proj.coroutineviz.appJson)
            }
            install(io.ktor.server.sse.SSE)
            install(RateLimit) {
                register(RateLimitName(SHARED_RATE_LIMIT_NAME)) {
                    rateLimiter(limit = testLimit, refillPeriod = 1.minutes)
                    requestKey { call -> call.request.local.remoteHost }
                }
            }
            routing {
                rateLimit(RateLimitName(SHARED_RATE_LIMIT_NAME)) {
                    registerSharedPublicRoute(service)
                }
                // Unscoped sibling — must NOT be throttled by the shared scope.
                get("/api/unrelated") { call.respondText("ok") }
            }
        }
    }

    private fun seedShare(): String {
        val session = runBlocking { SessionManager.createSession("rl") }
        return ShareService(db).create(session.sessionId, "owner", ShareExpiry.SEVEN_DAYS).token
    }

    @Test
    fun `repeated shared reads from the same client exceed the bucket and return 429`() =
        testApplication {
            installRateLimitedApp()
            val client = jsonClient()
            val token = seedShare()

            // First `testLimit` requests succeed (200); the next exceeds the bucket → 429.
            repeat(testLimit) {
                assertEquals(HttpStatusCode.OK, client.get("/api/shared/$token").status)
            }
            assertEquals(HttpStatusCode.TooManyRequests, client.get("/api/shared/$token").status)
        }

    @Test
    fun `requests within the limit return their normal status`() =
        testApplication {
            installRateLimitedApp()
            val client = jsonClient()

            // An unknown token under the limit still resolves normally (404), not 429.
            val resp = client.get("/api/shared/unknown-token")
            assertEquals(HttpStatusCode.NotFound, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertTrue(body["error"]!!.jsonPrimitive.content.contains("not found"))
        }

    @Test
    fun `the limiter is scoped to the shared route only`() =
        testApplication {
            installRateLimitedApp()
            val client = jsonClient()
            val token = seedShare()

            // Exhaust the shared bucket.
            repeat(testLimit + 1) { client.get("/api/shared/$token") }

            // The unrelated route is NOT under the shared scope → still 200.
            assertEquals(HttpStatusCode.OK, client.get("/api/unrelated").status)
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
