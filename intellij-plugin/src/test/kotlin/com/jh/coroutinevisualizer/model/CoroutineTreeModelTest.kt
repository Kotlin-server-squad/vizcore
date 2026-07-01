package com.jh.coroutinevisualizer.model

import com.jh.coroutinevisualizer.api.HierarchyNodeDto
import org.junit.jupiter.api.Test
import javax.swing.tree.DefaultMutableTreeNode
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CoroutineTreeModelTest {
    private fun node(
        id: String,
        parentId: String?,
        children: List<String> = emptyList(),
        state: String = "RUNNING",
        threadName: String? = null,
    ) = HierarchyNodeDto(
        id = id,
        parentId = parentId,
        children = children,
        name = id,
        scopeId = "sc",
        state = state,
        jobId = "j-$id",
        currentThreadName = threadName,
    )

    private fun rowOf(n: DefaultMutableTreeNode) = n.userObject as CoroutineRow

    @Test fun `builds parent child tree under hidden root`() {
        val m = CoroutineTreeModel()
        m.apply(listOf(node("root", null, listOf("a")), node("a", "root")), emptySet())
        val root = m.treeModel.root as DefaultMutableTreeNode
        assertEquals(1, root.childCount)
        val rootNode = root.firstChild as DefaultMutableTreeNode
        assertEquals("root", rowOf(rootNode).id)
        assertEquals("a", rowOf(rootNode.firstChild as DefaultMutableTreeNode).id)
    }

    @Test fun `diff reuses node objects and updates state in place`() {
        val m = CoroutineTreeModel()
        m.apply(listOf(node("a", null, state = "RUNNING")), emptySet())
        val first = (m.treeModel.root as DefaultMutableTreeNode).firstChild
        m.apply(listOf(node("a", null, state = "SUSPENDED")), emptySet())
        val second = (m.treeModel.root as DefaultMutableTreeNode).firstChild
        assertSame(first, second)
        assertEquals("SUSPENDED", rowOf(second as DefaultMutableTreeNode).state)
    }

    @Test fun `removed id disappears`() {
        val m = CoroutineTreeModel()
        m.apply(listOf(node("a", null), node("b", null)), emptySet())
        m.apply(listOf(node("a", null)), emptySet())
        val root = m.treeModel.root as DefaultMutableTreeNode
        assertEquals(1, root.childCount)
        assertEquals("a", rowOf(root.firstChild as DefaultMutableTreeNode).id)
    }

    @Test fun `leak id marks row`() {
        val m = CoroutineTreeModel()
        m.apply(listOf(node("a", null)), setOf("a"))
        assertTrue(rowOf((m.treeModel.root as DefaultMutableTreeNode).firstChild as DefaultMutableTreeNode).isLeak)
    }

    @Test fun `populates thread name from the node`() {
        val m = CoroutineTreeModel()
        m.apply(listOf(node("a", null, threadName = "DefaultDispatcher-worker-3")), emptySet())
        val row = rowOf((m.treeModel.root as DefaultMutableTreeNode).firstChild as DefaultMutableTreeNode)
        assertEquals("DefaultDispatcher-worker-3", row.threadName)
    }

    @Test fun `re-parenting moves the node`() {
        val m = CoroutineTreeModel()
        m.apply(listOf(node("a", null), node("b", null)), emptySet())
        m.apply(listOf(node("a", null, listOf("b")), node("b", "a")), emptySet())
        val root = m.treeModel.root as DefaultMutableTreeNode
        assertEquals(1, root.childCount)
        val a = root.firstChild as DefaultMutableTreeNode
        assertEquals("b", rowOf(a.firstChild as DefaultMutableTreeNode).id)
    }
}
