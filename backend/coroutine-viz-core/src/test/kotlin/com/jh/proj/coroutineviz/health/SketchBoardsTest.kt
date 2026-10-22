package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineOrigin
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The three boards of the design canvas "vizcore session page — triage-first sketch". */
class SketchBoardsTest {
    private fun EventScript.vizcoreLeftovers(names: List<String>) {
        names.forEach { name ->
            created(name, at = sec(0), origin = CoroutineOrigin.VIZCORE)
            started(name, at = sec(0))
        }
    }

    private fun derive(
        script: EventScript,
        watermark: WatermarkObservation?,
    ) = SessionHealthDerivation.derive(
        HealthInput(
            summaries = script.table(),
            kind = SessionKind.ATTACHED,
            watermark = watermark,
            lastIngestEpochMs = 1_792_000_000_000,
            transitionsFor = { id -> Transitions.forCoroutine(script.events, id) },
        ),
    )

    @Test
    fun `board 1, a request failed`() {
        val script = EventScript()
        script.vizcoreLeftovers(listOf("cio-from-nio-reader", "cio-to-nio-writer", "nonce-generator"))
        script.created("req", at = sec(10), label = "GET /api/uat/dashboard")
        script.started("req", at = sec(10))
        val parts = listOf("dashboard.tasks", "dashboard.notifications", "dashboard.pets", "dashboard.failing-part")
        parts.forEach { part ->
            script.created(part, at = sec(10), parent = "req")
            script.started(part, at = sec(10))
            script.suspended(part, at = sec(10) + ms(5))
        }
        script.failed("dashboard.failing-part", at = sec(11), message = "dashboard part failed")
        listOf("dashboard.tasks", "dashboard.notifications", "dashboard.pets").forEach {
            script.cancelled(it, at = sec(11) + ms(1))
        }
        script.failed("req", at = sec(11) + ms(2), message = "dashboard part failed")

        val health = derive(script, WatermarkObservation(sec(12), 1_792_000_012_000, 1_792_000_012_050))

        assertEquals(HealthState.PROBLEMS, health.state)
        assertEquals(1, health.problemCount)
        val incident = assertIs<FailedRoundIncident>(health.incidents.items.single())
        assertEquals(listOf("req", "dashboard.failing-part"), incident.failures.items.map { it.coroutineId })
        assertEquals(3, incident.cancelledAfter.total)
        assertEquals(VizcoreConnectionHealth(3, 0, Capped(emptyList(), 0)), health.vizcoreConnection)
        assertEquals(OriginCounts(app = 5, vizcore = 3, unknown = 0, untracked = 0), health.counts)
        assertEquals(emptyList(), health.observations.longLived.items)
    }

    @Test
    fun `board 2, a coroutine may be blocking a thread`() {
        val script = EventScript()
        script.created("task", at = sec(100), label = "POST /api/tasks")
        script.started("task", at = sec(100))
        script.thread("task", at = sec(100), name = "reactor-http-nio-2")
        script.suspended("task", at = sec(101))
        script.resumed("task", at = sec(102))
        script.thread("task", at = sec(102), name = "lettuce-nioEventLoop-5-1", dispatcher = "Dispatchers.Unconfined")
        listOf("req-a", "req-b").forEach { id ->
            script.created(id, at = sec(103))
            script.started(id, at = sec(103))
            script.suspended(id, at = sec(103))
        }

        val health = derive(script, WatermarkObservation(sec(114), 1_792_000_114_000, 1_792_000_114_020))

        assertEquals(1, health.problemCount)
        val incident = assertIs<PossiblyBlockedIncident>(health.incidents.items.single())
        assertEquals("POST /api/tasks", incident.name)
        assertEquals("lettuce-nioEventLoop-", incident.eventLoopClue)
        assertEquals(sec(12), incident.runningForNanos)
        val round = ProblemRound("round:task", "task", sec(100), listOf(IncidentKind.POSSIBLY_BLOCKED))
        assertEquals(listOf(round), health.problemRounds.items)
    }

    @Test
    fun `board 3, no app coroutines captured, app disconnected`() {
        val script = EventScript()
        script.vizcoreLeftovers(listOf("cio-from-nio-reader", "cio-to-nio-writer", "cio-request-timeout", "nonce-generator", "ws-pinger"))

        val health = derive(script, watermark = null)

        assertEquals(HealthState.NO_APP_COROUTINES, health.state)
        assertEquals(0, health.problemCount)
        assertEquals(5, health.vizcoreConnection.captured)
        assertEquals(0, health.vizcoreConnection.failed)
        assertEquals(NotEvaluatedReason.NO_WATERMARK, health.possiblyBlocked.reason)
    }
}
