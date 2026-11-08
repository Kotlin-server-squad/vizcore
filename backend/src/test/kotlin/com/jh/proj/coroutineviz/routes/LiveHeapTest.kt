package com.jh.proj.coroutineviz.routes

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LiveHeapTest {
    @Test
    fun `a collected pool counts what its last collection left, not the garbage since`() {
        // An old generation holding 900 MB right now, of which its last collection kept 100 MB.
        val pools = listOf(HeapPool(usedBytes = 900, usedAfterLastCollectionBytes = 100))

        assertEquals(100, liveHeapBytes(pools))
    }

    @Test
    fun `a pool that reports no collection usage counts what it holds now`() {
        val pools = listOf(HeapPool(usedBytes = 300, usedAfterLastCollectionBytes = null))

        assertEquals(300, liveHeapBytes(pools))
    }

    @Test
    fun `the pools add up`() {
        val pools =
            listOf(
                HeapPool(usedBytes = 400, usedAfterLastCollectionBytes = 0), // eden right after a young collection
                HeapPool(usedBytes = 20, usedAfterLastCollectionBytes = 20), // survivor
                HeapPool(usedBytes = 700, usedAfterLastCollectionBytes = 250), // old generation
            )

        assertEquals(270, liveHeapBytes(pools))
    }

    @Test
    fun `the percentage is of the maximum heap, and zero without one`() {
        assertEquals(25.0, heapPercent(usedBytes = 256, maxBytes = 1024))
        assertEquals(0.0, heapPercent(usedBytes = 256, maxBytes = 0))
    }
}
