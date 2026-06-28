package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.VizEvent
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves [StructuralAwareBuffer] is a two-lane bounded shedding buffer (D-07/D-08):
 * - structural (lifecycle) events are NEVER dropped under flood (Pitfall P5)
 * - non-structural events drop-oldest when the sheddable lane exceeds capacity
 * - every sheddable drop increments an observable AtomicLong drop counter (D-08)
 * - the sheddable lane is bounded (no OOM) — capacity mirrors the 10_000 shape
 * - draining yields lifecycle-lane events without starving them
 * - protection delegates to [StructuralClassifier.isStructural] (shared spine, no reinvention)
 */
class StructuralAwareBufferTest {
    private data class TestEvent(
        override val kind: String,
        override var seq: Long,
        override val sessionId: String = "s",
        override val tsNanos: Long = 0L,
    ) : VizEvent

    private fun structural(seq: Long) = TestEvent("CoroutineCreated", seq)

    private fun sheddable(seq: Long) = TestEvent("FlowValueEmitted", seq)

    @Test
    fun `routing delegates protection to StructuralClassifier`() {
        // A known structural kind and a known sheddable kind must classify as the spine says.
        assertTrue(StructuralClassifier.isStructural(structural(0).kind))
        assertTrue(!StructuralClassifier.isStructural(sheddable(0).kind))
    }

    @Test
    fun `every structural event survives a flood that sheds non-structural`() = runTest {
        val shedCapacity = 8
        val buffer = StructuralAwareBuffer(shedCapacity = shedCapacity)

        // Interleave a small number of structural events among a flood of sheddable noise
        // that far exceeds the sheddable lane capacity.
        val structuralSeqs = mutableListOf<Long>()
        var seq = 0L
        repeat(100) { i ->
            if (i % 10 == 0) {
                val s = seq++
                structuralSeqs.add(s)
                buffer.offer(structural(s))
            }
            buffer.offer(sheddable(seq++))
        }
        buffer.close()

        val drained = buffer.stream().toList()

        // EVERY structural event must be present — none shed under flood (Pitfall P5).
        val drainedStructuralSeqs = drained.filter { StructuralClassifier.isStructural(it.kind) }.map { it.seq }
        assertEquals(
            structuralSeqs.toSet(),
            drainedStructuralSeqs.toSet(),
            "all structural (lifecycle) events must survive the flood",
        )

        // The drop counter recorded the shed non-structural events.
        assertTrue(buffer.dropped > 0, "non-structural overflow must increment the drop counter (D-08)")

        // Bounded: far fewer sheddable events survived than were offered (no unbounded retention).
        val survivingSheddable = drained.count { !StructuralClassifier.isStructural(it.kind) }
        assertTrue(
            survivingSheddable <= shedCapacity,
            "sheddable survivors ($survivingSheddable) must stay within capacity ($shedCapacity) — no OOM",
        )
    }

    @Test
    fun `non-structural drop-oldest keeps the newest events`() = runTest {
        val shedCapacity = 4
        val buffer = StructuralAwareBuffer(shedCapacity = shedCapacity)

        // Offer more sheddable events than capacity; oldest should be dropped.
        repeat(10) { i -> buffer.offer(sheddable(i.toLong())) }
        buffer.close()

        val drained = buffer.stream().toList().map { it.seq }

        assertEquals(6, buffer.dropped, "10 offered into capacity-4 lane drops the 6 oldest (D-08)")
        // The surviving seqs must be the NEWEST (drop-oldest), bounded by capacity.
        assertTrue(drained.all { it >= 6 }, "drop-oldest must retain the newest events, got $drained")
        assertTrue(drained.size <= shedCapacity, "no more than capacity survive")
    }

    @Test
    fun `no drops when sheddable load stays within capacity`() = runTest {
        val buffer = StructuralAwareBuffer(shedCapacity = 16)
        repeat(5) { i -> buffer.offer(sheddable(i.toLong())) }
        repeat(3) { i -> buffer.offer(structural((100 + i).toLong())) }
        buffer.close()

        val drained = buffer.stream().toList()

        assertEquals(0, buffer.dropped, "below capacity nothing is dropped")
        assertEquals(8, drained.size, "all 8 events delivered when within capacity")
    }

    @Test
    fun `drain yields lifecycle-lane events first each tick (never starved)`() = runTest {
        val buffer = StructuralAwareBuffer(shedCapacity = 64)

        // Offer sheddable first, then a structural — the structural must not wait behind a
        // backlog of noise.
        repeat(20) { i -> buffer.offer(sheddable(i.toLong())) }
        buffer.offer(structural(1000))
        buffer.close()

        // Take just the first drained element: it should be the prioritized structural one.
        val firstFew = buffer.stream().take(1).toList()
        assertTrue(
            firstFew.isNotEmpty() && StructuralClassifier.isStructural(firstFew.first().kind),
            "lifecycle lane drains first — structural not starved behind noise",
        )
    }
}
