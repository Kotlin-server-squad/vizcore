package com.jh.proj.coroutineviz.checksystem

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.*
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
        // Use ms-scale tsNanos so integer ns/1_000_000 yields non-zero values.
        // 4_000_000_000 ns span = 4000 ms; 5_000_000_000 ns total = 5000 ms.
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
        // Use ms-scale tsNanos: each unit below is 1_000_000 ns = 1 ms.
        // c1: created at 1ms, started at 2ms, suspended at 3ms,
        //     resumed at 5ms (2ms suspension), suspended at 6ms,
        //     resumed at 9ms (3ms suspension), completed at 10ms.
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
        // c1: 1ms..5ms = 4ms; c2: 3ms..8ms = 5ms; total: 1ms..8ms = 7ms.
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

    @Test
    fun `durations are reported in milliseconds not nanoseconds`() {
        // 5-second scenario: tsNanos span = 5_000_000_000 ns = 5000 ms.
        // c1: started at 0, suspended at 500_000, resumed at 2_500_000, completed at 2_500_000.
        // c1 span = max - min of its tsNanos = 2_500_000 ns = 2 ms.
        // Suspension: resumed(2_500_000) - suspended(500_000) = 2_000_000 ns = 2 ms.
        // c2: drives the total stream to 5_000_000_000 ns = 5000 ms.
        val events: List<VizEvent> =
            listOf(
                started("c1", 1, tsNanos = 0L),
                suspended("c1", 2, tsNanos = 500_000L),
                resumed("c1", 3, tsNanos = 2_500_000L),
                completed("c1", 4, tsNanos = 2_500_000L),
                started("c2", 5, tsNanos = 0L),
                completed("c2", 6, tsNanos = 5_000_000_000L),
            )

        val report = TimingAnalyzer.analyze(events)

        // Total stream: 5_000_000_000 ns / 1_000_000 = 5000 ms
        assertEquals(5000L, report.totalDuration, "totalDuration must be 5000 ms for a 5s scenario (not 5_000_000_000)")
        assertTrue(
            report.totalDuration < 1_000_000L,
            "totalDuration must NOT be in nanosecond range (was ${report.totalDuration})",
        )

        // c1 coroutine duration: 2_500_000 ns / 1_000_000 = 2 ms
        val c1Duration = report.coroutineDurations["c1"]!!
        assertEquals(2L, c1Duration, "c1 coroutine duration must be 2 ms (2_500_000 ns / 1_000_000)")

        // Suspension: 2_000_000 ns / 1_000_000 = 2 ms
        val c1Suspensions = report.suspensionDurations["c1"]!!
        assertEquals(1, c1Suspensions.size, "c1 should have 1 suspension period")
        assertEquals(2L, c1Suspensions[0], "Suspension duration must be 2 ms (2_000_000 ns / 1_000_000)")
    }

    @Test
    fun `magnitude sanity - 5s scenario and sub-ms events map to expected ms values`() {
        // This is the magnitude-sanity guard: values must be in the ms range (thousands),
        // NOT the ns range (billions). A bug reintroducing ns would make these assertions
        // fail with values like 5_000_000_000 instead of 5000.
        //
        // Layout:
        //   c1: started at 0 ns, suspended at 0 ns, resumed at 2_000_000 ns (2 ms),
        //       completed at 1_500_000 ns.
        //       Sorted tsNanos: 0, 1_500_000, 2_000_000 → span = 2_000_000 ns = 2 ms.
        //       Suspension: 2_000_000 - 0 = 2_000_000 ns = 2 ms.
        //
        //   c2: started at 0 ns, completed at 1_500_000 ns.
        //       Span: 1_500_000 ns = 1 ms (integer division).
        //
        //   c3 (5s anchor): started at 0 ns, completed at 5_000_000_000 ns.
        //       totalDuration driven to 5_000_000_000 ns = 5000 ms.
        val events: List<VizEvent> =
            listOf(
                started("c1", 1, tsNanos = 0L),
                suspended("c1", 2, tsNanos = 0L),
                resumed("c1", 3, tsNanos = 2_000_000L),
                completed("c1", 4, tsNanos = 1_500_000L),
                started("c2", 5, tsNanos = 0L),
                completed("c2", 6, tsNanos = 1_500_000L),
                started("c3", 7, tsNanos = 0L),
                completed("c3", 8, tsNanos = 5_000_000_000L),
            )

        val report = TimingAnalyzer.analyze(events)

        // totalDuration must be 5000 ms — explicitly NOT 5_000_000_000
        assertEquals(5000L, report.totalDuration, "5s scenario: totalDuration == 5000 ms, not 5_000_000_000")
        assertTrue(report.totalDuration < 100_000L, "totalDuration must be in ms range, not ns range")

        // c2: 1_500_000 ns / 1_000_000 = 1 ms (integer division)
        assertEquals(1L, report.coroutineDurations["c2"], "c2: 1_500_000 ns span = 1 ms")

        // c1 suspension: 2_000_000 ns / 1_000_000 = 2 ms
        val c1Suspensions = report.suspensionDurations["c1"]!!
        assertEquals(1, c1Suspensions.size, "c1 should have 1 suspension")
        assertEquals(2L, c1Suspensions[0], "c1 suspension: 2_000_000 ns = 2 ms")
    }
}
