package com.jh.coroutinevisualizer.model

import com.jh.coroutinevisualizer.api.HierarchyNodeDto

/**
 * Cross-poll memory of when each coroutine first entered SUSPENDED, mirroring FlashTracker's
 * put-and-compare idiom. Flags every id continuously SUSPENDED for at least [thresholdNanos];
 * leaving SUSPENDED (or vanishing from the hierarchy) clears the memory so the clock restarts on
 * re-entry (D-10/D-11). Not thread-safe; confined to the poll thread (FlashTracker idiom).
 *
 * Accepted caveat (D-11, Pitfall 1): idle-by-design coroutines (actor loops blocked on channel
 * receive) will flag after ~30s — tune the threshold later if noisy.
 */
class SuspensionTracker(private val thresholdNanos: Long = LONG_SUSPENDED_THRESHOLD_NANOS) {
    private val firstSuspendedAt = mutableMapOf<String, Long>()

    /**
     * Records the current SUSPENDED set and returns id -> firstSuspendedAtNanos for every id that
     * has been continuously suspended for at least [thresholdNanos] as of [nowNanos].
     */
    fun update(
        hierarchy: List<HierarchyNodeDto>,
        nowNanos: Long = System.nanoTime(),
    ): Map<String, Long> {
        val suspendedIds =
            hierarchy.filter { it.state.equals("SUSPENDED", ignoreCase = true) }
                .mapTo(HashSet()) { it.id }
        firstSuspendedAt.keys.retainAll(suspendedIds)
        suspendedIds.forEach { firstSuspendedAt.putIfAbsent(it, nowNanos) }
        return firstSuspendedAt.filterValues { nowNanos - it >= thresholdNanos }
    }

    companion object {
        /** ~30s in nanos (D-11). The millis twin lives beside LIVE_COMPLETED_WINDOW_MS in SessionModel. */
        const val LONG_SUSPENDED_THRESHOLD_NANOS = 30_000L * 1_000_000L
    }
}
