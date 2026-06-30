package com.jh.coroutinevisualizer.toolwindow

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoroutineTreeStyleTest {
    @Test fun `maps backend states to style buckets case-insensitively`() {
        assertEquals(CoroutineStateStyle.RUNNING, CoroutineStateStyle.of("RUNNING"))
        assertEquals(CoroutineStateStyle.RUNNING, CoroutineStateStyle.of("running"))
        assertEquals(CoroutineStateStyle.SUSPENDED, CoroutineStateStyle.of("SUSPENDED"))
        assertEquals(CoroutineStateStyle.COMPLETED, CoroutineStateStyle.of("COMPLETED"))
        assertEquals(CoroutineStateStyle.FAILED, CoroutineStateStyle.of("FAILED"))
        assertEquals(CoroutineStateStyle.FAILED, CoroutineStateStyle.of("CANCELLED"))
        assertEquals(CoroutineStateStyle.CREATED, CoroutineStateStyle.of("CREATED"))
        assertEquals(CoroutineStateStyle.OTHER, CoroutineStateStyle.of("weird-unknown"))
    }

    @Test fun `badge text is the lowercased label`() {
        assertEquals("running", CoroutineStateStyle.badgeText("RUNNING"))
        assertEquals("suspended", CoroutineStateStyle.badgeText("suspended"))
    }

    @Test fun `flash tracker flashes only on state change`() {
        val tracker = FlashTracker()
        assertFalse(tracker.shouldFlash("a", "RUNNING"), "first sighting should not flash")
        assertFalse(tracker.shouldFlash("a", "RUNNING"), "same state should not flash")
        assertTrue(tracker.shouldFlash("a", "SUSPENDED"), "changed state should flash")
        assertFalse(tracker.shouldFlash("a", "SUSPENDED"), "same state again should not flash")
    }

    @Test fun `ageLabel renders approximate durations`() {
        assertEquals("~120ms", CoroutineStateStyle.ageLabel(120))
        assertEquals("~1.2s", CoroutineStateStyle.ageLabel(1200))
    }
}
