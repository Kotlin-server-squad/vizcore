package com.jh.coroutinevisualizer.toolwindow

import com.jh.coroutinevisualizer.model.SessionTiles
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MetricTilesTest {
    @Test fun `tile values reflect the session tiles in order`() {
        val tiles = SessionTiles(active = 3, throughputPerSec = 12.34, leaks = 2, peak = 9)
        val values = MetricTilesPanel.tileValues(tiles)
        assertEquals(
            listOf("Active" to "3", "Throughput" to "12.3/s", "Leaks" to "2", "Peak" to "9"),
            values,
        )
    }
}
