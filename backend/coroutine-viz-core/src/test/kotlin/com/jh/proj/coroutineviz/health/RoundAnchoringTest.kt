package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.models.CoroutineState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoundAnchoringTest {
    @Test
    fun `each genuine root anchors its subtree`() {
        val script = EventScript()
        script.created("req1", at = sec(1))
        script.created("req1.a", at = sec(1), parent = "req1")
        script.created("req2", at = sec(2))
        val anchoring = RoundAnchoring.of(script.table())

        assertEquals("req1", anchoring.anchorOf("req1.a"))
        assertEquals("req2", anchoring.anchorOf("req2"))
        assertEquals(listOf("round:req2", "round:req1"), anchoring.rounds().map { it.roundId })
    }

    @Test
    fun `a promoted container anchors each direct child and is a singleton round`() {
        val script = EventScript()
        script.created("app", at = sec(0))
        listOf("r1", "r2", "r3").forEachIndexed { i, id -> script.created(id, at = sec(1L + i), parent = "app") }
        script.created("r3.part", at = sec(3), parent = "r3")
        script.completed("r1", at = sec(4))
        script.completed("r2", at = sec(5))
        val anchoring = RoundAnchoring.of(script.table())

        assertEquals("r3", anchoring.anchorOf("r3.part"))
        assertEquals("app", anchoring.anchorOf("app"))
        val appRound = anchoring.rounds().single { it.anchorId == "app" }
        assertEquals(1, appRound.memberCount)
        val r3 = anchoring.rounds().single { it.anchorId == "r3" }
        assertEquals(2, r3.memberCount)
        assertEquals(true, r3.inProgress)
    }

    @Test
    fun `before promotion the whole tree is one round, after it the children re-key`() {
        val script = EventScript()
        script.created("app", at = sec(0))
        listOf("r1", "r2", "r3").forEachIndexed { i, id -> script.created(id, at = sec(1L + i), parent = "app") }
        script.failed("r1", at = sec(4))
        assertEquals("app", RoundAnchoring.of(script.table()).anchorOf("r1"))

        script.completed("r2", at = sec(5))
        assertEquals("r1", RoundAnchoring.of(script.table()).anchorOf("r1"))
    }

    @Test
    fun `a coroutine whose parent was never tracked has no round`() {
        val script = EventScript()
        script.created("orphan", at = 1, parent = "never-seen")
        script.created("orphan.child", at = 2, parent = "orphan")
        val anchoring = RoundAnchoring.of(script.table())

        assertNull(anchoring.anchorOf("orphan"))
        assertNull(anchoring.anchorOf("orphan.child"))
        assertEquals(emptyList(), anchoring.rounds())
    }

    @Test
    fun `reduced summaries keep anchoring and round order`() {
        val script = EventScript()
        script.created("req1", at = sec(1))
        script.created("req1.a", at = sec(1), parent = "req1")
        script.completed("req1.a", at = sec(2))
        script.completed("req1", at = sec(2))
        script.created("req2", at = sec(3))
        val full = RoundAnchoring.of(script.table())
        val reducedTable = script.table(fullSummaryTarget = 1)
        assertTrue(reducedTable.get("req1.a")!!.reduced)
        val reduced = RoundAnchoring.of(reducedTable)

        assertEquals("req1", reduced.anchorOf("req1.a"))
        assertEquals(full.rounds().map { it.roundId }, reduced.rounds().map { it.roundId })
    }

    @Test
    fun `round summaries count members by state`() {
        val script = EventScript()
        script.created("req", at = 1)
        script.started("req", at = 2)
        script.created("a", at = 3, parent = "req")
        script.failed("a", at = 4)
        script.created("b", at = 5, parent = "req")
        script.cancelled("b", at = 6)
        val round = RoundAnchoring.of(script.table()).rounds().single()

        assertEquals(mapOf(CoroutineState.ACTIVE to 1, CoroutineState.FAILED to 1, CoroutineState.CANCELLED to 1), round.countsByState)
        assertEquals(3, round.memberCount)
    }

    @Test
    fun `roundIdOf returns the round id of the anchor`() {
        val script = EventScript()
        script.created("req", at = 1)
        script.created("req.a", at = 2, parent = "req")
        val anchoring = RoundAnchoring.of(script.table())

        assertEquals("round:req", anchoring.roundIdOf("req.a"))
        assertNull(anchoring.roundIdOf("unknown"))
    }

    @Test
    fun `parent cycles and self-parents have no round`() {
        val script = EventScript()
        script.created("self", at = 1, parent = "self")
        script.created("a", at = 2, parent = "b")
        script.created("b", at = 3, parent = "a")
        val anchoring = RoundAnchoring.of(script.table())

        assertNull(anchoring.anchorOf("self"))
        assertNull(anchoring.anchorOf("a"))
        assertNull(anchoring.anchorOf("b"))
        assertEquals(emptyList(), anchoring.rounds())
    }

    @Test
    fun `an orphan whose parent arrives later joins that parent's round`() {
        val script = EventScript()
        script.created("child", at = 1, parent = "late")
        assertNull(RoundAnchoring.of(script.table()).anchorOf("child"))

        script.created("late", at = 2)
        val anchoring = RoundAnchoring.of(script.table())
        assertEquals("late", anchoring.anchorOf("child"))
    }

    @Test
    fun `a promoted container that finishes normally merges its children back into its round`() {
        val script = promotedContainer()
        script.completed("app", at = sec(6))
        val anchoring = RoundAnchoring.of(script.table())

        assertEquals("app", anchoring.anchorOf("r1"))
        assertEquals(listOf("round:app"), anchoring.rounds().map { it.roundId })
    }

    @Test
    fun `a container that grew after promotion and was cancelled keeps its children as separate rounds`() {
        val script = promotedContainer()
        script.created("r4", at = sec(6), parent = "app")
        script.cancelled("app", at = sec(7))
        val anchoring = RoundAnchoring.of(script.table())

        assertEquals("r1", anchoring.anchorOf("r1"))
        assertEquals("r4", anchoring.anchorOf("r4"))
        assertEquals("app", anchoring.anchorOf("app"))
    }

    private fun promotedContainer(): EventScript {
        val script = EventScript()
        script.created("app", at = sec(0))
        listOf("r1", "r2", "r3").forEachIndexed { i, id -> script.created(id, at = sec(1L + i), parent = "app") }
        script.completed("r1", at = sec(4))
        script.completed("r2", at = sec(5))
        return script
    }
}
