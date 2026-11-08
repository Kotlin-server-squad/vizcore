package com.jh.proj.coroutineviz.health

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The summary table's two limits as one value, and every way a table is built honouring them (spec C-2). */
class SummaryLimitsTest {
    private val small = SummaryLimits(fullSummaryTarget = 3, trackedCapacity = 3)

    /** Four coroutines, each created, started and completed in turn: seqs 1..12, the 4th created at seq 10. */
    private fun fourCoroutines(): EventScript =
        EventScript("limits").apply {
            for (i in 1..4) {
                created("c$i", at = sec(i.toLong()))
                started("c$i", at = sec(i.toLong()))
                completed("c$i", at = sec(i.toLong()) + ms(500))
            }
        }

    @Test
    fun `the defaults are the spec's 50 000 full summaries and 100 000 tracked coroutines`() {
        assertEquals(50_000, SummaryLimits.DEFAULT.fullSummaryTarget)
        assertEquals(100_000, SummaryLimits.DEFAULT.trackedCapacity)
        assertEquals(HealthThresholds.DEFAULT_FULL_SUMMARY_TARGET, SummaryLimits().fullSummaryTarget)
        assertEquals(HealthThresholds.DEFAULT_TRACKED_CAPACITY, SummaryLimits().trackedCapacity)
    }

    @Test
    fun `both limits are positive and the target is at most the capacity`() {
        assertThrows<IllegalArgumentException> { SummaryLimits(fullSummaryTarget = 0, trackedCapacity = 10) }
        assertThrows<IllegalArgumentException> { SummaryLimits(fullSummaryTarget = 5, trackedCapacity = -1) }
        assertThrows<IllegalArgumentException> { SummaryLimits(fullSummaryTarget = 11, trackedCapacity = 10) }
        assertEquals(10, SummaryLimits(fullSummaryTarget = 10, trackedCapacity = 10).trackedCapacity)
    }

    @Test
    fun `a table built from limits counts the coroutine beyond capacity as untracked`() {
        val table = SummaryTable(small).also { t -> fourCoroutines().events.forEach(t::apply) }
        assertEquals(listOf("c1", "c2", "c3"), table.all.map { it.id })
        assertEquals(1L, table.untrackedCount)
    }

    @Test
    fun `the replay fold uses the limits it is given`() {
        val script = fourCoroutines()
        assertEquals(1L, SummaryFold.foldTo(script.events, script.lastSeq, durable = null, limits = small).untrackedCount)
        assertEquals(0L, SummaryFold.foldTo(script.events, script.lastSeq).untrackedCount, "defaults unchanged")
    }

    @Test
    fun `the replay fold seeded from durable summaries uses the limits it is given`() {
        val script = fourCoroutines()
        val durable = script.table(fullSummaryTarget = 3, trackedCapacity = 3)
        // c1..c3 were created before the retained events (seqs 10..12) and come from the seed.
        val bound = SummaryBinding.bindAt(durable, script.events.drop(9), atSeq = 10, limits = small)
        assertEquals(1L, bound.view.untrackedCount, "the 4th creation (seq 10) is beyond capacity in the replay too")
    }

    @Test
    fun `a checkpoint restored with limits goes on with them`() {
        val script = fourCoroutines()
        val first = SummaryTable(small).also { t -> script.events.take(9).forEach(t::apply) }
        val restored = SummaryTable.restore(first.toState(), small)
        script.events.drop(9).forEach(restored::apply)
        assertEquals(1L, restored.untrackedCount)
        assertEquals(3, restored.all.size)
    }

    @Test
    fun `restored under a smaller capacity it keeps every summary and stub it had and tracks no more`() {
        // Saved under capacity 5, target 2: c1..c3 finished, so c1 is a stub; c4 and c5 running.
        val script =
            EventScript("shrink").apply {
                for (i in 1..3) {
                    created("c$i", at = sec(i.toLong()))
                    completed("c$i", at = sec(i.toLong()) + ms(1))
                }
                created("c4", at = sec(4))
                created("c5", at = sec(5))
            }
        val saved = SummaryTable(SummaryLimits(fullSummaryTarget = 2, trackedCapacity = 5)).also { t -> script.events.forEach(t::apply) }
        val stubsBefore = saved.all.filter { it.reduced }.map { it.id }
        assertTrue(stubsBefore.isNotEmpty())

        val restored = SummaryTable.restore(saved.toState(), SummaryLimits(fullSummaryTarget = 1, trackedCapacity = 3))
        assertEquals(saved.toState(), restored.toState(), "restoring changes nothing by itself")

        script.created("c6", at = sec(6))
        script.completed("c4", at = sec(7))
        script.events.takeLast(2).forEach(restored::apply)
        assertEquals(listOf("c1", "c2", "c3", "c4", "c5"), restored.all.map { it.id }, "nothing dropped, nothing added")
        assertEquals(1L, restored.untrackedCount, "c6 is beyond the new capacity")
        assertTrue(restored.all.filter { it.id in stubsBefore }.all { it.reduced }, "stubs stay stubs")
        // The next terminal reduces finished summaries down to the new target (only running c5 stays full).
        assertEquals(listOf("c5"), restored.all.filterNot { it.reduced }.map { it.id })
    }

    @Test
    fun `restored under a larger capacity a table that overflowed stays closed, so health still covers the first ones`() {
        val script = fourCoroutines()
        val saved = SummaryTable(small).also { t -> script.events.forEach(t::apply) }
        assertEquals(1L, saved.untrackedCount)

        val restored = SummaryTable.restore(saved.toState(), SummaryLimits(fullSummaryTarget = 10, trackedCapacity = 10))
        script.created("c5", at = sec(10))
        restored.apply(script.events.last())
        assertEquals(listOf("c1", "c2", "c3"), restored.all.map { it.id })
        assertEquals(2L, restored.untrackedCount)
    }

    @Test
    fun `restored under a larger capacity a table that had room tracks up to the new capacity`() {
        val script = fourCoroutines()
        val saved = SummaryTable(small).also { t -> script.events.take(6).forEach(t::apply) }
        assertEquals(2, saved.all.size)

        val restored = SummaryTable.restore(saved.toState(), SummaryLimits(fullSummaryTarget = 10, trackedCapacity = 10))
        script.events.drop(6).forEach(restored::apply)
        assertEquals(4, restored.all.size)
        assertEquals(0L, restored.untrackedCount)
    }

    @Test
    fun `restored under a smaller target with every coroutine finished, the next creation reduces them even when beyond capacity`() {
        val script =
            EventScript("closed").apply {
                for (i in 1..5) {
                    created("c$i", at = sec(i.toLong()))
                    completed("c$i", at = sec(i.toLong()) + ms(1))
                }
            }
        val saved = SummaryTable(SummaryLimits(fullSummaryTarget = 10, trackedCapacity = 10)).also { t -> script.events.forEach(t::apply) }
        val restored = SummaryTable.restore(saved.toState(), SummaryLimits(fullSummaryTarget = 1, trackedCapacity = 3))
        script.created("c6", at = sec(6))
        restored.apply(script.events.last())
        assertEquals(1L, restored.untrackedCount)
        assertEquals(listOf("c5"), restored.all.filterNot { it.reduced }.map { it.id }, "down to the new target, newest kept")
    }

    @Test
    fun `a replay admits exactly the coroutines the durable table tracked, whatever its limits are now`() {
        // Live: capacity 3 overflowed at c4, then restored under capacity 10 (still closed): c5 is untracked too.
        val script = fourCoroutines()
        val saved = SummaryTable(small).also { t -> script.events.forEach(t::apply) }
        val durable = SummaryTable.restore(saved.toState(), SummaryLimits(fullSummaryTarget = 10, trackedCapacity = 10))
        script.created("c5", at = sec(10))
        script.started("c1", at = sec(11))
        script.events.takeLast(2).forEach(durable::apply)
        val larger = SummaryLimits(fullSummaryTarget = 10, trackedCapacity = 10)
        val atC5 = script.lastSeq - 1
        val bound = SummaryBinding.bindAt(durable, script.events.drop(6), atC5, larger)
        assertEquals(listOf("c1", "c2", "c3"), bound.view.all.map { it.id })
        assertEquals(2L, bound.view.untrackedCount, "c4 and c5, as live")

        // Restored with five tracked under capacity 3: the replay keeps all five.
        val five =
            EventScript("five").apply {
                for (i in 1..5) created("c$i", at = sec(i.toLong()))
                started("c1", at = sec(6))
            }
        val wide = SummaryTable(SummaryLimits(fullSummaryTarget = 5, trackedCapacity = 5)).also { t -> five.events.forEach(t::apply) }
        val narrowed = SummaryTable.restore(wide.toState(), small)
        val replay = SummaryBinding.bindAt(narrowed, five.events.drop(2), atSeq = 5, limits = small)
        assertEquals(listOf("c1", "c2", "c3", "c4", "c5"), replay.view.all.map { it.id })
        assertEquals(0L, replay.view.untrackedCount)
    }
}
