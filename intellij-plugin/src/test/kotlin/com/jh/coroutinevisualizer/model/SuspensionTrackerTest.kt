package com.jh.coroutinevisualizer.model

import com.jh.coroutinevisualizer.api.HierarchyNodeDto
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SuspensionTrackerTest {
    private fun node(
        id: String,
        state: String,
    ) = HierarchyNodeDto(id = id, name = id, state = state)

    private val threshold = 30_000L * 1_000_000L

    @Test fun `id is flagged exactly at the threshold not before`() {
        val tracker = SuspensionTracker(threshold)
        val hierarchy = listOf(node("a", "SUSPENDED"))
        val t0 = 1_000_000L
        assertTrue(tracker.update(hierarchy, t0).isEmpty(), "not flagged at first sighting")
        assertTrue(tracker.update(hierarchy, t0 + threshold - 1).isEmpty(), "not flagged just before threshold")
        val flagged = tracker.update(hierarchy, t0 + threshold)
        assertEquals(mapOf("a" to t0), flagged, "flagged at threshold; value is firstSuspendedAt")
    }

    @Test fun `leaving SUSPENDED clears memory and re-entry restarts the clock`() {
        val tracker = SuspensionTracker(threshold)
        val t0 = 1_000_000L
        tracker.update(listOf(node("a", "SUSPENDED")), t0)
        // now RUNNING -> dropped from memory
        tracker.update(listOf(node("a", "RUNNING")), t0 + threshold)
        // re-enters SUSPENDED at a new time; clock restarts from tReenter
        val tReenter = t0 + threshold + 5
        tracker.update(listOf(node("a", "SUSPENDED")), tReenter)
        assertTrue(tracker.update(listOf(node("a", "SUSPENDED")), tReenter + threshold - 1).isEmpty())
        assertEquals(mapOf("a" to tReenter), tracker.update(listOf(node("a", "SUSPENDED")), tReenter + threshold))
    }

    @Test fun `id absent from hierarchy is pruned`() {
        val tracker = SuspensionTracker(threshold)
        val t0 = 1_000_000L
        tracker.update(listOf(node("a", "SUSPENDED")), t0)
        // a vanishes entirely -> pruned
        assertTrue(tracker.update(emptyList(), t0 + threshold).isEmpty())
        // re-appearing restarts the clock (not immediately flagged)
        assertTrue(tracker.update(listOf(node("a", "SUSPENDED")), t0 + threshold).isEmpty())
    }

    @Test fun `state comparison is case-insensitive`() {
        val tracker = SuspensionTracker(threshold)
        val t0 = 1_000_000L
        tracker.update(listOf(node("a", "suspended")), t0)
        assertEquals(mapOf("a" to t0), tracker.update(listOf(node("a", "suspended")), t0 + threshold))
    }
}
