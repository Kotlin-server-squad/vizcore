package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.appJson
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals

/** A summary table checkpoints and restores to a table that goes on exactly as the original (spec C-4, plan Task 10). */
class SummaryTableStateTest {
    private fun roundTrip(state: SummaryTableState): SummaryTableState =
        appJson.decodeFromString(SummaryTableState.serializer(), appJson.encodeToString(SummaryTableState.serializer(), state))

    private fun assertSplitsEqual(
        script: EventScript,
        fullSummaryTarget: Int,
        trackedCapacity: Int,
    ) {
        val whole = SummaryTable(fullSummaryTarget, trackedCapacity).also { t -> script.events.forEach(t::apply) }
        for (k in 0..script.events.size) {
            val first = SummaryTable(fullSummaryTarget, trackedCapacity).also { t -> script.events.take(k).forEach(t::apply) }
            val restored = SummaryTable.restore(roundTrip(first.toState()), fullSummaryTarget, trackedCapacity)
            script.events.drop(k).forEach(restored::apply)
            assertEquals(whole.toState(), restored.toState(), "split at $k")
        }
    }

    @Test
    fun `a container root promoted before the split grows and is cleared after it as in the original`() {
        val script =
            EventScript().apply {
                created("app", at = sec(1))
                started("app", at = sec(1))
                for (i in 1..4) {
                    created("r$i", at = sec(1 + i.toLong()), parent = "app")
                    completed("r$i", at = sec(2 + i.toLong()))
                }
                created("late", at = sec(9), parent = "app") // after promotion: the root grew
                completed("late", at = sec(10))
                cancelled("app", at = sec(11)) // grew and was cancelled: stays a container
            }
        assertSplitsEqual(script, HealthThresholds.DEFAULT_FULL_SUMMARY_TARGET, HealthThresholds.DEFAULT_TRACKED_CAPACITY)
    }

    @Test
    fun `random histories split anywhere go on identically, with stubs, reduction and untracked coroutines`() {
        repeat(HISTORIES) { n ->
            val script = randomHistory(Random(n))
            assertSplitsEqual(script, fullSummaryTarget = 3, trackedCapacity = 10)
            assertSplitsEqual(script, HealthThresholds.DEFAULT_FULL_SUMMARY_TARGET, HealthThresholds.DEFAULT_TRACKED_CAPACITY)
        }
    }

    private fun randomHistory(random: Random): EventScript {
        val script = EventScript()
        val count = random.nextInt(4, 14)
        val ids = (0 until count).map { "c$it" }
        val parents = ids.mapIndexed { i, _ -> if (i < 2 || random.nextInt(4) == 0) null else ids[random.nextInt(i)] }
        val steps = ids.associateWith { ArrayDeque<(Long) -> Unit>() }
        for ((i, id) in ids.withIndex()) {
            val q = steps.getValue(id)
            q += { at -> script.created(id, at, parent = parents[i]) }
            q += { at -> script.started(id, at) }
            repeat(random.nextInt(0, 3)) {
                q += { at -> script.thread(id, at, name = "w-${random.nextInt(3)}") }
                q += { at -> script.suspended(id, at) }
                q += { at -> script.resumed(id, at) }
            }
            when (random.nextInt(4)) {
                0 -> q += { at -> script.completed(id, at) }
                1 -> q += { at -> script.failed(id, at) }
                2 -> q += { at -> script.cancelled(id, at) }
                else -> Unit
            }
        }
        var t = sec(1)
        while (steps.values.any { it.isNotEmpty() }) {
            val pending = steps.values.filter { it.isNotEmpty() }
            pending[random.nextInt(pending.size)].removeFirst()(t)
            t += random.nextLong(-ms(500), sec(1))
        }
        return script
    }

    private companion object {
        const val HISTORIES = 80
    }
}
