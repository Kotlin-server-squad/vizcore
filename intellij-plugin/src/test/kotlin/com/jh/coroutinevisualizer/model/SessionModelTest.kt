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
        exceptionType: String? = null,
    ) = HierarchyNodeDto(
        id = id,
        parentId = parentId,
        name = id,
        scopeId = "sc",
        state = state,
        completedAtNanos = completedAtNanos,
        exceptionType = exceptionType,
        jobId = "j-$id",
    )

    @Test fun `tiles map active throughput leaks and peak from metrics`() {
        val hierarchy = listOf(node("a", "RUNNING"), node("b", "SUSPENDED"), node("c", "RUNNING"))
        val metrics =
            MetricsDto(
                active = 2,
                peak = 3,
                throughputPerSec = 12.34,
                leaks = listOf(LeakDto(coroutineId = "b", aliveMs = 12_400)),
                leakThresholdMs = 10_000,
            )
        val model = SessionModel.from(hierarchy, metrics)
        assertEquals(2, model.tiles.active)
        assertEquals(12.34, model.tiles.throughputPerSec)
        assertEquals(1, model.tiles.leaks)
        assertEquals(3, model.tiles.peak)
        assertEquals(setOf("b"), model.leakIds)
    }

    @Test fun `null metrics falls back to running count and zeros`() {
        val model = SessionModel.from(listOf(node("a", "RUNNING"), node("b", "RUNNING")), null)
        assertEquals(2, model.tiles.active) // RUNNING count
        assertEquals(0.0, model.tiles.throughputPerSec)
        assertEquals(0, model.tiles.leaks)
        assertEquals(0, model.tiles.peak)
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

    @Test fun `fullHierarchy is the unfiltered input regardless of live filtering`() {
        val now = 1_000_000_000_000L
        val oldCompleted = now - 60_000L * 1_000_000L
        val hierarchy =
            listOf(
                node("a", "RUNNING"),
                node("b", "COMPLETED", completedAtNanos = oldCompleted),
            )
        val model = SessionModel.from(hierarchy, null, nowNanos = now)
        assertEquals(2, model.fullHierarchy.size)
        assertEquals(1, model.hierarchy.size) // b is filtered out of the live view
    }

    @Test fun `failed node is pinned past the completed window and listed as a problem`() {
        val now = 1_000_000_000_000L
        val oldCompleted = now - 10_000L * 1_000_000L // 10s ago, outside the 5s window
        val hierarchy =
            listOf(
                node("root", "RUNNING"),
                node(
                    "failed",
                    "FAILED",
                    parentId = "root",
                    completedAtNanos = oldCompleted,
                    exceptionType = "java.lang.IllegalStateException",
                ),
            )
        val model = SessionModel.from(hierarchy, null, nowNanos = now)
        val ids = model.hierarchy.map { it.id }.toSet()
        assertTrue("failed" in ids, "exception node pinned past the window (D-12)")
        assertTrue("root" in ids, "its ancestor retained (no orphan)")
        assertTrue(
            model.problems.any { it.coroutineId == "failed" && it.category == ProblemCategory.EXCEPTION },
            "failed node listed as an EXCEPTION problem",
        )
    }

    @Test fun `long-suspended id is flagged as a problem and pinned`() {
        val now = 40_000_000_000L
        val hierarchy = listOf(node("s", "SUSPENDED"))
        val model = SessionModel.from(hierarchy, null, nowNanos = now, longSuspended = mapOf("s" to 0L))
        assertTrue(
            model.problems.any { it.coroutineId == "s" && it.category == ProblemCategory.LONG_SUSPENDED },
            "s flagged as LONG_SUSPENDED",
        )
        assertTrue("s" in model.hierarchy.map { it.id }, "s pinned into the live view")
    }

    @Test fun `filterToProblemCategory keeps only that category plus ancestors in input order`() {
        val now = 1_000_000_000_000L
        val hierarchy =
            listOf(
                node("root", "RUNNING"),
                node("ex", "FAILED", parentId = "root", exceptionType = "java.lang.IllegalStateException"),
                node("leaf", "RUNNING", parentId = "root"),
            )
        val metrics = MetricsDto(leaks = listOf(LeakDto(coroutineId = "leaf", aliveMs = 1_000)))
        val model = SessionModel.from(hierarchy, metrics, nowNanos = now)
        val exNodes = SessionModel.filterToProblemCategory(hierarchy, model.problems, ProblemCategory.EXCEPTION)
        assertEquals(listOf("root", "ex"), exNodes.map { it.id }, "exception node + ancestor, input order preserved")
    }
}
