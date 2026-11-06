package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.health.EventScript
import com.jh.proj.coroutineviz.health.SummaryTable
import com.jh.proj.coroutineviz.health.sec
import com.jh.proj.coroutineviz.health.toState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A session rehydrates its summaries from a checkpoint and reads one consistent cut (spec C-4, C-6; plan Task 11). */
class VizSessionCheckpointTest {
    private val sessions = mutableListOf<VizSession>()

    @AfterEach
    fun tearDown() = sessions.forEach { it.close() }

    private fun script(): EventScript =
        EventScript("cp").apply {
            created("app", at = sec(1))
            started("app", at = sec(1))
            for (i in 1..4) {
                created("r$i", at = sec(1 + i.toLong()), parent = "app")
                started("r$i", at = sec(1 + i.toLong()))
                if (i == 2) failed("r$i", at = sec(2 + i.toLong())) else completed("r$i", at = sec(2 + i.toLong()))
            }
        }

    private fun session(events: List<VizEvent> = emptyList()) =
        VizSession("cp", keepSummaries = true, eventStoreFactory = { EventStore(1_000).also { s -> events.forEach(s::record) } }).also { sessions += it }

    @Test
    fun `a session restored from a checkpoint and the events after it has the summaries of the whole history`() {
        val events = script().events
        val whole = SummaryTable().also { t -> events.forEach(t::apply) }.toState()
        val seed = SummaryTable().also { t -> events.take(6).forEach(t::apply) }.toState()
        val retained = events.drop(6)
        val b = session(retained)
        b.rehydrateFrom(retained, seed)
        assertEquals(whole, b.readSummaries { it.toState() })
        assertEquals(6L, b.summarySeedSeq)
        assertEquals(events.last().seq, b.lastSeq)
        val createdInRetained = retained.filterIsInstance<com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated>().map { it.coroutineId }.toSet()
        assertEquals(createdInRetained, b.projectionService.getHierarchyTree().map { it.id }.toSet(), "projections from the retained events")
    }

    @Test
    fun `events the checkpoint already covers are not applied to the summaries twice`() {
        val events = script().events
        val whole = SummaryTable().also { t -> events.forEach(t::apply) }.toState()
        val seed = SummaryTable().also { t -> events.take(9).forEach(t::apply) }.toState()
        val retained = events.drop(6)
        val b = session(retained)
        b.rehydrateFrom(retained, seed)
        assertEquals(whole, b.readSummaries { it.toState() })
    }

    @Test
    fun `without a checkpoint it is today's rehydration`() {
        val events = script().events
        val b = session(events)
        b.rehydrateFrom(events, seed = null)
        assertNull(b.summarySeedSeq)
        assertEquals(SummaryTable().also { t -> events.forEach(t::apply) }.toState(), b.readSummaries { it.toState() })
    }

    @Test
    fun `a checkpoint newer than every event is refused`() {
        val events = script().events
        val seed = SummaryTable().also { t -> events.forEach(t::apply) }.toState()
        val b = session(events.take(3))
        assertThrows<IllegalArgumentException> { b.rehydrateFrom(events.take(3), seed) }
    }

    @Test
    fun `a cut runs under the send lock, and without summaries it gets none`() {
        val a = session()
        val inCut = CountDownLatch(1)
        val release = CountDownLatch(1)
        var sentDuringCut = true
        val reader = thread { a.readCut { inCut.countDown(); release.await(5, TimeUnit.SECONDS) } }
        assertTrue(inCut.await(5, TimeUnit.SECONDS))
        val sender = thread { a.send(com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated("cp", 0, 1, "x", "job-x", null, "scope", "x")); sentDuringCut = false }
        Thread.sleep(100)
        assertTrue(sentDuringCut && sender.isAlive, "the send waits for the cut")
        release.countDown()
        reader.join(5_000)
        sender.join(5_000)
        assertFalse(sentDuringCut)
        val noSummaries = VizSession("n", keepSummaries = false).also { sessions += it }
        assertNull(noSummaries.readCut { it })
    }

    @Test
    fun `events up to the checkpoint never reach the restored summaries`() {
        val events = script().events
        // r1 completed at seq 5, inside the checkpoint (seq 6). A seed that, on purpose, still has r1 running tells the
        // two apart: had event 5 been applied again, r1 would read completed.
        val seed =
            SummaryTable().also { t -> events.take(6).forEach(t::apply) }.toState().let { s ->
                s.copy(summaries = s.summaries.map { if (it.id == "r1") it.copy(terminal = null, lastTransition = it.lastRunStart!!) else it })
            }
        val b = session(events)
        b.rehydrateFrom(events, seed)
        assertEquals(com.jh.proj.coroutineviz.models.CoroutineState.ACTIVE, b.readSummaries { it.get("r1")!!.state })
    }
}
