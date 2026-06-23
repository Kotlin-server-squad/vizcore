package com.jh.proj.coroutineviz.checksystem

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Magnitude-sanity guard proving the ns→ms conversion is authoritative in
 * coroutine-viz-core's [TimingAnalyzer] (NANOS_PER_MILLI = 1_000_000L).
 *
 * The backend `events/` + `checksystem/` forks were deleted (FND-01, this plan), so
 * `TimingAnalyzer` here resolves to the CORE class. If a fork class reintroduces the
 * raw-nanosecond behavior — or adverse fat-jar classloader ordering shadows the core
 * conversion — these assertions fail loudly with 10^9-scale values instead of ms
 * (threat T-02-03: classloader ordering silently undoing the ns→ms fix).
 *
 * This is the proving artifact distinct from the core module's own TimingAnalyzerTest:
 * it asserts realistic millisecond output for multi-second spans, the precise behavior
 * the BackendTimingReport frontend contract relies on.
 */
class TimingAnalyzerCoreTest {
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
    fun `core TimingAnalyzer converts a known ns span to the expected ms value`() {
        // 2_000_000_000 ns span → 2000 ms via NANOS_PER_MILLI = 1_000_000.
        val events: List<VizEvent> =
            listOf(
                started("c1", 1, tsNanos = 0L),
                completed("c1", 2, tsNanos = 2_000_000_000L),
            )

        val report = TimingAnalyzer.analyze(events)

        assertEquals(2000L, report.coroutineDurations["c1"], "2_000_000_000 ns / 1_000_000 = 2000 ms")
        assertEquals(2000L, report.totalDuration, "totalDuration: 2_000_000_000 ns = 2000 ms")
    }

    @Test
    fun `core TimingAnalyzer reports realistic ms for a multi-second span not nanosecond magnitude`() {
        // 5-second anchor: tsNanos span = 5_000_000_000 ns = 5000 ms.
        // c1: started at 0, suspended at 0, resumed at 2_000_000 (2 ms suspension),
        //     completed at 1_500_000. Sorted span = 2_000_000 ns = 2 ms.
        val events: List<VizEvent> =
            listOf(
                started("c1", 1, tsNanos = 0L),
                suspended("c1", 2, tsNanos = 0L),
                resumed("c1", 3, tsNanos = 2_000_000L),
                completed("c1", 4, tsNanos = 1_500_000L),
                started("c2", 5, tsNanos = 0L),
                completed("c2", 6, tsNanos = 5_000_000_000L),
            )

        val report = TimingAnalyzer.analyze(events)

        // Must be in ms range (thousands), NOT ns range (billions).
        assertEquals(5000L, report.totalDuration, "5s scenario: totalDuration == 5000 ms, not 5_000_000_000")
        assertTrue(
            report.totalDuration < 1_000_000L,
            "totalDuration must NOT be in nanosecond range (was ${report.totalDuration})",
        )

        assertEquals(2L, report.coroutineDurations["c1"], "c1 span: 2_000_000 ns / 1_000_000 = 2 ms")

        val c1Suspensions = report.suspensionDurations["c1"]!!
        assertEquals(1, c1Suspensions.size, "c1 should have 1 suspension period")
        assertEquals(2L, c1Suspensions[0], "c1 suspension: 2_000_000 ns / 1_000_000 = 2 ms")
    }
}
