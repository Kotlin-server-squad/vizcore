package com.jh.proj.coroutineviz.persistence

import com.jh.proj.coroutineviz.health.SummaryLimits
import com.jh.proj.coroutineviz.routes.HealthEvents
import com.jh.proj.coroutineviz.routes.HealthEvents.sec
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** A database session's summaries honour the configured limits: rebuilt from the events, checkpointed, restored. */
class SummaryLimitsDatabaseTest {
    private val small = SummaryLimits(fullSummaryTarget = 3, trackedCapacity = 3)
    private lateinit var dataSource: HikariDataSource
    private lateinit var db: Database
    private val built = mutableListOf<VizSession>()

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
    }

    @AfterEach
    fun tearDown() {
        SessionManager.useStore(null)
        built.forEach { it.close() }
        dataSource.close()
    }

    private fun ExposedSessionStore.fresh(id: String): VizSession = assertNotNull(getSession(id)).also { built += it }

    /** Six coroutines created one after another (seqs 1..6). */
    private fun ExposedSessionStore.sixCoroutines(): String {
        val id = runBlocking { createSession("limits") }.sessionId
        val writer = fresh(id)
        (1..6).forEach { writer.ingest(HealthEvents.created(id, "c$it", sec(it.toLong()))) }
        return id
    }

    @Test
    fun `a session rebuilt from its stored events uses the store's limits`() {
        val store = ExposedSessionStore(db, summaryLimits = small)
        val id = store.sixCoroutines()
        val session = store.fresh(id)
        assertEquals(small, session.summaryLimits)
        assertEquals(3L, session.readSummaries { it.untrackedCount })
    }

    @Test
    fun `the trim folds its checkpoint with the configured limits, and a restarted store goes on with them`() {
        val store = ExposedSessionStore(db, summaryLimits = small)
        val id = store.sixCoroutines()
        DbRetentionPolicy(db, maxEventsPerSession = 1, summaryLimits = small).cleanup()
        val checkpoint = assertNotNull(transaction(db) { SummaryCheckpoints.read(id) })
        assertEquals(5L, checkpoint.lastEventSeq)
        assertEquals(listOf("c1", "c2", "c3"), checkpoint.summaries.map { it.id })
        assertEquals(2L, checkpoint.untrackedCount, "c4 and c5 were folded beyond capacity")

        // A new process: a new store over the same database.
        val restarted = ExposedSessionStore(db, summaryLimits = small).fresh(id)
        assertEquals(5L, restarted.summarySeedSeq)
        assertEquals(3L, restarted.readSummaries { it.untrackedCount }, "c6, after the checkpoint, too")
    }

    @Test
    fun `defaults unchanged`() {
        val store = ExposedSessionStore(db)
        val id = store.sixCoroutines()
        DbRetentionPolicy(db, maxEventsPerSession = 1).cleanup()
        assertEquals(0L, assertNotNull(transaction(db) { SummaryCheckpoints.read(id) }).untrackedCount)
        assertEquals(6, store.fresh(id).readSummaries { it.all.size })
    }
}
