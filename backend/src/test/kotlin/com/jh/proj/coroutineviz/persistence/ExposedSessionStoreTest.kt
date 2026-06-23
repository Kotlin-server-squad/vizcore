package com.jh.proj.coroutineviz.persistence

import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.persistence.tables.EventsTable
import com.jh.proj.coroutineviz.persistence.tables.SessionsTable
import com.jh.proj.coroutineviz.persistence.tables.SharesTable
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PERS-01 coverage: Flyway creates the three tables on H2, and
 * [ExposedSessionStore] CRUD works against them.
 */
class ExposedSessionStoreTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var db: Database

    @BeforeEach
    fun setUp() {
        // Unique in-memory DB per test; DB_CLOSE_DELAY keeps it alive across pooled connections.
        val name = "test_${UUID.randomUUID().toString().replace("-", "")}"
        dataSource = h2DataSource("jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE")
        db = DatabaseFactory.init(dataSource as DataSource)
    }

    @AfterEach
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `Flyway creates sessions events and shares tables`() {
        transaction(db) {
            // A trivial selectAll on each table proves the schema exists.
            assertEquals(0, SessionsTable.selectAll().count())
            assertEquals(0, EventsTable.selectAll().count())
            assertEquals(0, SharesTable.selectAll().count())
        }
    }

    @Test
    fun `createSession persists a row that getSession reads back`() {
        val store = ExposedSessionStore(db)
        val session = runBlocking { store.createSession("alpha") }

        assertTrue(session.sessionId.startsWith("alpha-"))
        transaction(db) {
            assertEquals(1, SessionsTable.selectAll().count())
        }

        val loaded = store.getSession(session.sessionId)
        assertNotNull(loaded)
        assertEquals(session.sessionId, loaded.sessionId)

        assertNull(store.getSession("does-not-exist"))
    }

    @Test
    fun `listSessions returns SessionInfo with correct eventCount`() {
        val store = ExposedSessionStore(db)
        val session = runBlocking { store.createSession("beta") }

        session.send(
            CoroutineCreated(
                sessionId = session.sessionId,
                seq = 1,
                tsNanos = 1_000,
                coroutineId = "c1",
                jobId = "j1",
                parentCoroutineId = null,
                scopeId = "s1",
                label = "first",
            ),
        )
        session.send(
            CoroutineCreated(
                sessionId = session.sessionId,
                seq = 2,
                tsNanos = 2_000,
                coroutineId = "c2",
                jobId = "j2",
                parentCoroutineId = null,
                scopeId = "s1",
                label = "second",
            ),
        )

        val infos = store.listSessions()
        assertEquals(1, infos.size)
        val info = infos.first()
        assertEquals(session.sessionId, info.sessionId)
        assertEquals(2, info.eventCount)
        assertEquals(2, info.coroutineCount)
    }

    @Test
    fun `getSession rehydrates the snapshot and hierarchy from persisted events`() {
        // Regression for the DB-mode blank-visualization defect (2026-06-22): a
        // session reconstructed from the DB starts with empty read models, so
        // without rehydration the coroutine tree / threads render empty even
        // though events are persisted.
        val store = ExposedSessionStore(db)
        val session = runBlocking { store.createSession("delta") }
        session.send(
            CoroutineCreated(
                sessionId = session.sessionId,
                seq = 1,
                tsNanos = 1_000,
                coroutineId = "parent",
                jobId = "jp",
                parentCoroutineId = null,
                scopeId = "s1",
                label = "parent",
            ),
        )
        session.send(
            CoroutineCreated(
                sessionId = session.sessionId,
                seq = 2,
                tsNanos = 2_000,
                coroutineId = "child",
                jobId = "jc",
                parentCoroutineId = "parent",
                scopeId = "s1",
                label = "child",
            ),
        )

        // A FRESH instance (the read path) must project the persisted events.
        val loaded = store.getSession(session.sessionId)
        assertNotNull(loaded)
        assertEquals(2, loaded.snapshot.coroutines.size)
        assertTrue(loaded.snapshot.coroutines.containsKey("parent"))
        assertTrue(loaded.snapshot.coroutines.containsKey("child"))
        // The hierarchy projection (threads/tree read model) is rebuilt too.
        assertTrue(loaded.projectionService.getHierarchyTree().isNotEmpty())
    }

    @Test
    fun `appending across two stale session instances keeps seq unique and contiguous (F6)`() {
        // Regression for F6: in DB mode every read rebuilds the VizSession with a
        // fresh in-memory seqGenerator. Two instances of the SAME session id that
        // each start from a stale (empty) watermark would otherwise re-stamp
        // overlapping seqs (1,2 / 1,2) and produce duplicates. The store now owns
        // seq (MAX+1 under a per-session lock), so persisted seqs stay unique.
        val store = ExposedSessionStore(db)
        val id = runBlocking { store.createSession("multi") }.sessionId

        // Build BOTH instances before either appends → both rehydrate from seq 0.
        val a = store.getSession(id)!!
        val b = store.getSession(id)!!

        fun created(n: Int) =
            CoroutineCreated(
                sessionId = id,
                // provisional; the store reassigns the seq
                seq = 0,
                tsNanos = n.toLong() * 1_000,
                coroutineId = "c$n",
                jobId = "j$n",
                parentCoroutineId = null,
                scopeId = "s1",
                label = "c$n",
            )

        // Interleave appends across the two instances.
        a.send(created(1))
        b.send(created(2))
        a.send(created(3))
        b.send(created(4))

        val seqs = ExposedEventStore(db, id).all().map { it.seq }
        assertEquals(4, seqs.size)
        assertEquals(seqs.size, seqs.distinct().size, "expected no duplicate seqs, got $seqs")
        assertEquals(listOf(1L, 2L, 3L, 4L), seqs.sorted(), "expected contiguous seqs")
    }

    @Test
    fun `concurrent appends to one DB session produce no duplicate seqs (F6)`() {
        val store = ExposedSessionStore(db)
        val id = runBlocking { store.createSession("concurrent") }.sessionId

        val threads = 4
        val perThread = 10
        val workers =
            (0 until threads).map { t ->
                Thread {
                    val inst = store.getSession(id)!!
                    repeat(perThread) { i ->
                        val n = t * perThread + i
                        inst.send(
                            CoroutineCreated(
                                sessionId = id,
                                seq = 0,
                                tsNanos = n.toLong() * 1_000,
                                coroutineId = "c$n",
                                jobId = "j$n",
                                parentCoroutineId = null,
                                scopeId = "s1",
                                label = "c$n",
                            ),
                        )
                    }
                }
            }
        workers.forEach { it.start() }
        workers.forEach { it.join() }

        val seqs = ExposedEventStore(db, id).all().map { it.seq }
        assertEquals(threads * perThread, seqs.size)
        assertEquals(seqs.size, seqs.distinct().size, "expected no duplicate seqs under concurrency")
        assertEquals((1L..(threads * perThread).toLong()).toList(), seqs.sorted(), "expected contiguous seqs")
    }

    @Test
    fun `deleteSession removes the row and cascades events`() {
        val store = ExposedSessionStore(db)
        val session = runBlocking { store.createSession("gamma") }
        session.send(
            CoroutineCreated(
                sessionId = session.sessionId,
                seq = 1,
                tsNanos = 1_000,
                coroutineId = "c1",
                jobId = "j1",
                parentCoroutineId = null,
                scopeId = "s1",
                label = "x",
            ),
        )

        assertTrue(store.deleteSession(session.sessionId))
        assertFalse(store.deleteSession(session.sessionId))
        assertNull(store.getSession(session.sessionId))

        transaction(db) {
            // FK cascade removed the event row too.
            assertEquals(0, EventsTable.selectAll().count())
        }
    }

    @Test
    fun `clearAll empties the sessions table`() {
        val store = ExposedSessionStore(db)
        runBlocking {
            store.createSession("one")
            store.createSession("two")
        }
        assertEquals(2, store.listSessions().size)

        store.clearAll()
        assertEquals(0, store.listSessions().size)
        transaction(db) {
            assertEquals(0, SessionsTable.selectAll().count())
        }
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
