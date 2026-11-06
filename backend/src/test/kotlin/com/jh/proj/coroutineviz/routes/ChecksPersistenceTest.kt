package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.persistence.DatabaseFactory
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.routes.HealthEvents.completed
import com.jh.proj.coroutineviz.routes.HealthEvents.created
import com.jh.proj.coroutineviz.routes.HealthEvents.sec
import com.jh.proj.coroutineviz.routes.HealthEvents.started
import com.jh.proj.coroutineviz.session.SessionManager
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The last checks run is kept and served per session (spec C-5, T-8; plan E-14). */
class ChecksPersistenceTest {
    private var dataSource: HikariDataSource? = null

    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
        ChecksResults.clear()
        IngestStates.clear()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.useStore(null)
        SessionManager.clearAll()
        ChecksResults.clear()
        IngestStates.clear()
        dataSource?.close()
    }

    private suspend fun HttpClient.status(id: String): ChecksStatus {
        val response = get("/api/validate/session/$id")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return appJson.decodeFromString(ChecksStatus.serializer(), response.bodyAsText())
    }

    private fun send(
        id: String,
        vararg events: com.jh.proj.coroutineviz.events.VizEvent,
    ) = SessionManager.getSession(id)!!.let { s -> events.forEach(s::ingest) }

    @Test
    fun `memory mode keeps the last run until the session is deleted`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("checks-mem")
            assertEquals(ChecksStatus(id, null, null), client.status(id))
            send(id, created(id, "a", sec(1)), started(id, "a", sec(1)), completed(id, "a", sec(2)))
            assertEquals(HttpStatusCode.OK, client.post("/api/validate/session/$id").status)
            val run = assertNotNull(client.status(id).lastRun)
            assertEquals(SessionManager.getSession(id)!!.lastSeq, run.checkedSeq)
            assertTrue(run.rules.isNotEmpty())
            assertEquals(run.rules.count { !it.passed }, run.failedRuleCount)
            send(id, created(id, "b", sec(3)), started(id, "b", sec(3)), completed(id, "b", sec(4)))
            assertEquals(3L, client.status(id).newEventsSince)
            client.delete("/api/sessions/$id")
            assertNull(ChecksResults.load(id))
            assertEquals(HttpStatusCode.NotFound, client.get("/api/validate/session/$id").status)
        }

    @Test
    fun `database mode keeps the last run on the session row, across a restart`() =
        testApplication {
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
            val db = DatabaseFactory.init(ds as DataSource)
            SessionManager.useStore(ExposedSessionStore(db))
            application { module() }
            val id = client.createSessionNamed("checks-db")
            send(id, created(id, "a", sec(1)), started(id, "a", sec(1)))
            client.post("/api/validate/session/$id")
            val before = assertNotNull(client.status(id).lastRun)
            SessionManager.useStore(ExposedSessionStore(db)) // a new process
            ChecksResults.clear() // nothing in memory: the run must come from the session row
            assertEquals(before, client.status(id).lastRun)
            val stored =
                org.jetbrains.exposed.v1.jdbc.transactions.transaction(db) {
                    com.jh.proj.coroutineviz.persistence.tables.SessionsTable
                        .select(com.jh.proj.coroutineviz.persistence.tables.SessionsTable.checks)
                        .where { com.jh.proj.coroutineviz.persistence.tables.SessionsTable.id eq id }
                        .first()[com.jh.proj.coroutineviz.persistence.tables.SessionsTable.checks]
                }
            assertTrue(stored.orEmpty().contains("\"checkedSeq\""), stored)
        }
}
