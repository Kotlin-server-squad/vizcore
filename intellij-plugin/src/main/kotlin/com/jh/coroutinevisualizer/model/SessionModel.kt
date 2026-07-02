package com.jh.coroutinevisualizer.model

import com.jh.coroutinevisualizer.api.HierarchyNodeDto
import com.jh.coroutinevisualizer.api.MetricsDto

/** Header tile counts for the tool window. */
data class SessionTiles(
    val total: Int,
    val active: Int,
    val suspended: Int,
    val leakRisk: Int,
    val dispatchers: Int,
)

/**
 * Immutable observed state of one live session: the raw hierarchy (tree source), the server-computed
 * leak id set, and the header tile counts. Leaks are taken verbatim from MetricsDto.leaks — NOT
 * recomputed client-side.
 */
data class SessionModel(
    val hierarchy: List<HierarchyNodeDto>,
    val leakIds: Set<String>,
    val tiles: SessionTiles,
) {
    companion object {
        /** How long a COMPLETED coroutine stays visible in the live view after it finishes. */
        const val LIVE_COMPLETED_WINDOW_MS = 5_000L

        private const val NANOS_PER_MS = 1_000_000L

        fun from(
            hierarchy: List<HierarchyNodeDto>,
            metrics: MetricsDto?,
            nowNanos: Long = System.nanoTime(),
            completedWindowMs: Long = LIVE_COMPLETED_WINDOW_MS,
        ): SessionModel {
            val leakIds = metrics?.leaks?.map { it.coroutineId }?.toSet() ?: emptySet()
            // Tiles report the FULL, all-time input — filtering must not change their semantics.
            val active = hierarchy.count { it.state.equals("RUNNING", ignoreCase = true) }
            val suspended = hierarchy.count { it.state.equals("SUSPENDED", ignoreCase = true) }
            val tiles =
                SessionTiles(
                    total = hierarchy.size,
                    active = active,
                    suspended = suspended,
                    leakRisk = leakIds.size,
                    dispatchers = metrics?.dispatcherUtilization?.size ?: 0,
                )
            val filtered = filterLive(hierarchy, nowNanos, completedWindowMs * NANOS_PER_MS)
            return SessionModel(filtered, leakIds, tiles)
        }

        /**
         * Keep only live (not yet completed) and recently-completed (within [windowNanos]) coroutines,
         * then ancestor-close so no retained node is orphaned. Original input ordering is preserved.
         */
        private fun filterLive(
            full: List<HierarchyNodeDto>,
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
            // Ancestor closure: walk each kept node's parent chain and keep all ancestors.
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
