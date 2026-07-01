package com.jh.coroutinevisualizer.toolwindow

import com.jh.coroutinevisualizer.model.SessionTiles
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MetricTilesTest {
    @Test fun `tile values reflect the session tiles in order`() {
        val tiles = SessionTiles(total = 21, active = 6, suspended = 3, leakRisk = 1, dispatchers = 2)
        val values = MetricTilesPanel.tileValues(tiles)
        assertEquals(listOf("Coroutines" to "21", "Active" to "6", "Suspended" to "3", "Leak risk" to "1", "Dispatchers" to "2"), values)
    }
}
