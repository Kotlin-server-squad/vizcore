package com.jh.coroutinevisualizer.model

import com.jh.coroutinevisualizer.api.HierarchyNodeDto
import com.jh.coroutinevisualizer.api.MetricsDto

/** Header tile counts for the tool window: Active / Throughput / Leaks / Peak (D-22). */
data class SessionTiles(
    val active: Int,
    val throughputPerSec: Double,
    val leaks: Int,
    val peak: Int,
)

/**
 * Immutable observed state of one live session: the live-filtered [hierarchy] (tree source), the
 * unfiltered [fullHierarchy] (All mode + the "All n" count), the server-computed leak id set, the
 * ordered [problems] list, and the header tile counts. Leaks are taken verbatim from MetricsDto.leaks
 * — NOT recomputed client-side.
 */
data class SessionModel(
    val hierarchy: List<HierarchyNodeDto>,
    val fullHierarchy: List<HierarchyNodeDto>,
    val leakIds: Set<String>,
    val problems: List<Problem>,
    val tiles: SessionTiles,
) {
    companion object {
        /** How long a COMPLETED coroutine stays visible in the live view after it finishes. */
        const val LIVE_COMPLETED_WINDOW_MS = 5_000L

        /** A coroutine continuously SUSPENDED past this many ms is flagged long-suspended (D-11). */
        const val LONG_SUSPENDED_THRESHOLD_MS = 30_000L

        private const val NANOS_PER_MS = 1_000_000L

        fun from(
            hierarchy: List<HierarchyNodeDto>,
            metrics: MetricsDto?,
            nowNanos: Long = System.nanoTime(),
            completedWindowMs: Long = LIVE_COMPLETED_WINDOW_MS,
            longSuspended: Map<String, Long> = emptyMap(),
        ): SessionModel {
            val leaks = metrics?.leaks ?: emptyList()
            val leakIds = leaks.map { it.coroutineId }.toSet()
            // Problems + tiles are computed from the FULL, all-time input — filtering must not change them.
            val problems = ProblemDerivation.deriveProblems(hierarchy, leaks, longSuspended, nowNanos)
            val active = metrics?.active ?: hierarchy.count { it.state.equals("RUNNING", ignoreCase = true) }
            val tiles =
                SessionTiles(
                    active = active,
                    throughputPerSec = metrics?.throughputPerSec ?: 0.0,
                    leaks = leakIds.size,
                    peak = metrics?.peak ?: 0,
                )
            val filtered = filterLive(hierarchy, problems, nowNanos, completedWindowMs * NANOS_PER_MS)
            return SessionModel(filtered, hierarchy, leakIds, problems, tiles)
        }

        /**
         * Live view = active + recently-completed (within [windowNanos]) + problems, then ancestor-closed
         * so no retained node is orphaned (D-12). Original input ordering is preserved.
         */
        private fun filterLive(
            full: List<HierarchyNodeDto>,
            problems: List<Problem>,
            nowNanos: Long,
            windowNanos: Long,
        ): List<HierarchyNodeDto> {
            val byId = full.associateBy { it.id }
            val keep = HashSet<String>()
            for (node in full) {
                val completed = node.completedAtNanos
                val retain = completed == null || (nowNanos - completed) <= windowNanos
                if (retain) keep += node.id
            }
            // D-12: pin problem nodes (leaks ∪ exceptions ∪ long-suspended) regardless of the window.
            for (id in ProblemDerivation.problemIds(problems)) {
                if (id in byId) keep += id
            }
            return ancestorClose(full, keep)
        }

        /**
         * Filters [hierarchy] to only the nodes owning a problem of [category] plus their ancestors
         * (D-03 chip filter). Input order preserved.
         */
        fun filterToProblemCategory(
            hierarchy: List<HierarchyNodeDto>,
            problems: List<Problem>,
            category: ProblemCategory,
        ): List<HierarchyNodeDto> {
            val keep = problems.filter { it.category == category }.mapTo(HashSet()) { it.coroutineId }
            return ancestorClose(hierarchy, keep)
        }

        /**
         * Walks each kept node's parent chain and keeps all ancestors, then returns the input list
         * filtered to the keep-set (order preserved). Shared by [filterLive] and [filterToProblemCategory].
         */
        internal fun ancestorClose(
            full: List<HierarchyNodeDto>,
            keep: MutableSet<String>,
        ): List<HierarchyNodeDto> {
            val byId = full.associateBy { it.id }
            for (id in keep.toList()) {
                var parentId = byId[id]?.parentId
                while (parentId != null && parentId !in keep) {
                    keep += parentId
                    parentId = byId[parentId]?.parentId
                }
            }
            return full.filter { it.id in keep }
        }
    }
}
