package com.jh.proj.coroutineviz.checksystem

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import kotlinx.serialization.Serializable

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Timing report containing duration and suspension analysis.
 *
 * All duration values are in **milliseconds** (integer division from nanoseconds).
 * Sub-millisecond precision is lost intentionally — these values are for display only.
 *
 * @property coroutineDurations Map of coroutine ID to total duration in milliseconds
 *                              (from first event to last event)
 * @property suspensionDurations Map of coroutine ID to list of individual suspension
 *                               durations in milliseconds
 * @property totalDuration Overall duration of the event stream in milliseconds
 */
@Serializable
data class TimingReport(
    val coroutineDurations: Map<String, Long>,
    val suspensionDurations: Map<String, List<Long>>,
    val totalDuration: Long,
)

/**
 * Analyzes timing characteristics of coroutine event streams.
 *
 * Computes:
 * - Per-coroutine durations (first event to last event)
 * - Per-coroutine suspension durations (Suspended to Resumed pairs)
 * - Overall event stream duration
 *
 * All returned durations are in **milliseconds** (nanosecond timestamps divided by
 * [NANOS_PER_MILLI] = 1,000,000). This matches the `BackendTimingReport` frontend
 * contract where fields are documented as milliseconds.
 */
object TimingAnalyzer {
    /**
     * Analyze the given events and produce a [TimingReport].
     *
     * Duration values in the returned report are in **milliseconds**.
     *
     * @param events Full event stream (may contain events for multiple coroutines)
     * @return Timing analysis report with all durations in milliseconds
     */
    fun analyze(events: List<VizEvent>): TimingReport {
        val coroutineEvents = events.filterIsInstance<CoroutineEvent>()
        val byCoroutine = coroutineEvents.groupBy { it.coroutineId }

        val coroutineDurations = mutableMapOf<String, Long>()
        val suspensionDurations = mutableMapOf<String, List<Long>>()

        for ((coroutineId, cEvents) in byCoroutine) {
            val sorted = cEvents.sortedBy { it.tsNanos }

            // Total duration: first event to last event, converted ns → ms
            if (sorted.size >= 2) {
                val nsDelta = sorted.last().tsNanos - sorted.first().tsNanos
                coroutineDurations[coroutineId] = nsDelta / NANOS_PER_MILLI
            } else if (sorted.size == 1) {
                coroutineDurations[coroutineId] = 0L
            }

            // Suspension durations: pair Suspended with the next Resumed, converted ns → ms
            val suspensions = mutableListOf<Long>()
            var lastSuspended: CoroutineSuspended? = null

            for (event in sorted) {
                when (event) {
                    is CoroutineSuspended -> {
                        lastSuspended = event
                    }
                    is CoroutineResumed -> {
                        if (lastSuspended != null) {
                            val nsSpan = event.tsNanos - lastSuspended.tsNanos
                            suspensions.add(nsSpan / NANOS_PER_MILLI)
                            lastSuspended = null
                        }
                    }
                    else -> {}
                }
            }

            suspensionDurations[coroutineId] = suspensions
        }

        // Total stream duration: min to max tsNanos across all events, converted ns → ms
        val totalDuration =
            if (events.size >= 2) {
                val allTs = events.map { it.tsNanos }
                (allTs.max() - allTs.min()) / NANOS_PER_MILLI
            } else {
                0L
            }

        return TimingReport(
            coroutineDurations = coroutineDurations,
            suspensionDurations = suspensionDurations,
            totalDuration = totalDuration,
        )
    }
}
