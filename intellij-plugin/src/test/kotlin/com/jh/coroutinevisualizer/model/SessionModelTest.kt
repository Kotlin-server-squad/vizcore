package com.jh.coroutinevisualizer.model

import com.jh.coroutinevisualizer.api.HierarchyNodeDto
import com.jh.coroutinevisualizer.api.LeakDto
import com.jh.coroutinevisualizer.api.MetricsDto
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionModelTest {
    private fun node(
        id: String,
        state: String,
        parentId: String? = null,
        completedAtNanos: Long? = null,
    ) = HierarchyNodeDto(
        id = id,
        parentId = parentId,
        name = id,
        scopeId = "sc",
        state = state,
        completedAtNanos = completedAtNanos,
        jobId = "j-$id",
    )

    @Test fun `maps tiles and leak set from hierarchy and metrics`() {
        val hierarchy = listOf(node("a", "RUNNING"), node("b", "SUSPENDED"), node("c", "RUNNING"), node("d", "COMPLETED"))
        val metrics =
            MetricsDto(
                active = 2,
                peak = 3,
                dispatcherUtilization = mapOf("IO" to 1, "Default" to 2),
                leaks = listOf(LeakDto(coroutineId = "b", aliveMs = 12400)),
                leakThresholdMs = 10000,
            )
        val model = SessionModel.from(hierarchy, metrics)
        assertEquals(4, model.tiles.total)
        assertEquals(2, model.tiles.active) // RUNNING count
        assertEquals(1, model.tiles.suspended) // SUSPENDED count
        assertEquals(1, model.tiles.leakRisk) // leaks.size
        assertEquals(2, model.tiles.dispatchers) // dispatcherUtilization.size
        assertEquals(setOf("b"), model.leakIds)
        assertTrue("b" in model.leakIds)
    }

    @Test fun `null metrics yields empty leaks and zero dispatchers`() {
        val model = SessionModel.from(listOf(node("a", "RUNNING")), null)
        assertEquals(1, model.tiles.total)
        assertEquals(1, model.tiles.active)
        assertEquals(0, model.tiles.leakRisk)
        assertEquals(0, model.tiles.dispatchers)
        assertTrue(model.leakIds.isEmpty())
    }

    @Test fun `live view drops old-completed coroutines but keeps running ones`() {
        val now = 1_000_000_000_000L
        val oldCompleted = now - 60_000L * 1_000_000L // 60s ago, well outside window
        val hierarchy =
            listOf(
                node("a", "RUNNING"),
                node("b", "COMPLETED", completedAtNanos = oldCompleted),
                node("c", "RUNNING"),
                node("d", "COMPLETED", completedAtNanos = oldCompleted),
            )
        val model = SessionModel.from(hierarchy, null, nowNanos = now)
        assertEquals(setOf("a", "c"), model.hierarchy.map { it.id }.toSet())
        assertEquals(4, model.tiles.total) // tiles still reflect FULL input
    }

    @Test fun `recently-completed coroutine within window is retained`() {
        val now = 1_000_000_000_000L
        val recentlyCompleted = now - 2_000L * 1_000_000L // 2s ago, inside 5s window
        val hierarchy =
            listOf(
                node("a", "RUNNING"),
                node("b", "COMPLETED", completedAtNanos = recentlyCompleted),
            )
        val model = SessionModel.from(hierarchy, null, nowNanos = now)
        assertTrue("b" in model.hierarchy.map { it.id }, "recently-completed node b should be retained")
    }

    @Test fun `ancestor closure keeps old-completed parent of a retained child`() {
        val now = 1_000_000_000_000L
        val oldCompleted = now - 60_000L * 1_000_000L
        val hierarchy =
            listOf(
                node("parent", "COMPLETED", completedAtNanos = oldCompleted),
                node("child", "RUNNING", parentId = "parent"),
            )
        val model = SessionModel.from(hierarchy, null, nowNanos = now)
        val ids = model.hierarchy.map { it.id }.toSet()
        assertTrue("parent" in ids, "old-completed parent of a running child must be retained (no orphan)")
        assertTrue("child" in ids)
    }

    @Test fun `tiles counts are computed from full input regardless of filtering`() {
        val now = 1_000_000_000_000L
        val oldCompleted = now - 60_000L * 1_000_000L
        val hierarchy =
            listOf(
                node("a", "RUNNING"),
                node("b", "SUSPENDED"),
                node("c", "RUNNING"),
                node("d", "COMPLETED", completedAtNanos = oldCompleted),
                node("e", "COMPLETED", completedAtNanos = oldCompleted),
            )
        val model = SessionModel.from(hierarchy, null, nowNanos = now)
        assertEquals(5, model.tiles.total)
        assertEquals(2, model.tiles.active)
        assertEquals(1, model.tiles.suspended)
    }
}
