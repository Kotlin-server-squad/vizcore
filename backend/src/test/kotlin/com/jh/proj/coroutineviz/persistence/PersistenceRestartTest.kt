package com.jh.proj.coroutineviz.persistence

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.PolymorphicSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PERS-02 coverage: sessions + events written through one store instance are
 * read back identically by a SECOND store instance opened against the same H2
 * FILE database (simulating a backend restart). Also asserts the DB payload
 * round-trips through the SSE PolymorphicSerializer byte-for-byte (Pattern 3).
 */
class PersistenceRestartTest {
    @Test
    fun `sessions and events survive a simulated restart`(
        @TempDir tempDir: File,
    ) {
        val dbFile = File(tempDir, "restart").absolutePath
        val url = "jdbc:h2:file:$dbFile;CASE_INSENSITIVE_IDENTIFIERS=TRUE"

        val original =
            listOf(
                CoroutineCreated(
                    sessionId = "will-be-replaced",
                    seq = 1,
                    tsNanos = 111,
                    coroutineId = "c1",
                    jobId = "j1",
                    parentCoroutineId = null,
                    scopeId = "s1",
                    label = "created",
                ),
                CoroutineStarted(
                    sessionId = "will-be-replaced",
                    seq = 2,
                    tsNanos = 222,
                    coroutineId = "c1",
                    jobId = "j1",
                    parentCoroutineId = null,
                    scopeId = "s1",
                    label = "started",
                ),
            )

        // --- First "boot": create + write, then close the pool ---
        val sessionId: String
        val ds1 = h2DataSource(url)
        try {
            val db1 = DatabaseFactory.init(ds1 as DataSource)
            val store1 = ExposedSessionStore(db1)
            val session = runBlocking { store1.createSession("restart") }
            sessionId = session.sessionId
            original.forEach { session.send(rebind(it, sessionId)) }
            assertEquals(2, session.store.count())
        } finally {
            ds1.close()
        }

        // --- Second "boot": NEW pool + store on the SAME file ---
        val ds2 = h2DataSource(url)
        try {
            val db2 = DatabaseFactory.init(ds2 as DataSource)
            val store2 = ExposedSessionStore(db2)

            val reloaded = store2.getSession(sessionId)
            assertNotNull(reloaded, "session must survive restart")
            assertEquals(sessionId, reloaded.sessionId)

            val events = reloaded.store.all()
            assertEquals(2, events.size, "all events must survive restart")
            assertEquals(listOf("CoroutineCreated", "CoroutineStarted"), events.map { it.kind })
            assertEquals(listOf(1L, 2L), events.map { it.seq })

            val infos = store2.listSessions()
            assertEquals(1, infos.size)
            assertEquals(2, infos.first().eventCount)
        } finally {
            ds2.close()
        }
    }

    @Test
    fun `round-tripped DB event is PolymorphicSerializer-compatible with the wire shape`(
        @TempDir tempDir: File,
    ) {
        val dbFile = File(tempDir, "wire").absolutePath
        val url = "jdbc:h2:file:$dbFile;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        val serializer = PolymorphicSerializer(VizEvent::class)

        val ds = h2DataSource(url)
        try {
            val db = DatabaseFactory.init(ds as DataSource)
            val store = ExposedSessionStore(db)
            val session = runBlocking { store.createSession("wire") }

            val event =
                CoroutineCreated(
                    sessionId = session.sessionId,
                    seq = 1,
                    tsNanos = 999,
                    coroutineId = "cX",
                    jobId = "jX",
                    parentCoroutineId = "p0",
                    scopeId = "sX",
                    label = "wire-test",
                )
            session.send(event)

            val loaded = session.store.all().single()
            // The deserialized event must equal the original (same data class).
            assertEquals(event, loaded)
            // And its re-encoded JSON must be byte-identical to the original's
            // (the SSE /events path uses this exact serializer).
            val originalJson = appJson.encodeToString(serializer, event as VizEvent)
            val loadedJson = appJson.encodeToString(serializer, loaded)
            assertEquals(originalJson, loadedJson)
            assertTrue(originalJson.contains("\"type\":\"CoroutineCreated\""))
        } finally {
            ds.close()
        }
    }

    /** Copy the event with the real session id (events carry their sessionId). */
    private fun rebind(
        event: VizEvent,
        sessionId: String,
    ): VizEvent =
        when (event) {
            is CoroutineCreated -> event.copy(sessionId = sessionId)
            is CoroutineStarted -> event.copy(sessionId = sessionId)
            else -> event
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
