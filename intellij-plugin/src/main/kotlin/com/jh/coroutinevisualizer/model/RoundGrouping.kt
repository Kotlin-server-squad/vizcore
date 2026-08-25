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
 * One round = a round ANCHOR plus its subtree. An anchor is a genuine root coroutine (parentId == null,
 * D-13) OR — on the agent/DebugProbes real-app shape where one long-lived root never completes — each
 * DIRECT CHILD of that container root (container-root promotion). [rootId] is the anchor id. [visibleNodeIds]
 * is null in the normal plan (render everything under the anchor); during a search it holds the matched
 * nodes plus their in-group ancestor closure (D-17).
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
 * Pure round grouping + collapse economy for the "All" history view (SC#4). A round is each ANCHOR and
 * its anchor-reachable subtree. The plan keeps in-progress rounds expanded, lists the
 * [RECENT_ROUNDS_LISTED] most recent finished rounds collapsed, folds older clean rounds into one
 * summary, and never folds a round carrying problems (D-15/D-16). Search filters to matching rounds and
 * surfaces them out of the summary (D-17).
 *
 * ## Anchoring (D-13 + container-root promotion)
 *
 * D-13 root-anchoring — one round per genuine root (parentId == null) — holds exactly for apps whose
 * logical rounds each own their own root (the VizScope shape; byte-preserved here). It breaks on the
 * canonical agent/DebugProbes real-app shape: a long-lived `main`/application scope that NEVER completes,
 * under which every workload round is a DIRECT CHILD. There the whole app collapses into one permanently
 * in-progress, permanently-expanded mega-round and the collapse economy never engages.
 *
 * Container-root promotion fixes this using ONLY structural signals (never name parsing, never time-gap
 * heuristics — both explicitly rejected by D-13): a genuine root is a CONTAINER when it never completes
 * AND has >= [CONTAINER_MIN_CHILD_SUBTREES] direct children AND at least half of those direct-child
 * subtrees are fully completed. A container's DIRECT CHILDREN become the round anchors; the container
 * root itself becomes a thin SINGLETON anchor (its round holds only itself — still in-progress, still
 * listed+expanded, but one row instead of thousands). Accepted hysteresis: early in a session (< 3
 * children) the root is one normal round and re-keys into container form as children accumulate — a
 * one-time visual reshuffle of the group nodes when the flip happens.
 *
 * Cost: one O(n) structural pass to classify containers + one O(n) parent-chain pass to anchor nodes,
 * no recursion over children lists (T-15-02). The user query is only ever used via String.contains —
 * never compiled as a Regex (T-15-03, ASVS V5).
 */
@Suppress("TooManyFunctions") // small single-purpose anchor/classification/plan helpers inflate the count
object RoundGrouping {
    /** The most recent finished rounds shown individually (collapsed) before older ones fold (D-15). */
    const val RECENT_ROUNDS_LISTED = 5

    /** A never-completing genuine root needs at least this many direct children to be a container root. */
    const val CONTAINER_MIN_CHILD_SUBTREES = 3

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
        val anchors = anchorIds(hierarchy, byId)
        val subtrees = subtreeByAnchor(hierarchy, byId, anchors)
        val categories = categoriesById(problems)

        // Anchors ordered oldest-first so the 1-based chronological index (round 1 = oldest) is stable.
        val anchorNodes = hierarchy.filter { it.id in anchors }.sortedBy { it.createdAtNanos }
        val indexOf = anchorNodes.withIndex().associate { (i, anchor) -> anchor.id to (i + 1) }

        val rounds =
            anchorNodes.map { anchor ->
                val nodes = subtrees[anchor.id] ?: listOf(anchor)
                Round(
                    anchor = anchor,
                    nodes = nodes,
                    counts = countsFor(nodes, categories),
                    inProgress = nodes.any { it.completedAtNanos == null },
                    index = indexOf.getValue(anchor.id),
                )
            }

        return if (matchIds != null) searchPlan(rounds, byId, nowNanos, matchIds) else normalPlan(rounds, nowNanos)
    }

    /**
     * The round-anchor id set: every non-container genuine root, PLUS every direct child of a container
     * root, PLUS each container root itself (as a singleton anchor). Single source of truth shared by
     * [plan] and [RoundTreeModel] so the tree model never re-derives grouping independently.
     */
    fun anchorIds(
        hierarchy: List<HierarchyNodeDto>,
        byId: Map<String, HierarchyNodeDto>,
    ): Set<String> {
        val childrenOf = childrenOf(hierarchy, byId)
        val containerRoots = containerRootIds(hierarchy, childrenOf, byId)
        val anchors = HashSet<String>()
        for (node in hierarchy) {
            // Every genuine root is an anchor: a non-container root anchors its whole subtree; a
            // container root is a singleton anchor (its children below are anchors in their own right).
            if (node.parentId == null) anchors += node.id
        }
        for (container in containerRoots) {
            childrenOf[container]?.forEach { anchors += it }
        }
        return anchors
    }

    /** Convenience overload that computes the anchor set itself. */
    fun subtreeByAnchor(
        hierarchy: List<HierarchyNodeDto>,
        byId: Map<String, HierarchyNodeDto>,
    ): Map<String, List<HierarchyNodeDto>> = subtreeByAnchor(hierarchy, byId, anchorIds(hierarchy, byId))

    /** Assigns every node to its owning anchor via one memoized nearest-anchor parent-chain walk (O(n)). */
    fun subtreeByAnchor(
        hierarchy: List<HierarchyNodeDto>,
        byId: Map<String, HierarchyNodeDto>,
        anchors: Set<String>,
    ): Map<String, List<HierarchyNodeDto>> {
        val cache = HashMap<String, String>()
        val subtrees = HashMap<String, MutableList<HierarchyNodeDto>>()
        for (node in hierarchy) {
            subtrees.getOrPut(anchorFor(node.id, byId, anchors, cache)) { mutableListOf() }.add(node)
        }
        return subtrees
    }

    /**
     * The owning anchor id for [startId]: walk the parent chain up to the NEAREST anchor (a node that is
     * itself an anchor resolves to itself; a container root only ever resolves to itself since all its
     * children are anchors). [cache] memoizes the chain across calls. Every genuine root is an anchor, so
     * the walk always terminates.
     */
    fun anchorFor(
        startId: String,
        byId: Map<String, HierarchyNodeDto>,
        anchors: Set<String>,
        cache: MutableMap<String, String>,
    ): String {
        val chain = ArrayList<String>()
        var current = startId
        var resolved = cache[current]
        while (resolved == null) {
            chain.add(current)
            if (current in anchors) {
                resolved = current
            } else {
                val parent = byId[current]?.parentId?.takeIf { it in byId }
                if (parent == null) {
                    resolved = current // unanchored genuine root — resolves to itself
                } else {
                    current = parent
                    resolved = cache[parent]
                }
            }
        }
        chain.forEach { cache[it] = resolved }
        return resolved
    }

    /**
     * Classifies the never-completing genuine roots that are CONTAINERS: >= [CONTAINER_MIN_CHILD_SUBTREES]
     * direct children AND at least half of those direct-child subtrees fully completed. Pure structural
     * signals, one bounded iterative walk per candidate over disjoint subtrees (O(n) total, no recursion).
     */
    private fun containerRootIds(
        hierarchy: List<HierarchyNodeDto>,
        childrenOf: Map<String, List<String>>,
        byId: Map<String, HierarchyNodeDto>,
    ): Set<String> =
        hierarchy
            .filter { isContainerRoot(it, childrenOf, byId) }
            .mapTo(HashSet()) { it.id }

    private fun isContainerRoot(
        root: HierarchyNodeDto,
        childrenOf: Map<String, List<String>>,
        byId: Map<String, HierarchyNodeDto>,
    ): Boolean {
        if (root.parentId != null || root.completedAtNanos != null) return false
        val directChildren = childrenOf[root.id].orEmpty()
        val completedSubtrees = directChildren.count { subtreeFullyCompleted(it, childrenOf, byId) }
        return directChildren.size >= CONTAINER_MIN_CHILD_SUBTREES && completedSubtrees * 2 >= directChildren.size
    }

    /** True when every node in [startId]'s subtree has completed (iterative, cycle-guarded, no recursion). */
    private fun subtreeFullyCompleted(
        startId: String,
        childrenOf: Map<String, List<String>>,
        byId: Map<String, HierarchyNodeDto>,
    ): Boolean {
        val stack = ArrayDeque<String>()
        val visited = HashSet<String>()
        stack.addLast(startId)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast().takeIf { visited.add(it) }?.let { byId[it] }
            if (node != null) {
                if (node.completedAtNanos == null) return false
                childrenOf[node.id]?.forEach { stack.addLast(it) }
            }
        }
        return true
    }

    /** parentId -> direct child ids, restricted to parents present in [byId] (drops dangling parents). */
    private fun childrenOf(
        hierarchy: List<HierarchyNodeDto>,
        byId: Map<String, HierarchyNodeDto>,
    ): Map<String, List<String>> {
        val childrenOf = HashMap<String, MutableList<String>>()
        for (node in hierarchy) {
            val parent = node.parentId ?: continue
            if (parent in byId) childrenOf.getOrPut(parent) { mutableListOf() }.add(node.id)
        }
        return childrenOf
    }

    private fun normalPlan(
        rounds: List<Round>,
        nowNanos: Long,
    ): RoundPlan {
        val (inProgress, finished) = rounds.partition { it.inProgress }
        val finishedByRecency = finished.sortedByDescending { it.anchor.createdAtNanos }
        val recent = finishedByRecency.take(RECENT_ROUNDS_LISTED)
        val older = finishedByRecency.drop(RECENT_ROUNDS_LISTED)
        val olderProblem = older.filter { it.counts.exceptions > 0 || it.counts.amber > 0 }
        val olderClean = older.filter { it.counts.exceptions == 0 && it.counts.amber == 0 }

        val listed =
            (inProgress + recent + olderProblem)
                .sortedByDescending { it.anchor.createdAtNanos }
                .map { round ->
                    RoundGroup(
                        rootId = round.anchor.id,
                        label = labelFor(round.anchor, nowNanos),
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
                    rootIds = chronological.map { it.anchor.id },
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
                .sortedByDescending { it.anchor.createdAtNanos }
                .mapNotNull { round ->
                    val subtreeIds = round.nodes.mapTo(HashSet()) { it.id }
                    val matched = subtreeIds.intersect(matchIds)
                    if (matched.isEmpty()) return@mapNotNull null
                    RoundGroup(
                        rootId = round.anchor.id,
                        label = labelFor(round.anchor, nowNanos),
                        counts = round.counts,
                        inProgress = round.inProgress,
                        expanded = true,
                        visibleNodeIds = visibleFor(round.anchor.id, matched, subtreeIds, byId),
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
        val anchor: HierarchyNodeDto,
        val nodes: List<HierarchyNodeDto>,
        val counts: RoundCounts,
        val inProgress: Boolean,
        val index: Int,
    )
}
