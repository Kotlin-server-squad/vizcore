package com.jh.coroutinevisualizer.model

import com.jh.coroutinevisualizer.api.HierarchyNodeDto
import com.jh.coroutinevisualizer.toolwindow.CoroutineStateStyle

/** Per-round tally mirroring the strip taxonomy: completed-clean / exception / amber (D-14). */
data class RoundCounts(
    val ok: Int,
    val exceptions: Int,
    val amber: Int,
) {
    operator fun plus(other: RoundCounts): RoundCounts = RoundCounts(ok + other.ok, exceptions + other.exceptions, amber + other.amber)
}

/**
 * One round = a root coroutine (parentId == null) plus its subtree (D-13). [visibleNodeIds] is null in
 * the normal plan (render everything under the root); during a search it holds the matched nodes plus
 * their in-group ancestor closure (D-17).
 */
data class RoundGroup(
    val rootId: String,
    val label: String,
    val counts: RoundCounts,
    val inProgress: Boolean,
    val expanded: Boolean,
    val visibleNodeIds: Set<String>?,
)

/** The folded tail of old, clean, finished rounds (D-15). Problem rounds never appear here (D-16). */
data class SummaryGroup(
    val label: String,
    val counts: RoundCounts,
    val rootIds: List<String>,
)

/** The collapse-economy plan for the history view: [listed] rounds newest-first + an optional [summary]. */
data class RoundPlan(
    val listed: List<RoundGroup>,
    val summary: SummaryGroup?,
)

/**
 * Pure round grouping + collapse economy for the "All" history view (SC#4). A round is each root
 * coroutine and its parentId-reachable subtree (D-13 — no name parsing, no time-gap heuristics). The
 * plan keeps in-progress rounds expanded, lists the [RECENT_ROUNDS_LISTED] most recent finished rounds
 * collapsed, folds older clean rounds into one summary, and never folds a round carrying problems
 * (D-15/D-16). Search filters to matching rounds and surfaces them out of the summary (D-17).
 *
 * Cost: one O(n) parent-chain pass, no recursion over children lists (T-15-02). The user query is only
 * ever used via String.contains — never compiled as a Regex (T-15-03, ASVS V5).
 */
object RoundGrouping {
    /** The most recent finished rounds shown individually (collapsed) before older ones fold (D-15). */
    const val RECENT_ROUNDS_LISTED = 5

    private const val NANOS_PER_MS = 1_000_000L

    /**
     * Case-insensitive plain-substring match over each node's name and id. A blank query yields the
     * empty set (the caller passes null for "no search").
     */
    fun searchMatchIds(
        hierarchy: List<HierarchyNodeDto>,
        query: String,
    ): Set<String> {
        if (query.isBlank()) return emptySet()
        return hierarchy
            .filter { it.name.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true) }
            .mapTo(HashSet()) { it.id }
    }

    /**
     * Builds the [RoundPlan]. [matchIds] == null is the normal collapse plan; a non-null set (even
     * empty) is search mode: only rounds whose subtree intersects it survive, all expanded, with
     * [RoundGroup.visibleNodeIds] set and no summary.
     */
    fun plan(
        hierarchy: List<HierarchyNodeDto>,
        problems: List<Problem>,
        nowNanos: Long,
        matchIds: Set<String>? = null,
    ): RoundPlan {
        val byId = hierarchy.associateBy { it.id }
        val subtrees = groupBySubtree(hierarchy, byId)
        val categories = categoriesById(problems)

        // Genuine roots ordered oldest-first so the 1-based chronological index (round 1 = oldest) is stable.
        val roots = hierarchy.filter { it.parentId == null }.sortedBy { it.createdAtNanos }
        val indexOf = roots.withIndex().associate { (i, root) -> root.id to (i + 1) }

        val rounds =
            roots.map { root ->
                val nodes = subtrees[root.id] ?: listOf(root)
                Round(
                    root = root,
                    nodes = nodes,
                    counts = countsFor(nodes, categories),
                    inProgress = nodes.any { it.completedAtNanos == null },
                    index = indexOf.getValue(root.id),
                )
            }

        return if (matchIds != null) searchPlan(rounds, byId, nowNanos, matchIds) else normalPlan(rounds, nowNanos)
    }

    private fun normalPlan(
        rounds: List<Round>,
        nowNanos: Long,
    ): RoundPlan {
        val (inProgress, finished) = rounds.partition { it.inProgress }
        val finishedByRecency = finished.sortedByDescending { it.root.createdAtNanos }
        val recent = finishedByRecency.take(RECENT_ROUNDS_LISTED)
        val older = finishedByRecency.drop(RECENT_ROUNDS_LISTED)
        val olderProblem = older.filter { it.counts.exceptions > 0 || it.counts.amber > 0 }
        val olderClean = older.filter { it.counts.exceptions == 0 && it.counts.amber == 0 }

        val listed =
            (inProgress + recent + olderProblem)
                .sortedByDescending { it.root.createdAtNanos }
                .map { round ->
                    RoundGroup(
                        rootId = round.root.id,
                        label = labelFor(round.root, nowNanos),
                        counts = round.counts,
                        inProgress = round.inProgress,
                        expanded = round.inProgress,
                        visibleNodeIds = null,
                    )
                }

        val summary =
            if (olderClean.isEmpty()) {
                null
            } else {
                val chronological = olderClean.sortedBy { it.index }
                SummaryGroup(
                    label = "Rounds ${chronological.first().index}–${chronological.last().index}",
                    counts = chronological.map { it.counts }.reduce(RoundCounts::plus),
                    rootIds = chronological.map { it.root.id },
                )
            }
        return RoundPlan(listed, summary)
    }

    private fun searchPlan(
        rounds: List<Round>,
        byId: Map<String, HierarchyNodeDto>,
        nowNanos: Long,
        matchIds: Set<String>,
    ): RoundPlan {
        val listed =
            rounds
                .sortedByDescending { it.root.createdAtNanos }
                .mapNotNull { round ->
                    val subtreeIds = round.nodes.mapTo(HashSet()) { it.id }
                    val matched = subtreeIds.intersect(matchIds)
                    if (matched.isEmpty()) return@mapNotNull null
                    RoundGroup(
                        rootId = round.root.id,
                        label = labelFor(round.root, nowNanos),
                        counts = round.counts,
                        inProgress = round.inProgress,
                        expanded = true,
                        visibleNodeIds = visibleFor(round.root.id, matched, subtreeIds, byId),
                    )
                }
        return RoundPlan(listed, summary = null)
    }

    /** Matched nodes + their parent chain within the group + the root id (D-17). */
    private fun visibleFor(
        rootId: String,
        matched: Set<String>,
        subtreeIds: Set<String>,
        byId: Map<String, HierarchyNodeDto>,
    ): Set<String> {
        val visible = HashSet<String>()
        visible += rootId
        for (id in matched) {
            var current: String? = id
            while (current != null && current in subtreeIds) {
                visible += current
                if (current == rootId) break
                current = byId[current]?.parentId
            }
        }
        return visible
    }

    /** Assigns every node to its root id via one memoized parent-chain walk (O(n) amortized). */
    private fun groupBySubtree(
        hierarchy: List<HierarchyNodeDto>,
        byId: Map<String, HierarchyNodeDto>,
    ): Map<String, List<HierarchyNodeDto>> {
        val rootOf = HashMap<String, String>()
        val subtrees = HashMap<String, MutableList<HierarchyNodeDto>>()
        for (node in hierarchy) {
            subtrees.getOrPut(rootFor(node.id, byId, rootOf)) { mutableListOf() }.add(node)
        }
        return subtrees
    }

    private fun rootFor(
        startId: String,
        byId: Map<String, HierarchyNodeDto>,
        rootOf: MutableMap<String, String>,
    ): String {
        val chain = ArrayList<String>()
        var current = startId
        var root = rootOf[current]
        while (root == null) {
            chain.add(current)
            val parent = byId[current]?.parentId?.takeIf { it in byId }
            if (parent == null) {
                root = current
            } else {
                current = parent
                root = rootOf[parent]
            }
        }
        chain.forEach { rootOf[it] = root }
        return root
    }

    private fun categoriesById(problems: List<Problem>): Map<String, Set<ProblemCategory>> {
        val byId = HashMap<String, MutableSet<ProblemCategory>>()
        problems.forEach { byId.getOrPut(it.coroutineId) { mutableSetOf() }.add(it.category) }
        return byId
    }

    private fun countsFor(
        nodes: List<HierarchyNodeDto>,
        categories: Map<String, Set<ProblemCategory>>,
    ): RoundCounts {
        var ok = 0
        var exceptions = 0
        var amber = 0
        for (node in nodes) {
            val cats = categories[node.id]
            when {
                cats != null && ProblemCategory.EXCEPTION in cats -> exceptions++
                cats != null && (ProblemCategory.LEAK in cats || ProblemCategory.LONG_SUSPENDED in cats) -> amber++
                node.completedAtNanos != null -> ok++
            }
        }
        return RoundCounts(ok, exceptions, amber)
    }

    private fun labelFor(
        root: HierarchyNodeDto,
        nowNanos: Long,
    ): String {
        val ageMs = (nowNanos - root.createdAtNanos) / NANOS_PER_MS
        return "${root.name} · started ${CoroutineStateStyle.ageLabel(ageMs)} ago"
    }

    private data class Round(
        val root: HierarchyNodeDto,
        val nodes: List<HierarchyNodeDto>,
        val counts: RoundCounts,
        val inProgress: Boolean,
        val index: Int,
    )
}
