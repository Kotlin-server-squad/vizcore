package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.models.CoroutineState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SummaryFoldSeedTest {
    private fun replay(
        view: SummaryView,
        atSeq: Long,
    ): SessionHealth =
        SessionHealthDerivation.derive(HealthInput(summaries = view, kind = SessionKind.ATTACHED, mode = HealthMode.Replay(atSeq)))

    @Test
    fun `a failure whose ancestry was trimmed keeps its round`() {
        val script = EventScript()
        script.created("req", at = sec(1))
        script.created("a", at = sec(1), parent = "req")
        script.started("a", at = sec(2))
        script.failed("a", at = sec(3))
        val durable = script.table()
        val retained = script.events.drop(2)

        val unseeded = replay(SummaryFold.foldTo(retained, script.lastSeq), script.lastSeq)
        val seeded = replay(SummaryFold.foldTo(retained, script.lastSeq, durable), script.lastSeq)

        assertEquals(0, unseeded.problemCount, "without the seed, a's creation is gone and so is its failure")
        val round = assertIs<FailedRoundIncident>(seeded.incidents.items.single())
        assertEquals("req", round.anchorId)
        assertEquals("java.lang.IllegalStateException", round.failures.items.single().exceptionType)
    }

    @Test
    fun `a trimmed terminal counts only from its own seq`() {
        val script = EventScript()
        script.created("a", at = sec(1))
        val created = script.lastSeq
        script.failed("a", at = sec(2))
        val failed = script.lastSeq
        script.created("b", at = sec(3))
        val durable = script.table()
        val retained = script.events.drop(2)

        assertEquals(0, replay(SummaryFold.foldTo(retained, created, durable), created).problemCount)
        assertEquals(1, replay(SummaryFold.foldTo(retained, failed, durable), failed).problemCount)
        assertEquals(1, replay(SummaryFold.foldTo(retained, script.lastSeq, durable), script.lastSeq).problemCount)
    }

    @Test
    fun `nothing created after the cursor is seeded`() {
        val script = EventScript()
        script.created("a", at = sec(1))
        script.created("b", at = sec(2))
        script.started("b", at = sec(3))
        val durable = script.table()

        val view = SummaryFold.foldTo(script.events.drop(2), atSeq = 1, durable = durable)

        assertTrue(view.get("a") != null)
        assertNull(view.get("b"))
        assertEquals(1L, view.lastEventSeq)
    }

    @Test
    fun `states between a trimmed creation and the retained events are not invented`() {
        val script = EventScript()
        script.created("a", at = sec(1))
        script.suspended("a", at = sec(2))
        script.resumed("a", at = sec(3))
        val durable = script.table()

        val view = SummaryFold.foldTo(script.events.drop(3), atSeq = 2, durable = durable)

        assertEquals(CoroutineState.CREATED, view.get("a")!!.state)
    }

    @Test
    fun `a stub's failure is seeded with its details dropped`() {
        val script = EventScript()
        script.created("a", at = sec(1))
        script.failed("a", at = sec(2))
        script.created("b", at = sec(3))
        val durable = script.table(fullSummaryTarget = 0)

        val health = replay(SummaryFold.foldTo(script.events.drop(2), script.lastSeq, durable), script.lastSeq)

        val failure = assertIs<FailedRoundIncident>(health.incidents.items.single()).failures.items.single()
        assertTrue(failure.detailsDropped)
        assertNull(failure.exceptionType)
    }

    /**
     * Round anchoring and failures depend only on creations and terminals, which the seed keeps: for every cut of the
     * history and every cursor, a seeded fold of what is left anchors and counts exactly like a fold of everything.
     */
    @Test
    fun `a seeded fold anchors and counts like the full history, at every cut and cursor`() {
        val script = EventScript()
        script.created("app", at = sec(1))
        for (i in 1L..4L) {
            script.created("r$i", at = sec(1 + i), parent = "app")
            script.created("r$i-x", at = sec(1 + i), parent = "r$i")
            if (i == 2L) script.failed("r$i-x", at = sec(2 + i)) else script.completed("r$i-x", at = sec(2 + i))
            if (i == 2L) script.cancelled("r$i", at = sec(2 + i)) else script.completed("r$i", at = sec(2 + i))
        }
        script.created("late", at = sec(9), parent = "app")
        script.failed("late", at = sec(10))
        script.cancelled("app", at = sec(11))
        val durable = script.table()
        val ids = durable.all.map { it.id }

        for (cut in 0..script.events.size) {
            val retained = script.events.drop(cut)
            for (cursor in 0..script.lastSeq) {
                val full = SummaryFold.foldTo(script.events, cursor)
                val seeded = SummaryFold.foldTo(retained, cursor, durable)
                val fullAnchors = RoundAnchoring.of(full)
                val seededAnchors = RoundAnchoring.of(seeded)
                for (id in ids) {
                    assertEquals(fullAnchors.anchorOf(id), seededAnchors.anchorOf(id), "anchor of $id, cut $cut, cursor $cursor")
                }
                assertEquals(
                    replay(full, cursor).incidents.items.map { it.id },
                    replay(seeded, cursor).incidents.items.map { it.id },
                    "incidents, cut $cut, cursor $cursor",
                )
            }
        }
    }

    @Test
    fun `a replay of trimmed history says its time is only a lower bound`() {
        val script = EventScript()
        script.created("a", at = sec(1))
        script.suspended("a", at = sec(20))
        script.resumed("a", at = sec(30))
        script.created("b", at = sec(31))
        val durable = script.table()
        val retained = script.events.drop(3)
        val retention = RetentionInfo(oldestRetainedSeq = 4, oldestRetainedAppNanos = sec(31), trimmed = true)

        fun asOfAt(
            seq: Long,
            retention: RetentionInfo = RetentionInfo(oldestRetainedSeq = 4, oldestRetainedAppNanos = sec(31), trimmed = true),
        ) =
            SessionHealthDerivation
                .derive(
                    HealthInput(
                        summaries = SummaryFold.foldTo(retained, seq, durable),
                        kind = SessionKind.ATTACHED,
                        mode = HealthMode.Replay(seq),
                        retention = retention,
                    ),
                ).asOf!!

        val inTrimmed = asOfAt(2)
        assertEquals(sec(1), inTrimmed.appNanos, "only the creation is known at or before seq 2")
        assertTrue(inTrimmed.lowerBound)
        assertEquals(2L, inTrimmed.seq)
        // Appends are not in app-time order: a trimmed event may be later in app time than every retained one.
        assertTrue(asOfAt(4).lowerBound)
        assertEquals(false, asOfAt(4, retention.copy(trimmed = false)).lowerBound)
    }
}
