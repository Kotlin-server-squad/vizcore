package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.health.EvaluationStatus
import com.jh.proj.coroutineviz.health.NotEvaluatedReason
import com.jh.proj.coroutineviz.health.PossiblyBlockedIncident
import com.jh.proj.coroutineviz.health.SessionHealth
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.persistence.DatabaseFactory
import com.jh.proj.coroutineviz.persistence.DbRetentionPolicy
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.routes.HealthEvents.sec
import com.jh.proj.coroutineviz.session.SessionManager
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Database sessions fold their summaries from the stored events on every read. What this process did not watch is not
 * vouched for, and a trimmed history makes the health unavailable until Part 5b persists the summaries (D-9).
 */
class SessionHealthDatabaseModeTest {
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
    }

    @AfterEach
    fun tearDown() {
        SessionManager.useStore(null)
        IngestStates.clear()
        dataSource.close()
    }

    private suspend fun HttpClient.health(sessionId: String): SessionHealth {
        val response = get("/api/sessions/$sessionId/health")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return appJson.decodeFromString(SessionHealth.serializer(), response.bodyAsText())
    }

    /** Events stored by a connection this process never saw: a backend restart in between. */
    private fun storedByAnEarlierProcess(sessionId: String) {
        val writer = SessionManager.getSession(sessionId)!!
        writer.ingest(HealthEvents.created(sessionId, "req", at = sec(1)))
        writer.ingest(HealthEvents.created(sessionId, "child", at = sec(1), parent = "req"))
        writer.ingest(HealthEvents.failed(sessionId, "child", at = sec(2), parent = "req"))
    }

    @Test
    fun `events from a connection this process never watched are not vouched for`() =
        testApplication {
            application { module() }
            val sessionId = client.createSessionNamed("db-restarted")
            storedByAnEarlierProcess(sessionId)

            val health = client.health(sessionId)

            assertEquals(1, health.failedRoundCount)
            assertEquals(false, health.retention?.trimmed)
            assertTrue(health.transport.deliveryUncertain)
            assertEquals(1L, health.transport.sinceSeq)
            assertTrue(health.failuresAtLeast)
            assertEquals(NotEvaluatedReason.EVENTS_DROPPED, health.possiblyBlocked.reason)
        }

    @Test
    fun `a trimmed database session's health is unavailable rather than overcounted`() =
        testApplication {
            application { module() }
            val sessionId = client.createSessionNamed("db-trimmed")
            storedByAnEarlierProcess(sessionId)

            DbRetentionPolicy(db = db, maxEventsPerSession = 2).cleanup()
            val response = client.get("/api/sessions/$sessionId/health")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue(response.bodyAsText().contains("history-trimmed"), response.bodyAsText())
        }

    @Test
    fun `a connected database session's health sees each new watermark`() =
        testApplication {
            application { module() }
            val ws = createClient { install(WebSockets) }
            val sessionId = ws.createSessionNamed("db-live")

            ws.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(HealthEvents.created(sessionId, "worker", at = sec(1)).frameText()))
                send(Frame.Text(HealthEvents.started(sessionId, "worker", at = sec(1)).frameText()))
                send(Frame.Text(HealthEvents.watermark(sessionId, at = sec(5)).frameText()))
                eventually { IngestStates.snapshot(sessionId).watermark?.appNanos == sec(5) }

                val early = ws.health(sessionId)
                assertEquals(EvaluationStatus.EVALUATED, early.possiblyBlocked.status)
                assertEquals(0, early.problemCount)
                assertEquals(sec(5), early.asOfWatermark?.appNanos)

                send(Frame.Text(HealthEvents.watermark(sessionId, at = sec(12)).frameText()))
                eventually { IngestStates.snapshot(sessionId).watermark?.appNanos == sec(12) }

                val later = ws.health(sessionId)
                assertEquals("worker", assertIs<PossiblyBlockedIncident>(later.incidents.items.single()).coroutineId)
                assertEquals(sec(12), later.asOfWatermark?.appNanos)
            }
        }
}
