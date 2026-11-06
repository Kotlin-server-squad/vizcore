package com.jh.proj.coroutineviz.persistence

import com.jh.proj.coroutineviz.health.SummaryTable
import com.jh.proj.coroutineviz.health.toState
import com.jh.proj.coroutineviz.persistence.tables.EventsTable
import com.jh.proj.coroutineviz.persistence.tables.SessionsTable
import com.jh.proj.coroutineviz.routes.HealthEvents
import com.jh.proj.coroutineviz.routes.HealthEvents.sec
import com.jh.proj.coroutineviz.session.VizSession
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
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

/** The trim folds the events it deletes into the session's summary checkpoint (spec C-4, plan E-12). */
class SummaryCheckpointTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var db: Database
    private lateinit var store: ExposedSessionStore
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
        store = ExposedSessionStore(db)
    }

    @AfterEach
    fun tearDown() {
        built.forEach { it.close() }
        dataSource.close()
    }

    private fun fresh(id: String): VizSession = assertNotNull(store.getSession(id)).also { built += it }

    /** A container app with nine rounds, round 2 failing, and a last coroutine: 30 events (seqs 1..30). */
    private fun history(id: String): List<com.jh.proj.coroutineviz.events.VizEvent> {
        val events = mutableListOf<com.jh.proj.coroutineviz.events.VizEvent>()
        events += HealthEvents.created(id, "app", sec(1))
        events += HealthEvents.started(id, "app", sec(1))
        for (i in 1..9) {
            val r = "r$i"
            events += HealthEvents.created(id, r, sec(1L + i), parent = "app")
            events += HealthEvents.started(id, r, sec(1L + i))
            events +=
                if (i ==
                    2
                ) {
                    HealthEvents.failed(id, r, sec(2L + i), parent = "app")
                } else {
                    HealthEvents.completed(id, r, sec(2L + i), parent = "app")
                }
        }
        events += HealthEvents.created(id, "tail", sec(20))
        return events
    }

    private fun ingest(
        id: String,
        events: List<com.jh.proj.coroutineviz.events.VizEvent>,
    ): SummaryTable {
        val writer = fresh(id)
        events.forEach(writer::ingest)
        // The same events in memory: every ingested event carries its stored seq now.
        return SummaryTable().also { t -> events.forEach(t::apply) }
    }

    private fun checkpointSeq(id: String): Long? =
        transaction(
            db,
        ) { SessionsTable.select(SessionsTable.summariesSeq).where { SessionsTable.id eq id }.first()[SessionsTable.summariesSeq] }

    @Test
    fun `a trim keeps what the deleted events told, and a second trim folds onto the first checkpoint`() {
        val id = runBlocking { store.createSession("cp") }.sessionId
        val memory = ingest(id, history(id))
        DbRetentionPolicy(db, maxEventsPerSession = 10).cleanup()
        assertEquals(20L, checkpointSeq(id), "the newest deleted seq")
        val first = fresh(id)
        assertEquals(memory.toState(), first.readSummaries { it.toState() })
        assertEquals(20L, first.summarySeedSeq)

        val more = (1..15).map { HealthEvents.created(id, "late$it", sec(100L + it)) }
        val memoryAfter =
            SummaryTable.restore(memory.toState()).also { t ->
                ingest(id, more)
                more.forEach(t::apply)
            }
        DbRetentionPolicy(db, maxEventsPerSession = 10).cleanup()
        assertEquals(35L, checkpointSeq(id))
        assertEquals(memoryAfter.toState(), fresh(id).readSummaries { it.toState() })
    }

    @Test
    fun `a session trimmed before checkpoints existed gets none and is still trimmed`() {
        val id = runBlocking { store.createSession("legacy") }.sessionId
        ingest(id, history(id))
        transaction(db) { EventsTable.deleteWhere { (EventsTable.sessionId eq id) and (EventsTable.seq lessEq 4L) } }
        DbRetentionPolicy(db, maxEventsPerSession = 10).cleanup()
        assertNull(checkpointSeq(id))
        assertEquals(10, ExposedEventStore(db, id).count())
    }

    @Test
    fun `a failure after a valid checkpoint clears it instead of hiding the gap`() {
        val id = runBlocking { store.createSession("broken") }.sessionId
        ingest(id, history(id))
        DbRetentionPolicy(db, maxEventsPerSession = 10).cleanup()
        assertEquals(20L, checkpointSeq(id))
        ingest(id, (1..12).map { HealthEvents.created(id, "late$it", sec(100L + it)) })
        // A stored payload that does not decode, inside the range the next trim folds (legacy logs repeat seqs).
        transaction(db) {
            EventsTable.insert {
                it[sessionId] = id
                it[seq] = 21
                it[kind] = "CoroutineCreated"
                it[tsNanos] = 0
                it[payload] = "{}"
            }
        }
        DbRetentionPolicy(db, maxEventsPerSession = 10).cleanup()
        assertNull(checkpointSeq(id))
        assertNull(fresh(id).summarySeedSeq)
    }

    @Test
    fun `a trim between reading the events and the checkpoint is never half seen`() {
        val id = runBlocking { store.createSession("race") }.sessionId
        val events = history(id)
        val memory = ingest(id, events)
        var calls = 0
        store.afterEventsReadForTest = { sid ->
            if (sid == id && calls++ == 0) {
                // More than the 10 kept: the new checkpoint is newer than every event read before it.
                val more = (1..15).map { HealthEvents.created(id, "late$it", sec(100L + it)) }
                val writer = VizSession(id, keepSummaries = false, eventStoreFactory = { ExposedEventStore(db, it) })
                more.forEach(writer::ingest)
                writer.close()
                more.forEach(memory::apply)
                DbRetentionPolicy(db, maxEventsPerSession = 10).cleanup()
            }
        }
        val rebuilt = fresh(id)
        store.afterEventsReadForTest = null
        assertEquals(2, calls, "read again after the trim")
        assertEquals(memory.toState(), rebuilt.readSummaries { it.toState() })
        assertEquals(memory.lastEventSeq, rebuilt.lastSeq)
        val retainedCreations =
            ExposedEventStore(
                db,
                id,
            ).all()
                .filterIsInstance<com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated>()
                .map {
                    it.coroutineId
                }.toSet()
        assertEquals(
            retainedCreations,
            rebuilt.projectionService
                .getHierarchyTree()
                .map { it.id }
                .toSet(),
        )
    }

    @Test
    fun `a trim within the range already read is accepted without reading again`() {
        val id = runBlocking { store.createSession("race-in-range") }.sessionId
        val memory = ingest(id, history(id))
        var calls = 0
        store.afterEventsReadForTest = { sid ->
            if (sid == id && calls++ == 0) DbRetentionPolicy(db, maxEventsPerSession = 10).cleanup()
        }
        val rebuilt = fresh(id)
        store.afterEventsReadForTest = null
        assertEquals(1, calls)
        assertEquals(memory.toState(), rebuilt.readSummaries { it.toState() })
        assertTrue(rebuilt.summarySeedSeq != null)
    }

    @Test
    fun `a read of a rebuilt session uses the events it was rebuilt from, not a store that moved on`() {
        val id = runBlocking { store.createSession("cut-read") }.sessionId
        ingest(id, history(id))
        val rebuilt = fresh(id)
        // Another writer appends past the store's cap and the trim runs: the store moved on.
        ingest(id, (1..15).map { HealthEvents.created(id, "late$it", sec(100L + it)) })
        DbRetentionPolicy(db, maxEventsPerSession = 10).cleanup()
        val view =
            com.jh.proj.coroutineviz.routes.readView(
                rebuilt,
                com.jh.proj.coroutineviz.routes
                    .IngestSnapshot(),
                com.jh.proj.coroutineviz.routes.ViewBinding.Current,
                30_000,
            )
        assertEquals(30L, view.seq)
        assertEquals((1L..30L).toList(), view.events.map { it.seq })
    }
}
