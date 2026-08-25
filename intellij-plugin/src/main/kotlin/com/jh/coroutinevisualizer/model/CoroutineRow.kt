package com.jh.coroutinevisualizer.model

import com.jh.coroutinevisualizer.api.HierarchyNodeDto

/** Immutable per-coroutine view-model rendered as one tree row. ageMs is approximate (poll-bounded). */
data class CoroutineRow(
    val id: String,
    val name: String,
    val state: String,
    val dispatcherName: String?,
    val threadName: String?,
    val ageMs: Long,
    val childCount: Int,
    val isLeak: Boolean,
    val hasException: Boolean,
)

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Single source of truth for mapping a [HierarchyNodeDto] to a [CoroutineRow]. Shared by the Live
 * tree ([CoroutineTreeModel]) and — later — the All-mode tree (plan 15-05), so the row shape can
 * never drift between the two views.
 *
 * [hasException] follows the ONE exception rule ([ProblemDerivation.isRealException], D-09): a
 * cancellation is normal structured-concurrency flow and is NEVER badged; only a real user exception
 * is. [nowNanos] is injectable so the age computation is deterministic under test.
 */
fun rowFrom(
    dto: HierarchyNodeDto,
    leakIds: Set<String>,
    nowNanos: Long = System.nanoTime(),
): CoroutineRow {
    val ageMs =
        if (dto.createdAtNanos > 0) {
            (nowNanos - dto.createdAtNanos).coerceAtLeast(0) / NANOS_PER_MILLI
        } else {
            0
        }
    return CoroutineRow(
        id = dto.id,
        name = dto.name,
        state = dto.state,
        dispatcherName = dto.dispatcherName,
        threadName = dto.currentThreadName,
        ageMs = ageMs,
        childCount = dto.children.size,
        isLeak = dto.id in leakIds,
        hasException = ProblemDerivation.isRealException(dto.exceptionType),
    )
}
