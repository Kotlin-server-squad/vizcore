package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.models.CoroutineState
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The summaries at a past seq, each known exactly or unknown (plan E-1). */
class SummaryBindingTest {
    private fun bind(
        script: EventScript,
        trimBefore: Long,
        atSeq: Long,
        fullSummaryTarget: Int = HealthThresholds.DEFAULT_FULL_SUMMARY_TARGET,
    ): Pair<BoundSummaries, SummaryView> {
        val durable = script.table(fullSummaryTarget = fullSummaryTarget)
        val retained = script.events.filter { it.seq >= trimBefore }
        val truth = SummaryTable(fullSummaryTarget).also { t -> script.events.filter { it.seq <= atSeq }.forEach(t::apply) }
        return SummaryBinding.bindAt(durable, retained, atSeq) to truth
    }

    @Test
    fun `without trimming every coroutine is known at every seq, and later ones are absent`() {
        val script =
            EventScript().apply {
                created("a", at = sec(1))
                started("a", at = sec(1))
                suspended("a", at = sec(2))
                created("b", at = sec(3), parent = "a")
                resumed("a", at = sec(4))
            }
        val (bound, truth) = bind(script, trimBefore = 1, atSeq = 3)
        assertTrue(bound.unknownIds.isEmpty())
        assertEquals(truth.get("a"), bound.view.get("a"))
        assertNull(bound.view.get("b"))
    }

    @Test
    fun `at the last seq the durable summaries are the view`() {
        val script = EventScript().apply { created("a", at = sec(1)) }
        val durable = script.table()
        val bound = SummaryBinding.bindAt(durable, script.events, atSeq = script.lastSeq)
        assertSame(durable, bound.view)
        assertTrue(bound.unknownIds.isEmpty())
    }

    @Test
    fun `a trimmed creation unchanged since the seq is known from the durable summary`() {
        val script =
            EventScript().apply {
                created("a", at = sec(1))
                started("a", at = sec(1))
                suspended("a", at = sec(2)) // seq 3, a's last change
                created("b", at = sec(3)) // seq 4
                started("b", at = sec(3))
            }
        val (bound, truth) = bind(script, trimBefore = 4, atSeq = 4)
        assertEquals(RebuildLevel.EXACT, bound.levelOf("a"))
        assertEquals(truth.get("a"), bound.view.get("a"))
        assertEquals(CoroutineState.SUSPENDED, bound.view.get("a")!!.state)
    }

    @Test
    fun `a coroutine finished by the seq and reduced afterwards is known, its details said to be dropped`() {
        val script =
            EventScript().apply {
                created("f", at = sec(1))
                started("f", at = sec(1))
                failed("f", at = sec(2)) // seq 3
                created("x", at = sec(3)) // seq 4: over the target of 1, f becomes a stub at seq 4
            }
        val (bound, truth) = bind(script, trimBefore = 2, atSeq = 3, fullSummaryTarget = 1)
        assertEquals(RebuildLevel.EXACT, bound.levelOf("f"))
        val b = assertNotNull(bound.view.get("f"))
        assertTrue(b.reduced)
        assertEquals(truth.get("f")!!.toStub().copy(lastModifiedSeq = b.lastModifiedSeq), b)
        assertTrue(b.failed)
    }

    @Test
    fun `a retained suspension does not rebuild a trimmed run start, so the coroutine is unknown`() {
        val script =
            EventScript().apply {
                created("a", at = sec(1))
                started("a", at = 100) // trimmed, later in app time than the retained suspension
                suspended("a", at = 3) // seq 3, retained
                resumed("a", at = sec(5)) // seq 4, after the cursor
            }
        val (bound, _) = bind(script, trimBefore = 3, atSeq = 3)
        assertEquals(RebuildLevel.UNKNOWN, bound.levelOf("a"))
    }

    @Test
    fun `a trimmed creation changed since the seq with only thread events retained is unknown`() {
        val script =
            EventScript().apply {
                created("a", at = sec(1))
                started("a", at = sec(1))
                thread("a", at = sec(2), name = "w-1") // seq 3
                thread("a", at = sec(3), name = "w-2") // seq 4
            }
        val (bound, _) = bind(script, trimBefore = 3, atSeq = 3)
        assertEquals(setOf("a"), bound.unknownIds)
    }

    @Test
    fun `the view's observation times include the known summaries' observations`() {
        val script =
            EventScript().apply {
                created("a", at = sec(1))
                started("a", at = sec(100)) // trimmed, the latest app time of the session
                suspended("a", at = sec(3)) // seq 3: a's last change
                created("b", at = sec(4)) // seq 4, the only retained event
            }
        val (bound, truth) = bind(script, trimBefore = 4, atSeq = 3)
        assertEquals(RebuildLevel.EXACT, bound.levelOf("a"))
        assertEquals(truth.latestEventAppNanos, bound.view.latestEventAppNanos)
        assertEquals(truth.earliestEventAppNanos, bound.view.earliestEventAppNanos)
    }

    @Test
    fun `every known summary equals the untrimmed history's at every trim point and seq`() {
        var knownTrimmed = 0
        var unknown = 0
        var knownReduced = 0
        repeat(HISTORIES) { n ->
            val random = Random(n)
            val script = randomHistory(random)
            for (fullSummaryTarget in listOf(HealthThresholds.DEFAULT_FULL_SUMMARY_TARGET, 2)) {
                val durable = script.table(fullSummaryTarget = fullSummaryTarget)
                for (trimBefore in 1..script.lastSeq + 1) {
                    val retained = script.events.filter { it.seq >= trimBefore }
                    for (s in 0..script.lastSeq) {
                        val truth = SummaryTable(fullSummaryTarget).also { t -> script.events.filter { it.seq <= s }.forEach(t::apply) }
                        val bound = SummaryBinding.bindAt(durable, retained, s)
                        val where = "history $n, target $fullSummaryTarget, trim $trimBefore, seq $s"
                        assertEquals(truth.all.map { it.id }.toSet(), bound.view.all.map { it.id }.toSet(), where)
                        for (t in truth.all) {
                            if (bound.levelOf(t.id) != RebuildLevel.EXACT) {
                                unknown++
                                continue
                            }
                            if (t.firstObserved.seq < trimBefore) knownTrimmed++
                            val b = bound.view.get(t.id)!!
                            if (b.reduced && !t.reduced) {
                                knownReduced++
                                assertEquals(t.toStub().copy(lastModifiedSeq = b.lastModifiedSeq), b, "${t.id}, $where")
                            } else {
                                assertEquals(t, b, "${t.id}, $where")
                            }
                        }
                        assertEquals(RoundAnchoring.of(truth).anchorIds, RoundAnchoring.of(bound.view).anchorIds, where)
                    }
                }
            }
        }
        // The histories exercise every branch: known despite a trimmed creation, unknown, and reduced after the seq.
        assertTrue(knownTrimmed > 0 && unknown > 0 && knownReduced > 0, "trimmed $knownTrimmed, unknown $unknown, reduced $knownReduced")
    }

    /**
     * A history of up to 8 coroutines under one or two roots: lifecycles with suspensions and thread assignments,
     * interleaved, some terminal, app times not in append order.
     */
    private fun randomHistory(random: Random): EventScript {
        val script = EventScript()
        val count = random.nextInt(3, 9)
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
            val step = pending[random.nextInt(pending.size)].removeFirst()
            t += random.nextLong(-ms(500), sec(1))
            step(t)
        }
        return script
    }

    private companion object {
        const val HISTORIES = 60
    }
}
