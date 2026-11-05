package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.health.HealthState
import com.jh.proj.coroutineviz.health.NotEvaluatedReason
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
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Database sessions fold their summaries from the stored events; after a trim the health says it may be incomplete (D-9). */
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

    @Test
    fun `a database session's health comes from its stored events, and says when they were trimmed`() =
        testApplication {
            application { module() }
            val sessionId = client.createSessionNamed("db-app")
            val writer = SessionManager.getSession(sessionId)!!
            writer.ingest(HealthEvents.created(sessionId, "req", at = sec(1)))
            writer.ingest(HealthEvents.created(sessionId, "child", at = sec(1), parent = "req"))
            writer.ingest(HealthEvents.failed(sessionId, "child", at = sec(2), parent = "req"))

            val whole = client.health(sessionId)
            assertEquals(1, whole.failedRoundCount)
            assertEquals(false, whole.retention?.trimmed)
            assertEquals(false, whole.failuresAtLeast)

            DbRetentionPolicy(db = db, maxEventsPerSession = 2).cleanup()
            val trimmed = client.health(sessionId)

            assertEquals(true, trimmed.retention?.trimmed)
            assertEquals(2L, trimmed.retention?.oldestRetainedSeq)
            assertTrue(trimmed.failuresAtLeast)
            assertEquals(NotEvaluatedReason.HISTORY_TRIMMED, trimmed.possiblyBlocked.reason)
            // The child's parent was trimmed: its failure is still counted, as an ungrouped failure.
            assertEquals(1, trimmed.ungroupedFailureCount)
            assertEquals(HealthState.PROBLEMS, trimmed.state)
        }
}
