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
    ) = HierarchyNodeDto(id = id, parentId = null, name = id, scopeId = "sc", state = state, jobId = "j-$id")

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
}
