package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.health.RetentionInfo
import com.jh.proj.coroutineviz.health.SummaryTable
import com.jh.proj.coroutineviz.health.TransportStatus
import com.jh.proj.coroutineviz.health.WatermarkObservation
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
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
        watermarkNanos: Long? = null,
        revision: Long = 0,
    ) = HealthCut(
        sessionId = sessionId,
        seq = seq,
        summaries = SummaryTable(),
        ingest =
            IngestSnapshot(
                watermark = watermarkNanos?.let { WatermarkObservation(it, 1, 1) },
                transport = TransportStatus(revision = revision),
            ),
        retention = RetentionInfo(null, null, trimmed = false),
        transitions = emptyMap(),
    )

    private fun find(
        sessionId: String,
        seq: Long,
        watermarkNanos: Long? = null,
        revision: Long? = null,
    ) = HealthCuts.find(sessionId, seq, watermarkNanos, revision)

    @Test
    fun `a kept cut is found by session and seq`() {
        val c = cut("s", 5)
        HealthCuts.remember(c)
        assertSame(c, find("s", 5))
        assertNull(find("s", 6))
        assertNull(find("t", 5))
    }

    @Test
    fun `a session keeps its most recently used cuts`() {
        for (seq in 1L..HealthCuts.PER_SESSION) {
            HealthCuts.remember(cut("s", seq))
            now += 1
        }
        assertNotNull(find("s", 1)) // used: now the most recent
        now += 1
        HealthCuts.remember(cut("s", 100))
        assertNotNull(find("s", 1))
        assertNull(find("s", 2), "the least recently used goes")
    }

    @Test
    fun `a cut is identified by its seq, watermark and transport revision`() {
        HealthCuts.remember(cut("s", 5, watermarkNanos = 10))
        HealthCuts.remember(cut("s", 5, watermarkNanos = 20))
        HealthCuts.remember(cut("s", 5, watermarkNanos = 20, revision = 1))
        assertEquals(10L, find("s", 5, watermarkNanos = 10)?.ingest?.watermark?.appNanos)
        assertEquals(0L, find("s", 5, watermarkNanos = 20, revision = 0)?.ingest?.transport?.revision)
        assertEquals(1L, find("s", 5, watermarkNanos = 20)?.ingest?.transport?.revision, "the newest when no revision is named")
        assertNull(find("s", 5), "no watermark is a binding of its own")
        assertNull(find("s", 5, watermarkNanos = 20, revision = 2))
    }

    @Test
    fun `remembering a cut equal to a kept one refreshes it instead of adding one`() {
        HealthCuts.remember(cut("s", 5))
        now += HealthCuts.TTL_MS
        HealthCuts.remember(cut("s", 5))
        now += 1
        assertNotNull(find("s", 5))
        for (seq in 1L until HealthCuts.PER_SESSION) HealthCuts.remember(cut("s", 100 + seq))
        assertNotNull(find("s", 5), "the equal cut took one place")
    }

    @Test
    fun `a hit refreshes the cut's lifetime`() {
        HealthCuts.remember(cut("s", 1))
        now += HealthCuts.TTL_MS - 1
        assertNotNull(find("s", 1))
        now += HealthCuts.TTL_MS - 1
        assertNotNull(find("s", 1))
    }

    @Test
    fun `a cut expires`() {
        HealthCuts.remember(cut("s", 1))
        now += HealthCuts.TTL_MS + 1
        assertNull(find("s", 1))
    }

    @Test
    fun `beyond the overall cap the oldest cut goes`() {
        for (i in 0 until HealthCuts.MAX_TOTAL) {
            HealthCuts.remember(cut("s$i", 1))
            now += 1
        }
        HealthCuts.remember(cut("late", 1))
        assertNull(find("s0", 1))
        assertNotNull(find("s1", 1))
        assertNotNull(find("late", 1))
    }

    @Test
    fun `a deleted session's cuts go with it`() {
        HealthCuts.remember(cut("s", 1))
        HealthCuts.forget("s")
        assertNull(find("s", 1))
    }
}
