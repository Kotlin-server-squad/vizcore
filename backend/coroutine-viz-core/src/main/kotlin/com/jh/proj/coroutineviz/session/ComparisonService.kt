package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.dispatcher.ThreadAssigned
import kotlinx.serialization.Serializable

/**
 * Result of comparing two visualization sessions.
 *
 * Contains aggregate diffs (counts, duration) as well as per-coroutine
 * breakdowns for coroutines that appear in both sessions.
 *
 * @property sessionA ID of the first session
 * @property sessionB ID of the second session
 * @property coroutineCountDiff Difference in coroutine count (B - A)
 * @property eventCountDiff Difference in event count (B - A)
 * @property totalDurationDiffNanos Difference in session duration (B - A) in nanoseconds
 * @property distinctThreadsDiff Difference in distinct-thread (thread-utilization) count (B - A)
 * @property coroutinesOnlyInA Coroutine IDs present only in session A
 * @property coroutinesOnlyInB Coroutine IDs present only in session B
 * @property commonCoroutines Per-coroutine comparison for coroutines in both sessions
 */
@Serializable
data class SessionComparison(
    val sessionA: String,
    val sessionB: String,
    val coroutineCountDiff: Int,
    val eventCountDiff: Int,
    val totalDurationDiffNanos: Long,
    val distinctThreadsDiff: Int,
    val coroutinesOnlyInA: List<String>,
    val coroutinesOnlyInB: List<String>,
    val commonCoroutines: List<CoroutineComparison>,
)

/**
 * Comparison of a single coroutine that exists in both sessions.
 *
 * @property coroutineId The shared coroutine identifier
 * @property label Optional label from the coroutine (taken from session A if available)
 * @property stateA Current state in session A
 * @property stateB Current state in session B
 * @property eventCountA Number of events for this coroutine in session A
 * @property eventCountB Number of events for this coroutine in session B
 */
@Serializable
data class CoroutineComparison(
    val coroutineId: String,
    val label: String?,
    val stateA: String,
    val stateB: String,
    val eventCountA: Int,
    val eventCountB: Int,
)

/**
 * Service that compares two [VizSession] instances and produces a [SessionComparison].
 *
 * The comparison includes:
 * - Aggregate differences (coroutine count, event count, duration)
 * - Coroutines unique to each session
 * - Per-coroutine state and event count comparison for shared coroutines
 *
 * Duration is computed as the span between the earliest and latest event
 * timestamps in each session.
 */
object ComparisonService {

    /**
     * Compare two sessions and produce a structured diff.
     *
     * @param sessionA The first (baseline) session
     * @param sessionB The second (comparison) session
     * @return A [SessionComparison] describing all differences
     */
    fun compare(sessionA: VizSession, sessionB: VizSession): SessionComparison {
        val coroutinesA = sessionA.snapshot.coroutines
        val coroutinesB = sessionB.snapshot.coroutines

        val idsA = coroutinesA.keys
        val idsB = coroutinesB.keys

        val onlyInA = (idsA - idsB).sorted()
        val onlyInB = (idsB - idsA).sorted()
        val commonIds = (idsA intersect idsB).sorted()

        val eventsA = sessionA.store.all()
        val eventsB = sessionB.store.all()

        val commonCoroutines = commonIds.map { id ->
            val nodeA = coroutinesA.getValue(id)
            val nodeB = coroutinesB.getValue(id)

            CoroutineComparison(
                coroutineId = id,
                label = nodeA.label ?: nodeB.label,
                stateA = nodeA.state.toString(),
                stateB = nodeB.state.toString(),
                eventCountA = sessionA.store.byCoroutine(id).size,
                eventCountB = sessionB.store.byCoroutine(id).size,
            )
        }

        val durationA = computeDurationNanos(eventsA.map { it.tsNanos })
        val durationB = computeDurationNanos(eventsB.map { it.tsNanos })

        return SessionComparison(
            sessionA = sessionA.sessionId,
            sessionB = sessionB.sessionId,
            coroutineCountDiff = coroutinesB.size - coroutinesA.size,
            eventCountDiff = eventsB.size - eventsA.size,
            totalDurationDiffNanos = durationB - durationA,
            distinctThreadsDiff = distinctThreads(sessionB) - distinctThreads(sessionA),
            coroutinesOnlyInA = onlyInA,
            coroutinesOnlyInB = onlyInB,
            commonCoroutines = commonCoroutines,
        )
    }

    /**
     * Count the number of unique threads a session observed.
     *
     * Thread utilization is derived from [ThreadAssigned] events in the
     * session's event log; each event carries the system thread id the
     * coroutine actually ran on. Returns the number of distinct thread ids.
     */
    private fun distinctThreads(session: VizSession): Int =
        session.store.all()
            .filterIsInstance<ThreadAssigned>()
            .map { it.threadId }
            .distinct()
            .size

    /**
     * Compute the duration spanned by a list of timestamps.
     * Returns 0 if the list has fewer than 2 elements.
     */
    private fun computeDurationNanos(timestamps: List<Long>): Long {
        if (timestamps.size < 2) return 0L
        val min = timestamps.min()
        val max = timestamps.max()
        return max - min
    }
}
