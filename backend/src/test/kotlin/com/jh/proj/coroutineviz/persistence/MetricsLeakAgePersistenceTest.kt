package com.jh.proj.coroutineviz.persistence

import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.session.MetricsSnapshot
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

        // Boot 1: write a leaking CoroutineCreated, then close the pool.
        val sessionId = writeLeakingSession(url, createdAtEpochMs)

        // Boot 2: NEW pool + NEW store on the SAME file (simulated restart).
        val snap = readMetricsAfterRestart(url, sessionId)

        assertEquals(1, snap.leaks.size, "exactly one leak after restart; was ${snap.leaks}")
        val leak = snap.leaks.single()
        assertEquals("leaker", leak.coroutineId)
        assertEquals("leaky-coroutine", leak.label)
        assertTrue(
            leak.aliveMs in LOWER_BOUND_MS until UPPER_BOUND_MS,
            "aliveMs must be a sane positive wall-clock age (~$AGED_MS ms) across the " +
                "restart boundary; was ${leak.aliveMs}",
        )
    }

    /** Boot 1: create a session and persist a never-terminated, past-dated coroutine. */
    private fun writeLeakingSession(
        url: String,
        createdAtEpochMs: Long,
    ): String {
        val ds = h2DataSource(url)
        try {
            val store = ExposedSessionStore(DatabaseFactory.init(ds as DataSource))
            val session = runBlocking { store.createSession("leak-age") }
            session.send(
                CoroutineCreated(
                    sessionId = session.sessionId,
                    seq = 1,
                    tsNanos = 111,
                    coroutineId = "leaker",
                    jobId = "job-leaker",
                    parentCoroutineId = null,
                    scopeId = "Dispatchers.IO",
                    label = "leaky-coroutine",
                    createdAtEpochMs = createdAtEpochMs,
                ),
            )
            session.send(
                CoroutineStarted(
                    sessionId = session.sessionId,
                    seq = 2,
                    tsNanos = 222,
                    coroutineId = "leaker",
                    jobId = "job-leaker",
                    parentCoroutineId = null,
                    scopeId = "Dispatchers.IO",
                    label = "leaky-coroutine",
                ),
            )
            assertEquals(2, session.store.count())
            return session.sessionId
        } finally {
            ds.close()
        }
    }

    /**
     * Boot 2: rehydrate the session from a SECOND store on the same file and read the
     * metrics with the REAL wall clock the /metrics route uses — NOT a synthetic clock
     * derived from tsNanos. This is what proves CR-01 is fixed.
     */
    private fun readMetricsAfterRestart(
        url: String,
        sessionId: String,
    ): MetricsSnapshot {
        val ds = h2DataSource(url)
        try {
            val store = ExposedSessionStore(DatabaseFactory.init(ds as DataSource))
            val reloaded = store.getSession(sessionId)
            assertNotNull(reloaded, "session must survive restart")
            return reloaded.metricsProjection.snapshot(
                nowEpochMs = System.currentTimeMillis(),
                leakThresholdMs = THRESHOLD_MS,
            )
        } finally {
            ds.close()
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
