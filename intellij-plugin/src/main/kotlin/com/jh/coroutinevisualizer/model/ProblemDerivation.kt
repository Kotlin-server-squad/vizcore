package com.jh.coroutinevisualizer.model

import com.jh.coroutinevisualizer.api.HierarchyNodeDto
import com.jh.coroutinevisualizer.api.LeakDto
import com.jh.coroutinevisualizer.toolwindow.CoroutineStateStyle

/**
 * Pure derivation of the ordered problem list (D-06/D-07/D-09) from the full hierarchy, the
 * server-computed leaks (verbatim from MetricsDto — NEVER recomputed client-side), and the
 * long-suspended memory produced by [SuspensionTracker]. Age labels always route through
 * [CoroutineStateStyle.ageLabel] — no hand-rolled formatter.
 */
object ProblemDerivation {
    private const val NANOS_PER_MS = 1_000_000L
    private const val MAX_MESSAGE_LEN = 120

    /**
     * A real (user) exception — anything except a kotlin/kotlinx/JDK CancellationException, which is
     * normal structured-concurrency cancellation flow, not a problem (D-09, A1 rule).
     */
    fun isRealException(exceptionType: String?): Boolean = exceptionType != null && !exceptionType.endsWith("CancellationException")

    /**
     * Builds the ordered problem list: all EXCEPTION problems, then LEAK, then LONG_SUSPENDED (D-07
     * severity via category ordinal); within each category newest first by [Problem.sortKeyNanos]
     * (= createdAtNanos, A2). [longSuspended] maps a coroutine id to the nanos it first entered
     * SUSPENDED.
     */
    fun deriveProblems(
        hierarchy: List<HierarchyNodeDto>,
        leaks: List<LeakDto>,
        longSuspended: Map<String, Long>,
        nowNanos: Long,
    ): List<Problem> {
        val byId = hierarchy.associateBy { it.id }
        val problems =
            buildList {
                hierarchy.filter { isRealException(it.exceptionType) }.forEach { node ->
                    add(exceptionProblem(node, nowNanos))
                }
                leaks.forEach { leak ->
                    add(leakProblem(leak, byId[leak.coroutineId]))
                }
                longSuspended.forEach { (id, sinceNanos) ->
                    add(longSuspendedProblem(id, sinceNanos, byId[id], nowNanos))
                }
            }
        return problems.sortedWith(
            compareBy<Problem> { it.category.ordinal }.thenByDescending { it.sortKeyNanos },
        )
    }

    /** Union of coroutine ids across every problem category — the pin set for the live filter (D-12). */
    fun problemIds(problems: List<Problem>): Set<String> = problems.mapTo(HashSet()) { it.coroutineId }

    private fun exceptionProblem(
        node: HierarchyNodeDto,
        nowNanos: Long,
    ): Problem {
        val ageMs = (nowNanos - node.createdAtNanos) / NANOS_PER_MS
        return Problem(
            category = ProblemCategory.EXCEPTION,
            coroutineId = node.id,
            name = node.name,
            ageLabel = CoroutineStateStyle.ageLabel(ageMs),
            why = exceptionWhy(node.exceptionType, node.exceptionMessage),
            sortKeyNanos = node.createdAtNanos,
        )
    }

    private fun leakProblem(
        leak: LeakDto,
        node: HierarchyNodeDto?,
    ): Problem =
        Problem(
            category = ProblemCategory.LEAK,
            coroutineId = leak.coroutineId,
            name = leak.label ?: node?.name ?: leak.coroutineId,
            ageLabel = CoroutineStateStyle.ageLabel(leak.aliveMs),
            why = "alive " + CoroutineStateStyle.ageLabel(leak.aliveMs),
            sortKeyNanos = node?.createdAtNanos ?: 0L,
        )

    private fun longSuspendedProblem(
        id: String,
        sinceNanos: Long,
        node: HierarchyNodeDto?,
        nowNanos: Long,
    ): Problem {
        val suspendedMs = (nowNanos - sinceNanos) / NANOS_PER_MS
        return Problem(
            category = ProblemCategory.LONG_SUSPENDED,
            coroutineId = id,
            name = node?.name ?: id,
            ageLabel = CoroutineStateStyle.ageLabel(suspendedMs),
            why = "suspended " + CoroutineStateStyle.ageLabel(suspendedMs),
            sortKeyNanos = node?.createdAtNanos ?: 0L,
        )
    }

    private fun exceptionWhy(
        type: String?,
        message: String?,
    ): String {
        val simple = type?.substringAfterLast('.') ?: return ""
        val msg = message?.take(MAX_MESSAGE_LEN)?.takeIf { it.isNotBlank() }
        return if (msg != null) "$simple: $msg" else simple
    }
}
