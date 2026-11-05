package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.health.RetentionInfo
import com.jh.proj.coroutineviz.health.SummaryTable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The exact cuts health responses used, kept briefly for their follow-up reads (plan E-17). */
class HealthCutsTest {
    private var now = 1_000_000L

    @BeforeEach
    fun setUp() {
        HealthCuts.clear()
        HealthCuts.clock = { now }
    }

    @AfterEach
    fun tearDown() = HealthCuts.clear()

    private fun cut(
        sessionId: String,
        seq: Long,
    ) = HealthCut(
        sessionId = sessionId,
        seq = seq,
        summaries = SummaryTable(),
        ingest = IngestSnapshot(),
        retention = RetentionInfo(null, null, trimmed = false),
        createdAtMs = now,
    )

    @Test
    fun `a kept cut is found by session and seq`() {
        val c = cut("s", 5)
        HealthCuts.remember(c)
        assertSame(c, HealthCuts.find("s", 5))
        assertNull(HealthCuts.find("s", 6))
        assertNull(HealthCuts.find("t", 5))
    }

    @Test
    fun `a session keeps its newest cuts only`() {
        HealthCuts.remember(cut("s", 1))
        HealthCuts.remember(cut("s", 2))
        HealthCuts.remember(cut("s", 3))
        assertNull(HealthCuts.find("s", 1))
        assertNotNull(HealthCuts.find("s", 2))
        assertNotNull(HealthCuts.find("s", 3))
    }

    @Test
    fun `a cut expires`() {
        HealthCuts.remember(cut("s", 1))
        now += HealthCuts.TTL_MS
        assertNotNull(HealthCuts.find("s", 1))
        now += 1
        assertNull(HealthCuts.find("s", 1))
    }

    @Test
    fun `beyond the overall cap the oldest cut goes`() {
        for (i in 0 until HealthCuts.MAX_TOTAL) {
            HealthCuts.remember(cut("s$i", 1))
            now += 1
        }
        HealthCuts.remember(cut("late", 1))
        assertNull(HealthCuts.find("s0", 1))
        assertNotNull(HealthCuts.find("s1", 1))
        assertNotNull(HealthCuts.find("late", 1))
    }

    @Test
    fun `a deleted session's cuts go with it`() {
        HealthCuts.remember(cut("s", 1))
        HealthCuts.forget("s")
        assertNull(HealthCuts.find("s", 1))
    }
}
