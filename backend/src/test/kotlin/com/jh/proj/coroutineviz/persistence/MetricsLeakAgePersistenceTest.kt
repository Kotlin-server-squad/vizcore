package com.jh.proj.coroutineviz.persistence

import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CR-01 regression (RCO-07 SC#2): leak age must survive a real persistence boundary.
 *
 * The /metrics route serves the DB-rehydrated path: a session created by a PRIOR
 * backend process is reconstructed by the CURRENT process through a second
 * [ExposedSessionStore], and its [MetricsProjection] is rebuilt from the persisted
 * events. Before this fix the projection subtracted two [System.nanoTime] values
 * captured in DIFFERENT JVM lifetimes (`nowNanos - createdAtNanos`), which has no
 * fixed origin across processes → garbage/undefined `aliveMs` and false-or-missed
 * leak alerts.
 *
 * This test crosses that exact boundary — two stores over the SAME H2 FILE db
 * (simulating a restart) — and reads with the REAL wall clock the route uses
 * ([System.currentTimeMillis]), NOT a synthetic clock matched to the event's
 * tsNanos. It asserts a sane POSITIVE leak age ≈ the real elapsed wall time.
 *
 * It mirrors [PersistenceRestartTest]'s harness (h2DataSource + DatabaseFactory.init
 * + two ExposedSessionStore instances + @TempDir + rebind).
 */
class MetricsLeakAgePersistenceTest {
    @Test
    fun `leak age is sane and positive after a simulated restart on the DB-rehydrated path`(
        @TempDir tempDir: File,
    ) {
        val dbFile = File(tempDir, "leak-age").absolutePath
        val url = "jdbc:h2:file:$dbFile;CASE_INSENSITIVE_IDENTIFIERS=TRUE"

        // The coroutine was created ~60s in the past, in wall-clock time, and never
        // terminated — so after the restart it must be flagged as a leak whose age is
        // ~60s, NOT negative and NOT absurd.
        val createdAtEpochMs = System.currentTimeMillis() - AGED_MS

        // --- First "boot": create a session + write a leaking CoroutineCreated, then close. ---
        val sessionId: String
        val ds1 = h2DataSource(url)
        try {
            val db1 = DatabaseFactory.init(ds1 as DataSource)
            val store1 = ExposedSessionStore(db1)
            val session = runBlocking { store1.createSession("leak-age") }
            sessionId = session.sessionId

            val created =
                CoroutineCreated(
                    sessionId = sessionId,
                    seq = 1,
                    tsNanos = 111,
                    coroutineId = "leaker",
                    jobId = "job-leaker",
                    parentCoroutineId = null,
                    scopeId = "Dispatchers.IO",
                    label = "leaky-coroutine",
                    createdAtEpochMs = createdAtEpochMs,
                )
            val startedEvent =
                CoroutineStarted(
                    sessionId = sessionId,
                    seq = 2,
                    tsNanos = 222,
                    coroutineId = "leaker",
                    jobId = "job-leaker",
                    parentCoroutineId = null,
                    scopeId = "Dispatchers.IO",
                    label = "leaky-coroutine",
                )
            session.send(created)
            session.send(startedEvent)
            assertEquals(2, session.store.count())
        } finally {
            ds1.close()
        }

        // --- Second "boot": NEW pool + NEW store on the SAME file (simulated restart). ---
        val ds2 = h2DataSource(url)
        try {
            val db2 = DatabaseFactory.init(ds2 as DataSource)
            val store2 = ExposedSessionStore(db2)

            val reloaded = store2.getSession(sessionId)
            assertNotNull(reloaded, "session must survive restart")
            // The metrics projection is rebuilt from the persisted events on rehydrate.

            // Read with the REAL wall clock, exactly as the /metrics route does — NOT a
            // synthetic clock derived from tsNanos. This is what proves CR-01 is fixed.
            val snap =
                reloaded.metricsProjection.snapshot(
                    nowEpochMs = System.currentTimeMillis(),
                    leakThresholdMs = THRESHOLD_MS,
                )

            assertEquals(1, snap.leaks.size, "exactly one leak after restart; was ${snap.leaks}")
            val leak = snap.leaks.single()
            assertEquals("leaker", leak.coroutineId)
            assertEquals("leaky-coroutine", leak.label)
            assertTrue(
                leak.aliveMs in LOWER_BOUND_MS until UPPER_BOUND_MS,
                "aliveMs must be a sane positive wall-clock age (~$AGED_MS ms) across the " +
                    "restart boundary; was ${leak.aliveMs}",
            )
        } finally {
            ds2.close()
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

    private companion object {
        private const val AGED_MS = 60_000L
        private const val THRESHOLD_MS = 30_000L
        private const val LOWER_BOUND_MS = 55_000L
        private const val UPPER_BOUND_MS = 120_000L
    }
}
