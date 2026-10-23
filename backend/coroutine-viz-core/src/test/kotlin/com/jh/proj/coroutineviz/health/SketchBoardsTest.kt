package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.events.SuspensionProvenance
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The three boards of the design canvas "vizcore session page — triage-first sketch". */
class SketchBoardsTest {
    private fun EventScript.vizcoreLeftovers(names: List<String>) {
        names.forEach { name ->
            created(name, at = sec(0), origin = CoroutineOrigin.VIZCORE)
            started(name, at = sec(0))
        }
    }

    private fun observation(appSeconds: Long) =
        WatermarkObservation(sec(appSeconds), 1_792_000_000_000 + appSeconds * 1_000, 1_792_000_000_000 + appSeconds * 1_000 + 50)

    private fun derive(
        summaries: SummaryView,
        script: EventScript,
        watermark: WatermarkObservation?,
    ) = SessionHealthDerivation.derive(
        HealthInput(
            summaries = summaries,
            kind = SessionKind.ATTACHED,
            watermark = watermark,
            lastIngestEpochMs = 1_792_000_000_000,
            transitionsFor = { id -> Transitions.forCoroutine(script.events, id) },
        ),
    )

    private fun derive(
        script: EventScript,
        watermark: WatermarkObservation?,
    ) = derive(script.table(), script, watermark)

    /**
     * Strip text: "1 problem detected [Failed 1] · Nothing possibly blocked · Long-lived 0 · as of 12:00:42".
     * The leftovers are 42 s old at the watermark (past the 30 s leak threshold) but are vizcore's own, so they
     * are not observations. The request is one round once it finished, though it was split while it ran.
     */
    @Test
    fun `board 1, a request failed`() {
        val script = EventScript()
        script.vizcoreLeftovers(listOf("cio-from-nio-reader", "cio-to-nio-writer", "nonce-generator"))
        script.created("req", at = sec(40), label = "GET /api/uat/dashboard")
        script.started("req", at = sec(40))
        val parts = listOf("dashboard.tasks", "dashboard.notifications", "dashboard.pets", "dashboard.failing-part")
        parts.forEach { part ->
            script.created(part, at = sec(40), parent = "req")
            script.started(part, at = sec(40))
            script.suspended(part, at = sec(40) + ms(5))
        }
        script.failed("dashboard.failing-part", at = sec(41), message = "dashboard part failed")
        listOf("dashboard.tasks", "dashboard.notifications", "dashboard.pets").forEach {
            script.cancelled(it, at = sec(41) + ms(1))
        }
        val beforeRequestFails = script.lastSeq
        script.failed("req", at = sec(41) + ms(2), message = "dashboard part failed")
        script.watermark(sec(42), epochMs = observation(42).appEpochMs)
        val watermark = observation(42)

        val health = derive(script, watermark)

        assertEquals(HealthState.PROBLEMS, health.state)
        assertEquals(1, health.problemCount)
        assertEquals(1, health.failedRoundCount)
        assertEquals(0, health.ungroupedFailureCount)
        assertEquals(PossiblyBlockedEvaluation(EvaluationStatus.EVALUATED), health.possiblyBlocked)
        assertEquals(AsOf(AsOfKind.WATERMARK, sec(42), watermark.appEpochMs, watermark.receivedEpochMs, script.lastSeq), health.asOf)
        val incident = assertIs<FailedRoundIncident>(health.incidents.items.single())
        assertEquals(listOf("req", "dashboard.failing-part"), incident.failures.items.map { it.coroutineId })
        assertEquals(3, incident.cancelledAfter.total)
        assertEquals(VizcoreConnectionHealth(3, 0, Capped(emptyList(), 0)), health.vizcoreConnection)
        assertEquals(OriginCounts(app = 5, vizcore = 3, unknown = 0, untracked = 0), health.counts)
        assertEquals(emptyList(), health.observations.longLived.items)
        assertEquals(1, health.roundCount)
        assertEquals("req", health.problemRounds.items.single().anchorId)
        assertNull(script.table().get("req")?.promotedAtSeq)

        // Mid-way, with the failing part and the cancellations in but the request still running, the
        // request is a promoted container: five rounds, and the failing part's own round is the incident.
        val midTable = SummaryFold.foldTo(script.events, beforeRequestFails)
        assertNotNull(midTable.get("req")?.promotedAtSeq)
        val mid = derive(midTable, script, watermark)
        assertEquals(5, mid.roundCount)
        assertEquals("dashboard.failing-part", assertIs<FailedRoundIncident>(mid.incidents.items.single()).anchorId)
    }

    /** Strip text: "1 problem detected [Possibly blocked 1] · as of 12:01:54". */
    @Test
    fun `board 2, a coroutine may be blocking a thread`() {
        val script = EventScript()
        script.created("task", at = sec(100), label = "POST /api/tasks")
        script.started("task", at = sec(100))
        script.thread("task", at = sec(100), name = "reactor-http-nio-2", dispatcher = null)
        script.suspended("task", at = sec(101))
        script.resumed("task", at = sec(102))
        script.thread("task", at = sec(102), name = "lettuce-nioEventLoop-5-1", dispatcher = "Dispatchers.Unconfined")
        listOf("req-a", "req-b").forEach { id ->
            script.created(id, at = sec(103))
            script.started(id, at = sec(103))
            script.suspended(id, at = sec(103))
        }

        val health = derive(script, observation(114))

        assertEquals(HealthState.PROBLEMS, health.state)
        assertEquals(1, health.problemCount)
        assertEquals(PossiblyBlockedEvaluation(EvaluationStatus.EVALUATED), health.possiblyBlocked)
        assertEquals(AsOfKind.WATERMARK, health.asOf?.kind)
        val incident = assertIs<PossiblyBlockedIncident>(health.incidents.items.single())
        assertEquals("POST /api/tasks", incident.name)
        assertEquals("lettuce-nioEventLoop-", incident.eventLoopClue)
        assertEquals(sec(12), incident.runningForNanos)
        assertEquals("lettuce-nioEventLoop-5-1", incident.lastThread?.threadName)
        assertEquals("Dispatchers.Unconfined", incident.dispatcher)
        assertEquals(SuspensionProvenance.REAL_FRAME, incident.lastSuspensionProvenance)
        assertEquals(
            listOf(TransitionKind.CREATED, TransitionKind.STARTED, TransitionKind.SUSPENDED, TransitionKind.RESUMED),
            incident.transitions.map { it.kind },
        )
        assertFalse(incident.transitionsGap)
        assertFalse(incident.firstObservedRunning)
        assertEquals("round:task", incident.roundId)
        val round = ProblemRound("round:task", "task", sec(100), listOf(IncidentKind.POSSIBLY_BLOCKED))
        assertEquals(listOf(round), health.problemRounds.items)
    }

    /**
     * Strip text: "No app coroutines captured · Nothing possibly blocked · Long-lived 0 · as of 12:00:30".
     * A 1.3 session always has a watermark, so board 3 has the last one before the disconnect. That the app
     * disconnected is the backend's `appConnected` (spec C-3) and out of scope for core.
     */
    @Test
    fun `board 3, no app coroutines captured, app disconnected`() {
        val script = EventScript()
        // ws-pinger stands for an SDK connection coroutine, like the spec's leftovers.
        script.vizcoreLeftovers(listOf("cio-from-nio-reader", "cio-to-nio-writer", "cio-request-timeout", "nonce-generator", "ws-pinger"))

        val health = derive(script, observation(30))

        assertEquals(HealthState.NO_APP_COROUTINES, health.state)
        assertEquals(0, health.problemCount)
        assertEquals(PossiblyBlockedEvaluation(EvaluationStatus.EVALUATED), health.possiblyBlocked)
        assertEquals(AsOfKind.WATERMARK, health.asOf?.kind)
        assertEquals(OriginCounts(app = 0, vizcore = 5, unknown = 0, untracked = 0), health.counts)
        assertEquals(5, health.vizcoreConnection.captured)
        assertEquals(0, health.vizcoreConnection.failed)
        assertEquals(emptyList(), health.observations.longLived.items)
        assertEquals(0, health.roundCount)
    }
}
