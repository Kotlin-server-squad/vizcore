package com.jh.proj.coroutineviz.share

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.persistence.DatabaseFactory
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.persistence.tables.EventsTable
import com.jh.proj.coroutineviz.persistence.tables.SharesTable
import com.jh.proj.coroutineviz.routes.HealthCuts
import com.jh.proj.coroutineviz.routes.HealthEvents
import com.jh.proj.coroutineviz.routes.HealthEvents.sec
import com.jh.proj.coroutineviz.routes.IngestStates
import com.jh.proj.coroutineviz.routes.registerSessionHealthRoutes
import com.jh.proj.coroutineviz.routes.registerSessionRoutes
import com.jh.proj.coroutineviz.routes.registerValidationRoutes
import com.jh.proj.coroutineviz.session.SessionManager
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Shares under one cut, and the shared view's health reads (spec C-6; plan E-16, E-17). */
class SharedHealthRoutesTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var db: Database

    @BeforeEach
    fun setUp() {
        val name = "test_${UUID.randomUUID().toString().replace("-", "")}"
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = "jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
                    driverClassName = "org.h2.Driver"
                    username = "sa"
                    password = ""
                    maximumPoolSize = 4
                    isAutoCommit = false
                },
            )
        db = DatabaseFactory.init(dataSource as DataSource)
        SessionManager.useStore(ExposedSessionStore(db))
        IngestStates.clear()
        HealthCuts.clear()
    }

    @AfterEach
    fun tearDown() {
        afterSessionResolvedForTest = null
        SessionManager.useStore(null)
        IngestStates.clear()
        HealthCuts.clear()
        dataSource.close()
    }

    private fun ApplicationTestBuilder.app() {
        val service = ShareService(db)
        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json(appJson) }
            install(io.ktor.server.sse.SSE)
            install(io.ktor.server.plugins.ratelimit.RateLimit) {
                for (bucket in listOf("api", "session-create")) {
                    register(
                        io.ktor.server.plugins.ratelimit
                            .RateLimitName(bucket),
                    ) { rateLimiter(limit = 10_000, refillPeriod = 1.minutes) }
                }
            }
            routing {
                registerSessionRoutes()
                registerSessionHealthRoutes()
                registerValidationRoutes()
                registerShareOwnerRoutes(service, publicBaseUrl = "https://app.example.com")
                registerSharedPublicRoute(service)
            }
        }
    }

    /** A request whose child failed, then the share token. */
    private suspend fun HttpClient.failingShared(): Pair<String, String> {
        val id = runBlocking { SessionManager.createSession("shared-app") }.sessionId
        val s = SessionManager.getSession(id)!!
        s.ingest(HealthEvents.created(id, "req", sec(1)))
        s.ingest(HealthEvents.created(id, "child", sec(1), parent = "req"))
        s.ingest(HealthEvents.failed(id, "child", sec(2), parent = "req"))
        val created =
            post("/api/sessions/$id/share") {
                contentType(ContentType.Application.Json)
                setBody("""{"expiresIn":"7d"}""")
            }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        return id to
            appJson
                .parseToJsonElement(created.bodyAsText())
                .jsonObject["token"]!!
                .jsonPrimitive.content
    }

    private suspend fun HttpClient.payload(token: String): SharedSessionResponse {
        val response = get("/api/shared/$token")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return appJson.decodeFromString(SharedSessionResponse.serializer(), response.bodyAsText())
    }

    private fun accessCount(token: String): Int =
        transaction(
            db,
        ) { SharesTable.select(SharesTable.accessCount).where { SharesTable.token eq token }.first()[SharesTable.accessCount] }

    @Test
    fun `the payload carries the health and the checks of one cut`() =
        testApplication {
            app()
            val (id, token) = client.failingShared()
            val first = client.payload(token)
            val health = assertNotNull(first.health)
            assertEquals(1, health.failedRoundCount)
            assertEquals(first.events.last().seq, health.asOfSeq)
            assertEquals(first.events.size, first.session.eventCount)
            assertNotNull(first.healthCut)
            assertNull(first.checks?.lastRun)
            client.post("/api/validate/session/$id")
            val run = assertNotNull(client.payload(token).checks?.lastRun)
            assertTrue(run.checkedSeq <= health.asOfSeq)
        }

    @Test
    fun `a watermark newer than the resolved session cannot make the loaded health claim more`() =
        testApplication {
            app()
            val id = runBlocking { SessionManager.createSession("shared-worker") }.sessionId
            val s = SessionManager.getSession(id)!!
            val connection = IngestStates.connectionOpened(id, firstSeq = 1)
            s.ingest(HealthEvents.created(id, "worker", sec(1)))
            s.ingest(HealthEvents.started(id, "worker", sec(1)))
            IngestStates.watermarkReceived(connection, HealthEvents.watermark(id, sec(5)), receivedEpochMs = 1)
            val created =
                client.post("/api/sessions/$id/share") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"expiresIn":"7d"}""")
                }
            val token =
                appJson
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["token"]!!
                    .jsonPrimitive.content
            afterSessionResolvedForTest = { sid ->
                // After the session was rebuilt (the worker running): it suspends and a poll 20 s later arrives. Paired
                // with the rebuilt summaries, that watermark would show the worker running for 24 s.
                SessionManager.getSession(sid)!!.ingest(HealthEvents.suspended(sid, "worker", sec(6)))
                IngestStates.watermarkReceived(connection, HealthEvents.watermark(sid, sec(25)), receivedEpochMs = 2)
            }
            val payload = client.payload(token)
            val health = assertNotNull(payload.health)
            assertEquals(0, health.possiblyBlockedCount, "the newer watermark is not paired with the older events")
            assertEquals(health.asOfWatermark?.appNanos, payload.session.lastWatermark?.appNanos)
        }

    @Test
    fun `the shared reads answer like the session reads, bound to the payload's cut, without counting accesses`() =
        testApplication {
            app()
            val (id, token) = client.failingShared()
            val payload = client.payload(token)
            val health = payload.health!!
            val countAfterLoad = accessCount(token)
            val binding = "asOfSeq=${health.asOfSeq}&asOfTransport=${health.transport.revision}&asOfCut=${payload.healthCut}"
            SessionManager.getSession(id)!!.ingest(HealthEvents.created(id, "later", sec(9)))
            for (path in listOf(
                "rounds?$binding",
                "health/items?list=incidents&$binding",
                "hierarchy?$binding",
                "coroutines/child/timeline?$binding",
            )) {
                val shared = client.get("/api/shared/$token/$path")
                assertEquals(HttpStatusCode.OK, shared.status, "$path: ${shared.bodyAsText()}")
                val own = client.get("/api/sessions/$id/$path")
                assertEquals(own.bodyAsText(), shared.bodyAsText(), path)
            }
            assertEquals(HttpStatusCode.OK, client.get("/api/shared/$token/health?atSeq=2").status)
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/shared/$token/health?asOfSeq=2").status)
            assertEquals(countAfterLoad, accessCount(token), "follow-ups are part of the load's access")
            assertEquals(HttpStatusCode.NotFound, client.get("/api/shared/nope/rounds").status)
        }

    @Test
    fun `a session trimmed before checkpoints existed shares without a health, saying why`() =
        testApplication {
            app()
            val (id, token) = client.failingShared()
            transaction(db) { EventsTable.deleteWhere { (EventsTable.sessionId eq id) and (EventsTable.seq eq 1L) } }
            val payload = client.payload(token)
            assertNull(payload.health)
            assertEquals("history-trimmed", payload.healthUnavailable)
        }

    @Test
    fun `the shared hierarchy of a session without a health still answers, like the session's`() =
        testApplication {
            app()
            val (id, token) = client.failingShared()
            transaction(db) { EventsTable.deleteWhere { (EventsTable.sessionId eq id) and (EventsTable.seq eq 1L) } }
            assertEquals(HttpStatusCode.OK, client.get("/api/sessions/$id/hierarchy").status)
            assertEquals(HttpStatusCode.OK, client.get("/api/shared/$token/hierarchy").status)
            val metrics = client.get("/api/sessions/$id/metrics")
            assertTrue(metrics.bodyAsText().contains("\"retainedOnly\":true"), metrics.bodyAsText())
        }

    @Test
    fun `the shared current timeline answers like the session's, even without a health`() =
        testApplication {
            app()
            val (id, token) = client.failingShared()
            transaction(db) { EventsTable.deleteWhere { (EventsTable.sessionId eq id) and (EventsTable.seq eq 1L) } }
            val own = client.get("/api/sessions/$id/coroutines/child/timeline")
            assertEquals(HttpStatusCode.OK, own.status)
            val shared = client.get("/api/shared/$token/coroutines/child/timeline")
            assertEquals(HttpStatusCode.OK, shared.status, shared.bodyAsText())
        }

    @Test
    fun `an event arriving after the session was resolved does not move the loaded health's receipt time`() =
        testApplication {
            app()
            val id = runBlocking { SessionManager.createSession("shared-receipt") }.sessionId
            val connection = IngestStates.connectionOpened(id, firstSeq = 1)
            IngestStates.eventReceived(connection, receivedEpochMs = 1_000)
            SessionManager.getSession(id)!!.ingest(HealthEvents.created(id, "a", sec(1)))
            val created =
                client.post("/api/sessions/$id/share") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"expiresIn":"7d"}""")
                }
            val token =
                appJson
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["token"]!!
                    .jsonPrimitive.content
            afterSessionResolvedForTest = { sid ->
                IngestStates.eventReceived(connection, receivedEpochMs = 9_000)
                SessionManager.getSession(sid)!!.ingest(HealthEvents.created(sid, "b", sec(2)))
            }
            val payload = client.payload(token)
            assertEquals(1_000L, payload.session.lastIngestEpochMs)
            assertEquals(payload.session.lastIngestEpochMs, payload.health?.asOf?.receivedEpochMs)
        }
}
