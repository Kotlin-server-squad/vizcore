package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.health.AsOfKind
import com.jh.proj.coroutineviz.health.SessionHealth
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.persistence.DatabaseFactory
import com.jh.proj.coroutineviz.persistence.ExposedIngestStatePersistence
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.persistence.tables.SessionsTable
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
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ingest state and the "ingest open" marker survive a backend restart (spec C-3, C-4; plan E-13). */
class IngestStatePersistenceTest {
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
        IngestStates.persistence = ExposedIngestStatePersistence(db)
        HealthCuts.clear()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.useStore(null)
        IngestStates.clear()
        HealthCuts.clear()
        dataSource.close()
    }

    /** A new process on the same database: nothing in memory, the startup sweep, then persistence. */
    private fun restart() {
        IngestStates.clear()
        HealthCuts.clear()
        SessionManager.useStore(ExposedSessionStore(db))
        ExposedIngestStatePersistence(db).also {
            it.sweepOpenMarkers()
            IngestStates.persistence = it
        }
    }

    private fun marker(id: String): Long? =
        transaction(db) {
            SessionsTable.select(SessionsTable.ingestOpenSince).where { SessionsTable.id eq id }.first()[SessionsTable.ingestOpenSince]
        }

    private suspend fun HttpClient.health(id: String): SessionHealth {
        val response = get("/api/sessions/$id/health")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return appJson.decodeFromString(SessionHealth.serializer(), response.bodyAsText())
    }

    private suspend fun HttpClient.streamAndClose(
        id: String,
        final: Boolean,
    ) {
        webSocket("/api/sessions/$id/ingest") {
            send(Frame.Text(HealthEvents.created(id, "worker", sec(1)).frameText()))
            send(Frame.Text(HealthEvents.started(id, "worker", sec(1)).frameText()))
            send(Frame.Text(HealthEvents.watermark(id, sec(12), final = final).frameText()))
        }
        // The server handles the frames and the close after the client is done: wait for both.
        eventually { SessionManager.getSession(id)!!.store.count() == 2 }
        eventually { !IngestConnections.isConnected(id) && marker(id) == null }
    }

    @Test
    fun `a clean close is still clean after a restart, with its watermark`() =
        testApplication {
            application { module() }
            val ws = createClient { install(WebSockets) }
            val id = ws.createSessionNamed("db-clean")
            ws.streamAndClose(id, final = true)
            restart()
            val health = ws.health(id)
            assertFalse(health.transport.deliveryUncertain, "5a's D-9b read this as uncertain")
            assertEquals(AsOfKind.WATERMARK, health.asOf?.kind)
            assertEquals(sec(12), IngestStates.snapshot(id).watermark?.appNanos)
            assertNull(marker(id))
        }

    @Test
    fun `an unclean close stays uncertain after a restart`() =
        testApplication {
            application { module() }
            val ws = createClient { install(WebSockets) }
            val id = ws.createSessionNamed("db-unclean")
            ws.streamAndClose(id, final = false)
            restart()
            val health = ws.health(id)
            assertTrue(health.transport.deliveryUncertain)
            assertEquals(1L, health.transport.sinceSeq)
        }

    @Test
    fun `a connection open when the process stopped makes the session uncertain, once`() {
        val id = kotlinx.coroutines.runBlocking { SessionManager.createSession("db-crash") }.sessionId
        val connection = IngestStates.connectionOpened(id, firstSeq = 3)
        IngestStates.watermarkReceived(connection, HealthEvents.watermark(id, sec(5), lost = 3), receivedEpochMs = 1)
        assertEquals(3L, marker(id))
        val revisionBefore = IngestStates.snapshot(id).transport.revision
        // No close: the process "crashed".
        restart()
        val transport = IngestStates.snapshot(id).transport
        assertTrue(transport.deliveryUncertain)
        assertEquals(3L, transport.sinceSeq)
        assertEquals(3L, transport.lostEvents, "a loss reported before the restart is still counted")
        assertTrue(transport.revision > revisionBefore)
        assertNull(marker(id))
    }

    @Test
    fun `a row from before 5b-2 keeps 5a's rule, a new clean row reads clean after a restart`() =
        testApplication {
            application { module() }
            val legacy = client.createSessionNamed("db-legacy")
            val fresh = client.createSessionNamed("db-fresh")
            transaction(db) {
                SessionsTable.update({ SessionsTable.id eq legacy }) {
                    it[ingestState] = null
                    it[createdAt] = kotlin.time.Instant.fromEpochMilliseconds(0)
                }
                SessionsTable.update({ SessionsTable.id eq fresh }) { it[createdAt] = kotlin.time.Instant.fromEpochMilliseconds(0) }
            }
            restart()
            assertTrue(client.health(legacy).transport.deliveryUncertain)
            assertFalse(client.health(fresh).transport.deliveryUncertain)
        }

    /** Records every write; can fail the n-th. */
    private class FakePersistence(
        var failOn: (Int) -> Boolean = { false },
    ) : IngestStatePersistence {
        val writes = mutableListOf<Pair<PersistedIngestState, Long?>>()

        override fun load(sessionId: String): PersistedIngestState? = null

        override fun save(
            sessionId: String,
            state: PersistedIngestState,
            openSince: Long?,
        ) {
            if (failOn(writes.size + 1)) error("database down")
            writes += state to openSince
        }
    }

    @Test
    fun `writes are throttled while frames arrive`() {
        val fake = FakePersistence()
        IngestStates.persistence = fake
        var now = 1_000_000L
        IngestStates.clock = { now }
        val c = IngestStates.connectionOpened("s", firstSeq = 1)
        repeat(10) {
            now += 100
            IngestStates.watermarkReceived(c, HealthEvents.watermark("s", sec(it.toLong())), receivedEpochMs = now)
        }
        assertEquals(1, fake.writes.size, "only the open within 1 s")
        now += 5_000
        IngestStates.eventReceived(c, receivedEpochMs = now)
        assertEquals(2, fake.writes.size)
    }

    @Test
    fun `the marker covers every open connection and clears with the last one, revisions never go back`() {
        val fake = FakePersistence()
        IngestStates.persistence = fake
        val a = IngestStates.connectionOpened("s", firstSeq = 1)
        val b = IngestStates.connectionOpened("s", firstSeq = 7)
        IngestStates.frameSkipped(b)
        IngestStates.connectionClosed(a)
        assertEquals(7L, fake.writes.last().second, "b still open")
        IngestStates.connectionClosed(b)
        assertNull(fake.writes.last().second)
        val revisions = fake.writes.map { it.first.transport.revision }
        assertEquals(revisions.sorted(), revisions)
        assertEquals(
            IngestStates.snapshot("s").transport,
            fake.writes
                .last()
                .first.transport,
        )
    }

    @Test
    fun `a socket whose open cannot be stored is refused before any event, leaving nothing registered`() =
        testApplication {
            application { module() }
            val ws = createClient { install(WebSockets) }
            val id = ws.createSessionNamed("db-refused")
            val listeners = SessionManager.onSessionClosedListenerCount()
            IngestStates.persistence = FakePersistence(failOn = { it == 1 })
            var reason: CloseReason? = null
            ws.webSocket("/api/sessions/$id/ingest") {
                runCatching { send(Frame.Text(HealthEvents.created(id, "a", sec(1)).frameText())) }
                reason = withTimeout(5_000) { closeReason.await() }
            }
            assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, reason?.code)
            eventually { !IngestConnections.isConnected(id) }
            assertEquals(0, SessionManager.getSession(id)!!.store.count(), "no event stored")
            eventually { SessionManager.onSessionClosedListenerCount() == listeners }
            IngestStates.persistence = FakePersistence()
            ws.webSocket("/api/sessions/$id/ingest") { send(Frame.Text(HealthEvents.created(id, "a", sec(1)).frameText())) }
            eventually { SessionManager.getSession(id)!!.store.count() == 1 }
        }

    @Test
    fun `a close whose write fails leaves the marker, so the session reads uncertain after a restart`() {
        val id = kotlinx.coroutines.runBlocking { SessionManager.createSession("db-close-fails") }.sessionId
        val real = ExposedIngestStatePersistence(db)
        val c = IngestStates.connectionOpened(id, firstSeq = 1)
        IngestStates.watermarkReceived(c, HealthEvents.watermark(id, sec(5), final = true), receivedEpochMs = 1)
        IngestStates.persistence =
            object : IngestStatePersistence by real {
                override fun save(
                    sessionId: String,
                    state: PersistedIngestState,
                    openSince: Long?,
                ) = error("database down")
            }
        IngestStates.connectionClosed(c)
        assertEquals(1L, marker(id))
        restart()
        assertTrue(IngestStates.snapshot(id).transport.deliveryUncertain)
    }

    @Test
    fun `a write held up on one thread keeps the next change waiting, so stored revisions never go back`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val fake = FakePersistence()
        var holdNext = false
        IngestStates.persistence =
            object : IngestStatePersistence by fake {
                override fun save(
                    sessionId: String,
                    state: PersistedIngestState,
                    openSince: Long?,
                ) {
                    if (holdNext) {
                        holdNext = false
                        entered.countDown()
                        release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    fake.save(sessionId, state, openSince)
                }
            }
        val a = IngestStates.connectionOpened("s", firstSeq = 1)
        val b = IngestStates.connectionOpened("s", firstSeq = 5)
        holdNext = true
        val first = kotlin.concurrent.thread { IngestStates.watermarkReceived(a, HealthEvents.watermark("s", sec(1), lost = 2), 1) }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val second = kotlin.concurrent.thread { IngestStates.frameSkipped(b) }
        Thread.sleep(100)
        assertTrue(second.isAlive, "the second change waits for the first write")
        release.countDown()
        first.join(5_000)
        second.join(5_000)
        val revisions = fake.writes.map { it.first.transport.revision }
        assertEquals(revisions.sorted(), revisions)
        assertEquals(IngestStates.snapshot("s").transport.revision, revisions.last())
        IngestStates.connectionClosed(a)
        assertEquals(5L, fake.writes.last().second, "b is still open")
    }
}
