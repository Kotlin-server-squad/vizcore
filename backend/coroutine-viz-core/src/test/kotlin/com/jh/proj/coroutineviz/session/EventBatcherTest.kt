package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.VizEvent
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves [EventBatcher] is a count-OR-time-window hybrid flush collector (D-04/D-05):
 * - flushes when the pending list reaches BATCH_COUNT (count trigger)
 * - flushes when BATCH_WINDOW_MS elapses since the first pending element (time trigger),
 *   whichever fires first
 * - emits size-1 batches below load so the SSE wire stays byte-identical (D-04)
 * - never drops an event (total emitted across batches == total received)
 * - never emits an empty batch
 *
 * Time-window behavior is driven deterministically by [runTest] virtual time.
 */
class EventBatcherTest {
    /** Minimal pure-JVM test event — avoids constructing heavyweight serialized subclasses. */
    private data class TestEvent(
        override val kind: String,
        override var seq: Long,
        override val sessionId: String = "s",
        override val tsNanos: Long = 0L,
    ) : VizEvent

    private fun event(seq: Long, kind: String = "FlowValueEmitted") = TestEvent(kind, seq)

    @Test
    fun `count trigger flushes at BATCH_COUNT producing batches no larger than count`() = runTest {
        val total = 125
        val count = 50
        val upstream = flow {
            for (i in 0 until total) emit(event(i.toLong()))
        }

        val batches = EventBatcher.batched(upstream, count = count, windowMs = 100).toList()

        // None exceed count.
        assertTrue(batches.all { it.size <= count }, "no batch may exceed BATCH_COUNT")
        // ceil(125/50) == 3 batches.
        assertEquals(3, batches.size, "burst of N>count should yield ceil(N/count) batches")
        // No event lost — total preserved.
        assertEquals(total, batches.sumOf { it.size }, "total emitted must equal total received")
        // None empty.
        assertTrue(batches.none { it.isEmpty() }, "batcher must never emit an empty batch")
    }

    @Test
    fun `time-window trigger flushes a partial batch when count is not reached`() = runTest {
        val count = 50
        val windowMs = 100L
        // Emit 3 events, then idle past the window so the time trigger fires.
        val upstream = flow {
            emit(event(0))
            emit(event(1))
            emit(event(2))
            // No further emissions — the window timer must flush the partial batch.
            kotlinx.coroutines.delay(windowMs * 5)
        }

        val batches = EventBatcher.batched(upstream, count = count, windowMs = windowMs).toList()

        assertEquals(3, batches.sumOf { it.size }, "all 3 events must be flushed by the time trigger")
        assertTrue(batches.none { it.isEmpty() }, "no empty batches even when idle")
        // The partial batch was < count yet still flushed (proves time, not count, triggered it).
        assertTrue(batches.first().size < count, "partial batch flushed before reaching count")
    }

    @Test
    fun `a slow stream of one event per window yields singleton batches (D-04 back-compat)`() = runTest {
        val windowMs = 100L
        val upstream = flow {
            emit(event(0))
            kotlinx.coroutines.delay(windowMs * 2)
            emit(event(1))
            kotlinx.coroutines.delay(windowMs * 2)
            emit(event(2))
            kotlinx.coroutines.delay(windowMs * 2)
        }

        val batches = EventBatcher.batched(upstream, count = 50, windowMs = windowMs).toList()

        assertEquals(3, batches.size, "one event per window should produce 3 singleton batches")
        assertTrue(batches.all { it.size == 1 }, "below load the batcher emits size-1 frames (D-04)")
        assertEquals(3, batches.sumOf { it.size })
    }

    @Test
    fun `an idle stream emits no batches (never an empty flush)`() = runTest {
        val upstream = flow<VizEvent> {
            kotlinx.coroutines.delay(1_000)
        }

        val batches = EventBatcher.batched(upstream, count = 50, windowMs = 100).toList()

        assertTrue(batches.isEmpty(), "an idle stream must produce zero batches, never an empty one")
    }

    @Test
    fun `total events preserved across a mixed burst-then-trickle stream`() = runTest {
        val count = 10
        val windowMs = 50L
        val upstream = flow {
            // Burst that overflows count.
            for (i in 0 until 23) emit(event(i.toLong()))
            // Trickle that relies on the window trigger.
            kotlinx.coroutines.delay(windowMs * 2)
            emit(event(100))
            kotlinx.coroutines.delay(windowMs * 2)
        }

        val batches = EventBatcher.batched(upstream, count = count, windowMs = windowMs).toList()

        assertEquals(24, batches.sumOf { it.size }, "no event dropped across burst+trickle")
        assertTrue(batches.all { it.size in 1..count }, "every batch is 1..count, never empty, never oversized")
    }
}
