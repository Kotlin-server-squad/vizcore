package com.jh.proj.coroutineviz.health

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PossiblyBlockedTest {
    private fun blocked(
        script: EventScript,
        watermarkNanos: Long,
    ): List<PossiblyBlockedIncident> {
        val table = script.table()
        return PossiblyBlockedIncidents.build(table.all.toList(), RoundAnchoring.of(table), watermarkNanos) { id ->
            Transitions.forCoroutine(script.events, id)
        }
    }

    private fun runningTask(threadName: String = "lettuce-nioEventLoop-5-1"): EventScript =
        EventScript().apply {
            created("task", at = sec(100), label = "POST /api/tasks")
            started("task", at = sec(100))
            thread("task", at = sec(100), name = "reactor-http-nio-2")
            suspended("task", at = sec(101))
            resumed("task", at = sec(102))
            thread("task", at = sec(102), name = threadName, dispatcher = "Dispatchers.Unconfined")
        }

    @Test
    fun `running for more than 10 s up to the watermark is possibly blocked`() {
        val incident = blocked(runningTask(), watermarkNanos = sec(112) + 1).single()

        assertEquals("task", incident.coroutineId)
        assertEquals(sec(10) + 1, incident.runningForNanos)
        assertEquals("round:task", incident.roundId)
        assertEquals("lettuce-nioEventLoop-", incident.eventLoopClue)
        assertEquals("lettuce-nioEventLoop-5-1", incident.lastThread?.threadName)
        assertEquals("Dispatchers.Unconfined", incident.dispatcher)
        assertFalse(incident.firstObservedRunning)
        assertFalse(incident.transitionsGap)
    }

    @Test
    fun `exactly 10 s is not over the threshold`() {
        assertEquals(emptyList(), blocked(runningTask(), watermarkNanos = sec(112)))
    }

    @Test
    fun `suspended and finished coroutines are never possibly blocked`() {
        val script = runningTask()
        script.suspended("task", at = sec(103))
        assertEquals(emptyList(), blocked(script, watermarkNanos = sec(200)))

        script.resumed("task", at = sec(104))
        script.completed("task", at = sec(105))
        assertEquals(emptyList(), blocked(script, watermarkNanos = sec(200)))
    }

    @Test
    fun `no clue without a thread assignment in the current run`() {
        val script = EventScript()
        script.created("task", at = sec(1))
        script.started("task", at = sec(1))
        script.thread("task", at = sec(1), name = "lettuce-nioEventLoop-5-1")
        script.suspended("task", at = sec(2))
        script.resumed("task", at = sec(3))

        val incident = blocked(script, watermarkNanos = sec(20)).single()
        assertNull(incident.lastThread)
        assertNull(incident.eventLoopClue)
    }

    @Test
    fun `each known event-loop prefix is a clue, other names are not`() {
        for (prefix in HealthThresholds.EVENT_LOOP_THREAD_PREFIXES) {
            assertEquals(prefix, blocked(runningTask("${prefix}1"), watermarkNanos = sec(200)).single().eventLoopClue)
        }
        assertNull(blocked(runningTask("DefaultDispatcher-worker-1"), watermarkNanos = sec(200)).single().eventLoopClue)
    }

    @Test
    fun `first observed running means no suspension was ever observed`() {
        val script = EventScript()
        script.created("loop", at = sec(1))
        script.started("loop", at = sec(1))

        assertTrue(blocked(script, watermarkNanos = sec(20)).single().firstObservedRunning)
    }

    @Test
    fun `transitions keep the 20 most recent and mark the gap`() {
        val script = EventScript()
        script.created("busy", at = 0)
        script.started("busy", at = 1)
        for (i in 1..15L) {
            script.suspended("busy", at = ms(i))
            script.resumed("busy", at = ms(i) + 1)
        }

        val incident = blocked(script, watermarkNanos = sec(30)).single()
        assertEquals(HealthThresholds.TRANSITIONS_CAP, incident.transitions.size)
        assertTrue(incident.transitionsGap)
        assertEquals(TransitionKind.RESUMED, incident.transitions.last().kind)
    }
}
