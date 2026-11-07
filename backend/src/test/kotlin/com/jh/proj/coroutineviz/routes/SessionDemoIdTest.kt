package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.persistence.DatabaseFactory
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.share.ShareService
import com.jh.proj.coroutineviz.share.registerShareOwnerRoutes
import com.jh.proj.coroutineviz.share.registerSharedPublicRoute
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

/** The session records the demo it was created for (spec S-13, plan P-1). */
class SessionDemoIdTest {
    private var dataSource: HikariDataSource? = null

    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
        SessionDemos.clear()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.useStore(null)
        SessionManager.clearAll()
        SessionDemos.clear()
        dataSource?.close()
    }

    private fun database(): Database {
        val name = "test_${UUID.randomUUID().toString().replace("-", "")}"
        val ds =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = "jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
                    driverClassName = "org.h2.Driver"
                    username = "sa"
                    password = ""
                    maximumPoolSize = 4
                    isAutoCommit = false
                },
            ).also { dataSource = it }
        return DatabaseFactory.init(ds as DataSource)
    }

    private suspend fun HttpClient.create(query: String): String {
        val response = post("/api/sessions?$query")
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return appJson
            .parseToJsonElement(response.bodyAsText())
            .jsonObject["sessionId"]!!
            .jsonPrimitive.content
    }

    private suspend fun HttpClient.demoIdOf(id: String): String? {
        val element = getJson("/api/sessions/$id")["demoId"]
        return if (element == null || element == JsonNull) null else element.jsonPrimitive.content
    }

    @Test
    fun `memory mode returns the demo a session was created for`() =
        testApplication {
            application { module() }
            val id = client.create("name=scenario-Nested&demoId=nested")
            assertEquals("nested", client.demoIdOf(id))
        }

    @Test
    fun `a session created without a demo has none`() =
        testApplication {
            application { module() }
            val id = client.create("name=my-app")
            assertNull(client.demoIdOf(id))
        }

    @Test
    fun `an unknown demo is refused and creates nothing`() =
        testApplication {
            application { module() }
            val response = client.post("/api/sessions?name=scenario-x&demoId=no-such-demo")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                "Unknown demo",
                appJson
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject["error"]!!
                    .jsonPrimitive.content,
            )
            assertEquals(0, SessionManager.listSessions().size)
        }

    @Test
    fun `a deleted session's demo is forgotten`() =
        testApplication {
            application { module() }
            val id = client.create("name=scenario-Nested&demoId=nested")
            assertEquals(HttpStatusCode.OK, client.delete("/api/sessions/$id").status)
            assertNull(SessionDemos.of(id))
        }

    @Test
    fun `database mode keeps the demo on the session row, across a restart`() =
        testApplication {
            val db = database()
            SessionManager.useStore(ExposedSessionStore(db))
            application { module() }
            val id = client.create("name=scenario-Parallel&demoId=parallel")
            SessionManager.useStore(ExposedSessionStore(db)) // a new process
            SessionDemos.clear() // nothing in memory: the demo must come from the session row
            assertEquals("parallel", client.demoIdOf(id))
        }

    @Test
    fun `a shared link's payload names the demo`() =
        testApplication {
            val db = database()
            SessionManager.useStore(ExposedSessionStore(db))
            val service = ShareService(db)
            application {
                install(ServerContentNegotiation) { json(appJson) }
                install(io.ktor.server.sse.SSE)
                install(RateLimit) {
                    register(RateLimitName("api")) { rateLimiter(limit = 10_000, refillPeriod = 1.minutes) }
                    register(RateLimitName("session-create")) { rateLimiter(limit = 10_000, refillPeriod = 1.minutes) }
                }
                routing {
                    registerSessionRoutes()
                    registerShareOwnerRoutes(service, publicBaseUrl = "https://app.example.com")
                    registerSharedPublicRoute(service)
                }
            }
            val id = client.create("name=scenario-Mixed&demoId=mixed")
            val share =
                client.post("/api/sessions/$id/share") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"expiresIn":"7d"}""")
                }
            assertEquals(HttpStatusCode.Created, share.status, share.bodyAsText())
            val token =
                appJson
                    .parseToJsonElement(share.bodyAsText())
                    .jsonObject["token"]!!
                    .jsonPrimitive.content
            val payload = client.getJson("/api/shared/$token")
            assertEquals("mixed", payload["session"]!!.jsonObject["demoId"]!!.jsonPrimitive.content)
        }
}
