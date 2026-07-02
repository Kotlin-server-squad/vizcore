package com.jh.coroutinevisualizer.model

import com.jh.coroutinevisualizer.api.HierarchyNodeDto
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.MutableTreeNode
import javax.swing.tree.TreeNode

/**
 * Lazy Swing tree builder for the "All" history view (SC#4). The pure [RoundGrouping.plan] decides
 * which round groups exist and which are expanded; this class turns that plan into a
 * [DefaultTreeModel] whose cost is proportional to what is expanded, NOT to the total node count.
 *
 * Collapse economy (D-15): a collapsed round carries exactly one [Placeholder] child and zero
 * materialized [CoroutineRow] nodes. Expanding a group ([materialize], driven by plan 15-06's
 * `TreeWillExpandListener`, or eager expansion for in-progress / search rounds) swaps the placeholder
 * for the group's real subtree, built through the shared [rowFrom] factory so rows are identical to
 * Live mode. This is the SC#4 perf mechanism — collapse economy, never `expandAll`, never a
 * drop-and-rebuild of the whole tree.
 *
 * Node reuse (D-13): group nodes are keyed by rootId and coroutine nodes by id, so a refresh updates
 * `userObject`s in place and diffs children — a live JTree's expansion and selection survive the
 * 1.5s poll cadence.
 *
 * Transient-empty semantics: [apply] renders exactly what it is given. Suppressing a momentary empty
 * hierarchy while a previous non-empty snapshot exists is the CALLER's guard (panel Pitfall 4), not
 * this model's concern.
 */
@Suppress("TooManyFunctions") // small single-purpose tree-diff/materialization helpers inflate the count
class RoundTreeModel {
    private val root = DefaultMutableTreeNode()
    val treeModel: DefaultTreeModel = DefaultTreeModel(root)

    /** Group nodes keyed by round rootId — reused across [apply] calls so expansion is preserved. */
    private val groupNodesByRootId = mutableMapOf<String, DefaultMutableTreeNode>()

    /** Every materialized coroutine node across all groups, keyed by id for diff-based reuse. */
    private val coroutineNodesById = mutableMapOf<String, DefaultMutableTreeNode>()

    /** Rounds the user manually expanded via [materialize]; they stay materialized across refreshes. */
    private val userExpandedRootIds = mutableSetOf<String>()

    private var summaryNode: DefaultMutableTreeNode? = null

    private var snapshotByRoot: Map<String, List<HierarchyNodeDto>> = emptyMap()
    private var snapshotById: Map<String, HierarchyNodeDto> = emptyMap()
    private var snapshotLeakIds: Set<String> = emptySet()
    private var snapshotNowNanos: Long = 0

    /** The dummy child of a collapsed group; its presence marks the group as not-yet-materialized. */
    object Placeholder {
        override fun toString(): String = "…"
    }

    /**
     * Reconcile the tree to the collapse plan for [hierarchy]. [matchIds] null is the normal plan;
     * a non-null set is search mode (only matching rounds, all auto-expanded to their match closure,
     * no summary). [leakIds] and [problems] feed row badges and per-round counts; [nowNanos] makes
     * age deterministic. Returns the plan so the caller can mirror `expanded` onto the JTree —
     * Swing keeps nodes inserted under a collapsed parent collapsed, so model-side materialization
     * alone never auto-expands anything visually.
     */
    fun apply(
        hierarchy: List<HierarchyNodeDto>,
        leakIds: Set<String>,
        problems: List<Problem>,
        nowNanos: Long,
        matchIds: Set<String>? = null,
    ): RoundPlan {
        snapshotById = hierarchy.associateBy { it.id }
        snapshotByRoot = subtreeByRoot(hierarchy, snapshotById)
        snapshotLeakIds = leakIds
        snapshotNowNanos = nowNanos

        val plan = RoundGrouping.plan(hierarchy, problems, nowNanos, matchIds)

        val liveRootIds = HashSet<String>(plan.listed.size)
        val desired = ArrayList<DefaultMutableTreeNode>(plan.listed.size + 1)
        for (group in plan.listed) {
            liveRootIds += group.rootId
            desired += reconcileGroup(group)
        }
        reconcileSummary(plan.summary)?.let { desired += it }

        removeStaleGroups(liveRootIds)
        reconcileRootChildren(desired)
        return plan
    }

    /** The reused group node for [rootId], or null if the round is not currently listed. */
    fun groupNodeFor(rootId: String): DefaultMutableTreeNode? = groupNodesByRootId[rootId]

    /**
     * Materialize a collapsed group's subtree on demand (called by the tree's expand listener).
     * No-op unless the group still carries its [Placeholder]. Marks the round user-expanded so the
     * expansion survives subsequent refreshes.
     */
    fun materialize(groupNode: DefaultMutableTreeNode) {
        if (!hasOnlyPlaceholder(groupNode)) return
        val group = groupNode.userObject as? RoundGroup ?: return
        userExpandedRootIds += group.rootId
        stripPlaceholder(groupNode)
        reconcileGroupSubtree(groupNode, group.rootId, visible = null)
        treeModel.nodeStructureChanged(groupNode)
    }

    /** True when [node] is a group's dummy placeholder child. */
    fun isPlaceholder(node: DefaultMutableTreeNode): Boolean = node.userObject === Placeholder

    // --- group reconciliation -------------------------------------------------------------------

    private fun reconcileGroup(group: RoundGroup): DefaultMutableTreeNode {
        val node = groupNodesByRootId.getOrPut(group.rootId) { DefaultMutableTreeNode(group) }
        setUserObject(node, group)
        if (group.expanded || group.rootId in userExpandedRootIds) {
            val visible = if (group.expanded) group.visibleNodeIds else null
            stripPlaceholder(node)
            reconcileGroupSubtree(node, group.rootId, visible)
        } else {
            ensurePlaceholder(node)
        }
        return node
    }

    private fun reconcileSummary(summary: SummaryGroup?): DefaultMutableTreeNode? {
        if (summary == null) {
            summaryNode?.let { detach(it) }
            summaryNode = null
            return null
        }
        val node = summaryNode ?: DefaultMutableTreeNode().also { summaryNode = it }
        setUserObject(node, summary)
        ensurePlaceholder(node)
        return node
    }

    private fun removeStaleGroups(liveRootIds: Set<String>) {
        val stale = groupNodesByRootId.keys.filter { it !in liveRootIds }
        for (rootId in stale) {
            val node = groupNodesByRootId.remove(rootId) ?: continue
            userExpandedRootIds.remove(rootId)
            forgetCoroutineNodes(node)
            detach(node)
        }
    }

    // --- subtree (coroutine node) reconciliation ------------------------------------------------

    private fun reconcileGroupSubtree(
        groupNode: DefaultMutableTreeNode,
        rootId: String,
        visible: Set<String>?,
    ) {
        val dtos =
            (snapshotByRoot[rootId] ?: emptyList())
                .filter { visible == null || it.id in visible }
        val incoming = dtos.mapTo(HashSet()) { it.id }
        removeStaleCoroutineNodes(groupNode, incoming)
        upsertCoroutineNodes(dtos)
        wireCoroutineParents(dtos, incoming, groupNode)
    }

    private fun removeStaleCoroutineNodes(
        groupNode: DefaultMutableTreeNode,
        incoming: Set<String>,
    ) {
        for (descendant in coroutineDescendants(groupNode)) {
            val id = (descendant.userObject as CoroutineRow).id
            if (id !in incoming) {
                coroutineNodesById.remove(id)
                detach(descendant)
            }
        }
    }

    private fun upsertCoroutineNodes(dtos: List<HierarchyNodeDto>) {
        for (dto in dtos) {
            val row = rowFrom(dto, snapshotLeakIds, snapshotNowNanos)
            val existing = coroutineNodesById[dto.id]
            if (existing == null) {
                coroutineNodesById[dto.id] = DefaultMutableTreeNode(row)
            } else {
                existing.userObject = row
                if (isAttachedToRoot(existing)) treeModel.nodeChanged(existing)
            }
        }
    }

    private fun wireCoroutineParents(
        dtos: List<HierarchyNodeDto>,
        incoming: Set<String>,
        groupNode: DefaultMutableTreeNode,
    ) {
        for (dto in dtos.sortedBy { depthOf(it) }) {
            val node = coroutineNodesById.getValue(dto.id)
            val parent =
                dto.parentId
                    ?.takeIf { it in incoming }
                    ?.let { coroutineNodesById.getValue(it) }
                    ?: groupNode
            if (node.parent !== parent) {
                detach(node)
                attach(node, parent, parent.childCount)
            }
        }
    }

    // --- placeholder handling -------------------------------------------------------------------

    private fun ensurePlaceholder(node: DefaultMutableTreeNode) {
        if (hasOnlyPlaceholder(node)) return
        forgetCoroutineNodes(node)
        while (node.childCount > 0) detach(node.getChildAt(0) as DefaultMutableTreeNode)
        attach(DefaultMutableTreeNode(Placeholder), node, 0)
    }

    private fun stripPlaceholder(node: DefaultMutableTreeNode) {
        if (hasOnlyPlaceholder(node)) detach(node.getChildAt(0) as DefaultMutableTreeNode)
    }

    private fun hasOnlyPlaceholder(node: DefaultMutableTreeNode): Boolean =
        node.childCount == 1 && isPlaceholder(node.getChildAt(0) as DefaultMutableTreeNode)

    /** Drop every coroutine node under [node] from the id map (their tree removal happens separately). */
    private fun forgetCoroutineNodes(node: DefaultMutableTreeNode) {
        for (descendant in coroutineDescendants(node)) {
            coroutineNodesById.remove((descendant.userObject as CoroutineRow).id)
        }
    }

    // --- root child ordering --------------------------------------------------------------------

    private fun reconcileRootChildren(desired: List<DefaultMutableTreeNode>) {
        val keep = desired.toHashSet()
        val current = (0 until root.childCount).map { root.getChildAt(it) as DefaultMutableTreeNode }
        for (child in current) {
            if (child !in keep) treeModel.removeNodeFromParent(child)
        }
        desired.forEachIndexed { index, node ->
            val at = root.getIndex(node)
            when {
                at == -1 -> treeModel.insertNodeInto(node, root, index)
                at != index -> {
                    treeModel.removeNodeFromParent(node)
                    treeModel.insertNodeInto(node, root, index)
                }
            }
        }
    }

    // --- tree mutation helpers (event-firing only once a node is attached to the model root) -----

    private fun attach(
        child: DefaultMutableTreeNode,
        parent: DefaultMutableTreeNode,
        index: Int,
    ) {
        if (isAttachedToRoot(parent)) {
            treeModel.insertNodeInto(child, parent, index)
        } else {
            parent.insert(child, index)
        }
    }

    private fun detach(child: DefaultMutableTreeNode) {
        val parent = child.parent as? DefaultMutableTreeNode ?: return
        if (isAttachedToRoot(parent)) {
            treeModel.removeNodeFromParent(child)
        } else {
            parent.remove(child as MutableTreeNode)
        }
    }

    private fun setUserObject(
        node: DefaultMutableTreeNode,
        value: Any,
    ) {
        node.userObject = value
        if (isAttachedToRoot(node)) treeModel.nodeChanged(node)
    }

    private fun isAttachedToRoot(node: TreeNode?): Boolean {
        var current: TreeNode? = node
        while (current != null) {
            if (current === root) return true
            current = current.parent
        }
        return false
    }

    private fun coroutineDescendants(node: DefaultMutableTreeNode): List<DefaultMutableTreeNode> {
        val out = ArrayList<DefaultMutableTreeNode>()
        val stack = ArrayDeque<DefaultMutableTreeNode>()
        for (i in 0 until node.childCount) stack.addLast(node.getChildAt(i) as DefaultMutableTreeNode)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n.userObject is CoroutineRow) out += n
            for (i in 0 until n.childCount) stack.addLast(n.getChildAt(i) as DefaultMutableTreeNode)
        }
        return out
    }

    // --- snapshot grouping (mirrors RoundGrouping's O(n) parent-chain pass) ----------------------

    private fun subtreeByRoot(
        hierarchy: List<HierarchyNodeDto>,
        byId: Map<String, HierarchyNodeDto>,
    ): Map<String, List<HierarchyNodeDto>> {
        val rootOf = HashMap<String, String>()
        val out = HashMap<String, MutableList<HierarchyNodeDto>>()
        for (node in hierarchy) {
            out.getOrPut(rootFor(node.id, byId, rootOf)) { mutableListOf() }.add(node)
        }
        return out
    }

    private fun rootFor(
        startId: String,
        byId: Map<String, HierarchyNodeDto>,
        rootOf: MutableMap<String, String>,
    ): String {
        val chain = ArrayList<String>()
        var current = startId
        var resolved = rootOf[current]
        while (resolved == null) {
            chain += current
            val parent = byId[current]?.parentId?.takeIf { it in byId }
            if (parent == null) {
                resolved = current
            } else {
                current = parent
                resolved = rootOf[parent]
            }
        }
        chain.forEach { rootOf[it] = resolved }
        return resolved
    }

    private fun depthOf(dto: HierarchyNodeDto): Int {
        var depth = 0
        var current = dto.parentId
        while (current != null) {
            val parent = snapshotById[current] ?: break
            depth++
            current = parent.parentId
        }
        return depth
    }
}
