package com.jh.proj.coroutineviz.checksystem

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimingAnalyzerTest {
    private fun created(
        coroutineId: String,
        seq: Long,
        tsNanos: Long,
    ): CoroutineCreated =
        CoroutineCreated(
            sessionId = "test-session",
            seq = seq,
            tsNanos = tsNanos,
            coroutineId = coroutineId,
            jobId = "job-$coroutineId",
            parentCoroutineId = null,
            scopeId = "scope-1",
            label = null,
        )

    private fun started(
        coroutineId: String,
        seq: Long,
        tsNanos: Long,
    ): CoroutineStarted =
        CoroutineStarted(
            sessionId = "test-session",
            seq = seq,
            tsNanos = tsNanos,
            coroutineId = coroutineId,
            jobId = "job-$coroutineId",
            parentCoroutineId = null,
            scopeId = "scope-1",
            label = null,
        )

    private fun suspended(
        coroutineId: String,
        seq: Long,
        tsNanos: Long,
    ): CoroutineSuspended =
        CoroutineSuspended(
            sessionId = "test-session",
            seq = seq,
            tsNanos = tsNanos,
            coroutineId = coroutineId,
            jobId = "job-$coroutineId",
            parentCoroutineId = null,
            scopeId = "scope-1",
            label = null,
            reason = "delay",
        )

    private fun resumed(
        coroutineId: String,
        seq: Long,
        tsNanos: Long,
    ): CoroutineResumed =
        CoroutineResumed(
            sessionId = "test-session",
            seq = seq,
            tsNanos = tsNanos,
            coroutineId = coroutineId,
            jobId = "job-$coroutineId",
            parentCoroutineId = null,
            scopeId = "scope-1",
            label = null,
        )

    private fun completed(
        coroutineId: String,
        seq: Long,
        tsNanos: Long,
    ): CoroutineCompleted =
        CoroutineCompleted(
            sessionId = "test-session",
            seq = seq,
            tsNanos = tsNanos,
            coroutineId = coroutineId,
            jobId = "job-$coroutineId",
            parentCoroutineId = null,
            scopeId = "scope-1",
            label = null,
        )

    @Test
    fun `duration calculation correct`() {
        // ms-scale tsNanos so integer ns/1_000_000 yields non-zero values.
        // span = 4_000_000_000 ns = 4000 ms; total = 4_000_000_000 ns = 4000 ms.
        val events: List<VizEvent> =
            listOf(
                created("c1", 1, tsNanos = 1_000_000_000L),
                started("c1", 2, tsNanos = 2_000_000_000L),
                completed("c1", 3, tsNanos = 5_000_000_000L),
            )

        val report = TimingAnalyzer.analyze(events)

        assertEquals(4000L, report.coroutineDurations["c1"], "Duration should be (last - first) tsNanos / 1_000_000 ms")
        assertEquals(4000L, report.totalDuration, "Total duration should cover entire stream in ms")
    }

    @Test
    fun `suspension durations tracked`() {
        // ms-scale tsNanos: each 1_000_000 ns = 1 ms.
        val events: List<VizEvent> =
            listOf(
                created("c1", 1, tsNanos = 1_000_000L),
                started("c1", 2, tsNanos = 2_000_000L),
                suspended("c1", 3, tsNanos = 3_000_000L),
                // 2_000_000 ns = 2 ms suspension
                resumed("c1", 4, tsNanos = 5_000_000L),
                suspended("c1", 5, tsNanos = 6_000_000L),
                // 3_000_000 ns = 3 ms suspension
                resumed("c1", 6, tsNanos = 9_000_000L),
                completed("c1", 7, tsNanos = 10_000_000L),
            )

        val report = TimingAnalyzer.analyze(events)

        val suspensions = report.suspensionDurations["c1"]!!
        assertEquals(2, suspensions.size, "Should have 2 suspension periods")
        assertEquals(2L, suspensions[0], "First suspension should be 2 ms")
        assertEquals(3L, suspensions[1], "Second suspension should be 3 ms")
    }

    @Test
    fun `report includes all coroutines`() {
        // ms-scale tsNanos: 1_000_000 ns = 1 ms multiplier.
        val events: List<VizEvent> =
            listOf(
                created("c1", 1, tsNanos = 1_000_000L),
                started("c1", 2, tsNanos = 2_000_000L),
                created("c2", 3, tsNanos = 3_000_000L),
                started("c2", 4, tsNanos = 4_000_000L),
                completed("c1", 5, tsNanos = 5_000_000L),
                completed("c2", 6, tsNanos = 8_000_000L),
            )

        val report = TimingAnalyzer.analyze(events)

        assertTrue(report.coroutineDurations.containsKey("c1"), "Report should include c1")
        assertTrue(report.coroutineDurations.containsKey("c2"), "Report should include c2")
        assertEquals(4L, report.coroutineDurations["c1"], "c1 duration: (5ms - 1ms) = 4 ms")
        assertEquals(5L, report.coroutineDurations["c2"], "c2 duration: (8ms - 3ms) = 5 ms")
        assertEquals(7L, report.totalDuration, "Total duration: (8ms - 1ms) = 7 ms")
    }

    @Test
    fun `empty events produce empty report`() {
        val report = TimingAnalyzer.analyze(emptyList())

        assertTrue(report.coroutineDurations.isEmpty(), "No coroutines in empty report")
        assertTrue(report.suspensionDurations.isEmpty(), "No suspensions in empty report")
        assertEquals(0L, report.totalDuration, "Total duration should be 0 for empty events")
    }
}
