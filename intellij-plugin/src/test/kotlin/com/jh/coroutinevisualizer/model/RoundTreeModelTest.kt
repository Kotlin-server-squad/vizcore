package com.jh.coroutinevisualizer.model

import com.jh.coroutinevisualizer.api.HierarchyNodeDto
import org.junit.jupiter.api.Test
import javax.swing.tree.DefaultMutableTreeNode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pure DefaultTreeModel assertions for the lazy All-mode tree (no JTree needed): collapse economy
 * (D-15 — collapsed rounds cost one placeholder), materialize-on-expand (D-13), node-instance reuse
 * across refreshes (expansion survives the poll cadence), problem rounds staying listed (D-16), and
 * match-set visibility (D-17).
 */
class RoundTreeModelTest {
    private val nowNanos = 100_000_000_000L

    private fun node(
        id: String,
        parentId: String? = null,
        state: String = "COMPLETED",
        createdAtNanos: Long = 0,
        completedAtNanos: Long? = 1,
        name: String = id,
        exceptionType: String? = null,
    ) = HierarchyNodeDto(
        id = id,
        parentId = parentId,
        name = name,
        scopeId = "sc",
        state = state,
        createdAtNanos = createdAtNanos,
        completedAtNanos = completedAtNanos,
        jobId = "j-$id",
        exceptionType = exceptionType,
    )

    /** One finished-clean root + one child, created at [createdAtNanos] (root) and +1 (child). */
    private fun cleanRound(index: Int) =
        listOf(
            node("r$index", createdAtNanos = index.toLong()),
            node("c$index", parentId = "r$index", createdAtNanos = index.toLong() + 100),
        )

    private fun root(model: RoundTreeModel) = model.treeModel.root as DefaultMutableTreeNode

    private fun rootChildren(model: RoundTreeModel): List<DefaultMutableTreeNode> =
        (0 until root(model).childCount).map { root(model).getChildAt(it) as DefaultMutableTreeNode }

    private fun coroutineRows(node: DefaultMutableTreeNode): List<CoroutineRow> {
        val out = ArrayList<CoroutineRow>()
        val stack = ArrayDeque<DefaultMutableTreeNode>()
        for (i in 0 until node.childCount) stack.addLast(node.getChildAt(i) as DefaultMutableTreeNode)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            (n.userObject as? CoroutineRow)?.let { out += it }
            for (i in 0 until n.childCount) stack.addLast(n.getChildAt(i) as DefaultMutableTreeNode)
        }
        return out
    }

    private fun coroutineNodes(node: DefaultMutableTreeNode): List<DefaultMutableTreeNode> {
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

    @Test fun `collapsed history costs one placeholder each while the in-progress round is materialized`() {
        val hierarchy =
            buildList {
                for (i in 1..8) addAll(cleanRound(i))
                add(node("r9", state = "RUNNING", completedAtNanos = null, createdAtNanos = 9))
                add(node("c9", parentId = "r9", state = "RUNNING", completedAtNanos = null, createdAtNanos = 109))
            }
        val model = RoundTreeModel()
        model.apply(hierarchy, emptySet(), emptyList(), nowNanos)

        val children = rootChildren(model)
        // 6 listed groups (in-progress + 5 recent) + 1 summary node
        assertEquals(7, children.size)

        val inProgress = children.first()
        val group = inProgress.userObject as RoundGroup
        assertEquals("r9", group.rootId)
        assertTrue(group.inProgress)
        // Materialized: r9 + c9, no placeholder
        assertFalse(model.isPlaceholder(inProgress.getChildAt(0) as DefaultMutableTreeNode))
        assertEquals(setOf("r9", "c9"), coroutineRows(inProgress).mapTo(HashSet()) { it.id })

        // The five recent collapsed groups each carry exactly one placeholder and zero coroutine nodes
        for (collapsed in children.subList(1, 6)) {
            assertTrue(collapsed.userObject is RoundGroup)
            assertEquals(1, collapsed.childCount)
            assertTrue(model.isPlaceholder(collapsed.getChildAt(0) as DefaultMutableTreeNode))
        }

        // Summary node folds the oldest three clean rounds; it is a LEAF (no expand handle — there is
        // no materialization path for a SummaryGroup, so a placeholder would be a dead "…" row).
        val summary = children.last()
        assertTrue(summary.userObject is SummaryGroup)
        assertEquals(0, summary.childCount)

        // D-15 proof: the ONLY materialized coroutine nodes are the in-progress round's
        assertEquals(2, coroutineNodes(root(model)).size)
    }

    @Test fun `materialize swaps a collapsed group's placeholder for its rowFrom subtree`() {
        val hierarchy =
            listOf(
                node("r1", createdAtNanos = 1),
                node("leak1", parentId = "r1", createdAtNanos = 2),
                node("exc1", parentId = "r1", createdAtNanos = 3, exceptionType = "java.lang.IllegalStateException"),
            )
        val model = RoundTreeModel()
        model.apply(hierarchy, setOf("leak1"), emptyList(), nowNanos)

        val groupNode = rootChildren(model).single { (it.userObject as? RoundGroup)?.rootId == "r1" }
        assertTrue(model.isPlaceholder(groupNode.getChildAt(0) as DefaultMutableTreeNode))

        model.materialize(groupNode)

        val rows = coroutineRows(groupNode).associateBy { it.id }
        assertEquals(setOf("r1", "leak1", "exc1"), rows.keys)
        assertTrue(rows.getValue("leak1").isLeak)
        assertTrue(rows.getValue("exc1").hasException)
        // Parent structure preserved within the group: children hang off the root coroutine node
        val rootCoroutine = coroutineNodes(groupNode).single { (it.userObject as CoroutineRow).id == "r1" }
        assertEquals(2, rootCoroutine.childCount)
    }

    @Test fun `refreshing reuses the same group and coroutine node instances`() {
        val hierarchy =
            listOf(
                node("r1", state = "RUNNING", completedAtNanos = null, createdAtNanos = 1),
                node("c1", parentId = "r1", state = "RUNNING", completedAtNanos = null, createdAtNanos = 2),
            )
        val model = RoundTreeModel()
        model.apply(hierarchy, emptySet(), emptyList(), nowNanos)

        val group1 = rootChildren(model).single()
        val coroutine1 = coroutineNodes(group1).single { (it.userObject as CoroutineRow).id == "c1" }

        model.apply(hierarchy, emptySet(), emptyList(), nowNanos)

        val group2 = rootChildren(model).single()
        assertSame(group1, group2)
        val coroutine2 = coroutineNodes(group2).single { (it.userObject as CoroutineRow).id == "c1" }
        assertSame(coroutine1, coroutine2)
    }

    @Test fun `an old problem round stays a listed group and never enters the summary`() {
        val hierarchy = (1..9).flatMap { cleanRound(it) }
        val problems = listOf(Problem(ProblemCategory.EXCEPTION, "r1", "r1", "~1.0s", "boom", 1))
        val model = RoundTreeModel()
        model.apply(hierarchy, emptySet(), problems, nowNanos)

        val listedRootIds = rootChildren(model).mapNotNull { (it.userObject as? RoundGroup)?.rootId }.toSet()
        assertTrue("r1" in listedRootIds)

        val summary = rootChildren(model).single { it.userObject is SummaryGroup }.userObject as SummaryGroup
        assertFalse("r1" in summary.rootIds)
    }

    @Test fun `a match set shows only the owning round, auto-expanded, with match plus ancestors and no summary`() {
        val hierarchy =
            buildList {
                add(node("r1", createdAtNanos = 1))
                add(node("child1", parentId = "r1", createdAtNanos = 2, name = "target"))
                for (i in 2..9) addAll(cleanRound(i))
            }
        val match = RoundGrouping.searchMatchIds(hierarchy, "target")
        val model = RoundTreeModel()
        model.apply(hierarchy, emptySet(), emptyList(), nowNanos, matchIds = match)

        val children = rootChildren(model)
        assertEquals(1, children.size)
        val groupNode = children.single()
        assertEquals("r1", (groupNode.userObject as RoundGroup).rootId)
        // Materialized immediately (auto-expand) with exactly the match + its ancestors
        assertEquals(setOf("r1", "child1"), coroutineRows(groupNode).mapTo(HashSet()) { it.id })
        assertNull(rootChildren(model).firstOrNull { it.userObject is SummaryGroup })
    }

    // --- container-root promotion (agent shape, GAP-ALL-MODE-MEGA-GROUP) -------------------------

    /**
     * Agent shape: one never-completing container root "main" whose 8 completed direct children each
     * own a 3-node subtree (child + 2 grandchildren), plus one in-progress child9 subtree.
     */
    private fun agentHierarchy(): List<HierarchyNodeDto> =
        buildList {
            add(node("main", state = "RUNNING", completedAtNanos = null, createdAtNanos = 1))
            for (i in 1..8) {
                val c = i * 10L
                add(node("child$i", parentId = "main", createdAtNanos = c, completedAtNanos = c + 5))
                add(node("g${i}a", parentId = "child$i", createdAtNanos = c + 1, completedAtNanos = c + 4))
                add(node("g${i}b", parentId = "child$i", createdAtNanos = c + 2, completedAtNanos = c + 4))
            }
            add(node("child9", parentId = "main", state = "RUNNING", completedAtNanos = null, createdAtNanos = 200))
            add(node("g9a", parentId = "child9", state = "RUNNING", completedAtNanos = null, createdAtNanos = 201))
        }

    private fun groupNode(
        model: RoundTreeModel,
        rootId: String,
    ) = rootChildren(model).single { (it.userObject as? RoundGroup)?.rootId == rootId }

    @Test fun `agent shape materializes only the in-progress round and the container singleton`() {
        val model = RoundTreeModel()
        model.apply(agentHierarchy(), emptySet(), emptyList(), nowNanos)

        val children = rootChildren(model)
        // container singleton + in-progress child + 5 recent collapsed = 7 groups + 1 summary
        assertEquals(8, children.size)

        // The container root renders as a thin SINGLETON (its own row only), never the whole app.
        assertEquals(setOf("main"), coroutineRows(groupNode(model, "main")).mapTo(HashSet()) { it.id })
        // The in-progress child round is materialized.
        assertEquals(setOf("child9", "g9a"), coroutineRows(groupNode(model, "child9")).mapTo(HashSet()) { it.id })

        // The 5 recent collapsed promoted rounds each carry exactly one placeholder and zero coroutine nodes.
        val collapsed = children.filter { (it.userObject as? RoundGroup)?.expanded == false }
        assertEquals(5, collapsed.size)
        for (node in collapsed) {
            assertEquals(1, node.childCount)
            assertTrue(model.isPlaceholder(node.getChildAt(0) as DefaultMutableTreeNode))
        }

        // Perf property: ONLY the container singleton (1) + the in-progress round (2) are materialized.
        assertEquals(3, coroutineNodes(root(model)).size)
    }

    @Test fun `nodeFor a grandchild in a collapsed promoted round materializes its own round not the container`() {
        val model = RoundTreeModel()
        model.apply(agentHierarchy(), emptySet(), emptyList(), nowNanos)

        // g4a lives in child4's round (one of the 5 recent, collapsed).
        val node = model.nodeFor("g4a")
        assertEquals("g4a", (node?.userObject as CoroutineRow).id)

        // Its OWN round (child4) is now materialized...
        assertTrue(coroutineRows(groupNode(model, "child4")).any { it.id == "g4a" })
        // ...and the container singleton stays a singleton (NOT expanded to the whole app).
        assertEquals(setOf("main"), coroutineRows(groupNode(model, "main")).mapTo(HashSet()) { it.id })
    }

    @Test fun `expanding a promoted round survives a subsequent apply refresh`() {
        val model = RoundTreeModel()
        val hierarchy = agentHierarchy()
        model.apply(hierarchy, emptySet(), emptyList(), nowNanos)

        model.materialize(groupNode(model, "child5"))
        assertEquals(setOf("child5", "g5a", "g5b"), coroutineRows(groupNode(model, "child5")).mapTo(HashSet()) { it.id })

        // A poll refresh must keep the user-expanded promoted round materialized (userExpandedRootIds by anchor).
        model.apply(hierarchy, emptySet(), emptyList(), nowNanos)
        assertEquals(setOf("child5", "g5a", "g5b"), coroutineRows(groupNode(model, "child5")).mapTo(HashSet()) { it.id })
    }

    @Test fun `clearing the match restores the normal collapse layout`() {
        val hierarchy =
            buildList {
                add(node("r1", createdAtNanos = 1))
                add(node("child1", parentId = "r1", createdAtNanos = 2, name = "target"))
                for (i in 2..9) addAll(cleanRound(i))
            }
        val model = RoundTreeModel()
        val match = RoundGrouping.searchMatchIds(hierarchy, "target")
        model.apply(hierarchy, emptySet(), emptyList(), nowNanos, matchIds = match)
        // Now clear
        model.apply(hierarchy, emptySet(), emptyList(), nowNanos, matchIds = null)

        // Back to collapse economy: a summary reappears and no coroutine node is materialized
        assertTrue(rootChildren(model).any { it.userObject is SummaryGroup })
        assertTrue(rootChildren(model).any { it.userObject is RoundGroup })
        assertEquals(0, coroutineNodes(root(model)).size)
    }
}
