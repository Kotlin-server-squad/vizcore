package com.jh.coroutinevisualizer.toolwindow

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewModeTest {
    @Test fun `interval is the live value in live mode`() {
        assertEquals(200L, ViewMode.intervalMsFor(ViewMode.LIVE, 200L))
    }

    @Test fun `interval drops to the fixed all cadence in all mode`() {
        assertEquals(1500L, ViewMode.intervalMsFor(ViewMode.ALL, 200L))
        assertEquals(ViewMode.ALL_INTERVAL_MS, ViewMode.intervalMsFor(ViewMode.ALL, 200L))
    }

    @Test fun `graph is enabled only in live mode`() {
        assertTrue(ViewMode.graphEnabled(ViewMode.LIVE))
        assertFalse(ViewMode.graphEnabled(ViewMode.ALL))
    }

    @Test fun `all label groups thousands with locale root`() {
        assertEquals("All 2,841", ViewMode.allLabel(2841))
    }
}
