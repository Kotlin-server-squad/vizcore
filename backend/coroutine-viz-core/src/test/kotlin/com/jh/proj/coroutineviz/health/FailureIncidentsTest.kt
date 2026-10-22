package com.jh.proj.coroutineviz.health

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FailureIncidentsTest {
    private fun incidents(script: EventScript): List<Incident> {
        val table = script.table()
        return FailureIncidents.build(table.all.toList(), RoundAnchoring.of(table))
    }

    /** req with children a, b, c; a fails, b and c are cancelled; the parent's own terminal per [parentFirst]. */
    private fun failingRequest(parentFirst: Boolean): EventScript =
        EventScript().apply {
            created("req", at = sec(1))
            listOf("a", "b", "c").forEach { created(it, at = sec(1), parent = "req") }
            if (parentFirst) failed("req", at = sec(2))
            failed("a", at = sec(2))
            cancelled("b", at = sec(2))
            cancelled("c", at = sec(2))
            if (!parentFirst) failed("req", at = sec(3))
        }

    @Test
    fun `all failures of a round are one incident, whatever the terminal order`() {
        for (parentFirst in listOf(true, false)) {
            val incident = assertIs<FailedRoundIncident>(incidents(failingRequest(parentFirst)).single())
            assertEquals("round:req", incident.roundId)
            assertEquals(setOf("a", "req"), incident.failures.items.map { it.coroutineId }.toSet())
            assertEquals(listOf("c", "b"), incident.cancelledAfter.items, "newest first")
        }
    }

    @Test
    fun `cancellations before the first failure are not listed after it`() {
        val script = EventScript()
        script.created("req", at = 1)
        script.created("early", at = 1, parent = "req")
        script.created("a", at = 1, parent = "req")
        script.cancelled("early", at = 2)
        script.failed("a", at = 3)

        val incident = assertIs<FailedRoundIncident>(incidents(script).single())
        assertEquals(emptyList(), incident.cancelledAfter.items)
    }

    @Test
    fun `cancellations alone, and cancellation exceptions, are not problems`() {
        val script = EventScript()
        script.created("req", at = 1)
        script.created("a", at = 1, parent = "req")
        script.cancelled("a", at = 2)
        script.failed("req", at = 3, type = "kotlinx.coroutines.JobCancellationException")

        assertEquals(emptyList(), incidents(script))
    }

    @Test
    fun `a failure without resolvable ancestry is ungrouped`() {
        val script = EventScript()
        script.created("orphan", at = 1, parent = "never-seen")
        script.failed("orphan", at = 2, message = "lost")

        val incident = assertIs<UngroupedFailureIncident>(incidents(script).single())
        assertEquals("orphan", incident.failure.coroutineId)
        assertEquals("lost", incident.failure.message)
    }

    @Test
    fun `a reduced failure is still counted, with details dropped`() {
        val script = EventScript()
        script.created("req", at = 1)
        script.failed("req", at = 2)
        script.created("x", at = 3)
        script.created("y", at = 4)
        val table = script.table(fullSummaryTarget = 1)

        val incident = assertIs<FailedRoundIncident>(FailureIncidents.build(table.all.toList(), RoundAnchoring.of(table)).single())
        assertTrue(incident.failures.items.single().detailsDropped)
    }
}
