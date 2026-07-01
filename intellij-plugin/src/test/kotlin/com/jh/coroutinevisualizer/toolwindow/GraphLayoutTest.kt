package com.jh.coroutinevisualizer.toolwindow

import com.jh.coroutinevisualizer.api.HierarchyNodeDto
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraphLayoutTest {
    private fun node(
        id: String,
        parentId: String?,
        children: List<String>,
        state: String = "RUNNING",
    ) = HierarchyNodeDto(
        id = id,
        parentId = parentId,
        children = children,
        name = "coroutine-$id",
        state = state,
    )

    /** root -> a, b ; a -> c */
    private fun sampleHierarchy() =
        listOf(
            node("root", null, listOf("a", "b")),
            node("a", "root", listOf("c")),
            node("b", "root", emptyList()),
            node("c", "a", emptyList()),
        )

    @Test fun `empty input yields empty model`() {
        val model = GraphLayout.compute(emptyList(), emptySet())
        assertTrue(model.nodes.isEmpty())
        assertTrue(model.edges.isEmpty())
        assertEquals(0, model.width)
        assertEquals(0, model.height)
    }

    @Test fun `all nodes are laid out`() {
        val model = GraphLayout.compute(sampleHierarchy(), emptySet())
        assertEquals(4, model.nodes.size)
        assertEquals(setOf("root", "a", "b", "c"), model.nodes.map { it.id }.toSet())
    }

    @Test fun `root sits at the smallest y`() {
        val model = GraphLayout.compute(sampleHierarchy(), emptySet())
        val root = model.nodes.first { it.id == "root" }
        assertEquals(model.nodes.minOf { it.y }, root.y)
    }

    @Test fun `children are positioned below their parents`() {
        val model = GraphLayout.compute(sampleHierarchy(), emptySet())
        val byId = model.nodes.associateBy { it.id }
        assertTrue(byId.getValue("a").y > byId.getValue("root").y)
        assertTrue(byId.getValue("b").y > byId.getValue("root").y)
        assertTrue(byId.getValue("c").y > byId.getValue("a").y)
    }

    @Test fun `parent-child edges exist`() {
        val model = GraphLayout.compute(sampleHierarchy(), emptySet())
        assertNotNull(model.edges.firstOrNull { it.fromId == "root" && it.toId == "a" })
        assertNotNull(model.edges.firstOrNull { it.fromId == "root" && it.toId == "b" })
        assertNotNull(model.edges.firstOrNull { it.fromId == "a" && it.toId == "c" })
        assertEquals(3, model.edges.size)
    }

    @Test fun `leak flag is propagated`() {
        val model = GraphLayout.compute(sampleHierarchy(), setOf("c"))
        assertTrue(model.nodes.first { it.id == "c" }.isLeak)
        assertTrue(model.nodes.filter { it.id != "c" }.none { it.isLeak })
    }

    @Test fun `orphan whose parent is absent is treated as a root`() {
        val hierarchy = listOf(node("orphan", "missing-parent", emptyList()))
        val model = GraphLayout.compute(hierarchy, emptySet())
        assertEquals(1, model.nodes.size)
        assertEquals(GraphLayout.MARGIN, model.nodes.single().y)
        assertTrue(model.edges.isEmpty())
    }

    @Test fun `canvas size covers all nodes`() {
        val model = GraphLayout.compute(sampleHierarchy(), emptySet())
        assertTrue(model.width >= model.nodes.maxOf { it.x } + GraphLayout.NODE_W)
        assertTrue(model.height >= model.nodes.maxOf { it.y } + GraphLayout.NODE_H)
    }
}
