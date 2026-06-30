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
        fun from(
            hierarchy: List<HierarchyNodeDto>,
            metrics: MetricsDto?,
        ): SessionModel {
            val leakIds = metrics?.leaks?.map { it.coroutineId }?.toSet() ?: emptySet()
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
            return SessionModel(hierarchy, leakIds, tiles)
        }
    }
}
