package com.jh.proj.coroutineviz.health

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtreeIndexTest {
    @Test
    fun `root qualifies once half of at least three child subtrees are finished`() {
        val index = SubtreeIndex()
        index.add("root", null)
        listOf("a", "b", "c").forEach { index.add(it, "root") }
        assertFalse(index.qualifiesAsContainer("root"))

        index.close("a")
        assertFalse(index.qualifiesAsContainer("root"), "1 of 3 finished")

        index.close("b")
        assertTrue(index.qualifiesAsContainer("root"), "2 of 3 finished")
    }

    @Test
    fun `a child subtree counts as finished only when all its descendants are`() {
        val index = SubtreeIndex()
        index.add("root", null)
        listOf("a", "b", "c").forEach { index.add(it, "root") }
        index.add("a1", "a")
        index.close("a")
        index.close("b")
        assertFalse(index.qualifiesAsContainer("root"), "a's child a1 is still open")

        index.close("a1")
        assertTrue(index.qualifiesAsContainer("root"))
    }

    @Test
    fun `fewer than three children never qualify`() {
        val index = SubtreeIndex()
        index.add("root", null)
        index.add("a", "root")
        index.add("b", "root")
        index.close("a")
        index.close("b")
        assertFalse(index.qualifiesAsContainer("root"))
    }

    @Test
    fun `children created before their parent are linked when it arrives`() {
        val index = SubtreeIndex()
        index.add("a", "root")
        index.close("a")
        index.add("b", "root")
        index.add("root", null)
        index.add("c", "root")

        assertEquals("root", index.rootOf("a"))
        assertFalse(index.qualifiesAsContainer("root"), "1 of 3 finished")
        index.close("b")
        assertTrue(index.qualifiesAsContainer("root"), "2 of 3 finished")
    }

    @Test
    fun `rootOf follows tracked parents only`() {
        val index = SubtreeIndex()
        index.add("root", null)
        index.add("a", "root")
        index.add("a1", "a")
        index.add("orphan", "never-seen")

        assertEquals("root", index.rootOf("a1"))
        assertEquals("orphan", index.rootOf("orphan"))
    }

    @Test
    fun `a coroutine naming itself as parent is its own root`() {
        val index = SubtreeIndex()
        index.add("x", "x")
        index.close("x")

        assertEquals("x", index.rootOf("x"))
        assertFalse(index.qualifiesAsContainer("x"))
    }

    @Test
    fun `a parent cycle is not linked`() {
        val index = SubtreeIndex()
        index.add("a", "b")
        index.add("b", "a")
        index.close("a")
        index.close("b")

        assertEquals("a", index.rootOf("a"))
        assertEquals("a", index.rootOf("b"))
    }

    @Test
    fun `closing a coroutine twice does not close its subtree early`() {
        val index = SubtreeIndex()
        index.add("root", null)
        listOf("a", "b", "c").forEach { index.add(it, "root") }
        index.add("a1", "a")
        index.close("a")
        index.close("a")
        index.close("b")

        assertFalse(index.qualifiesAsContainer("root"), "a1 is still open")
    }

    @Test
    fun `a closed child that gets a new descendant stops counting as finished`() {
        val index = SubtreeIndex()
        index.add("root", null)
        listOf("a", "b", "c").forEach { index.add(it, "root") }
        index.close("a")
        index.close("b")
        assertTrue(index.qualifiesAsContainer("root"))

        index.add("a2", "a")
        assertFalse(index.qualifiesAsContainer("root"))

        index.close("a2")
        assertTrue(index.qualifiesAsContainer("root"))
    }

    @Test
    fun `closing an unknown id does nothing and the id is open when added later`() {
        val index = SubtreeIndex()
        index.close("x")
        index.add("root", null)
        listOf("x", "a", "b", "c").forEach { index.add(it, "root") }
        index.close("a")

        assertFalse(index.qualifiesAsContainer("root"), "x is open: 1 of 4 finished")
    }

    @Test
    fun `a repeated add of the same id is ignored`() {
        val index = SubtreeIndex()
        index.add("root", null)
        index.add("a", "root")
        index.add("b", "root")
        index.add("a", "root")
        index.add("b", "a")
        index.close("a")
        index.close("b")

        assertEquals("root", index.rootOf("b"))
        assertFalse(index.qualifiesAsContainer("root"), "only two children")
    }
}
