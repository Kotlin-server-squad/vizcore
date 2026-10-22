package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.CoroutineOrigin
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionHealthDerivationTest {
    private val watermark = WatermarkObservation(appNanos = sec(60), appEpochMs = 1_792_000_060_000, receivedEpochMs = 1_792_000_060_100)

    private fun derive(
        script: EventScript,
        kind: SessionKind = SessionKind.ATTACHED,
        watermark: WatermarkObservation? = this.watermark,
        transport: TransportStatus = TransportStatus(),
        mode: HealthMode = HealthMode.Live,
        trackedCapacity: Int = HealthThresholds.DEFAULT_TRACKED_CAPACITY,
    ): SessionHealth =
        SessionHealthDerivation.derive(
            HealthInput(
                summaries = script.table(trackedCapacity = trackedCapacity),
                kind = kind,
                mode = mode,
                watermark = watermark,
                lastIngestEpochMs = 1_792_000_059_000,
                transport = transport,
                transitionsFor = { id -> Transitions.forCoroutine(script.events, id) },
            ),
        )

    private fun quietApp(): EventScript =
        EventScript().apply {
            created("req", at = sec(1))
            started("req", at = sec(1))
            suspended("req", at = sec(2))
        }

    @Test
    fun `nothing captured yet is awaiting first data, without asOf`() {
        val health = derive(EventScript(), watermark = null)
        assertEquals(HealthState.AWAITING_FIRST_DATA, health.state)
        assertNull(health.asOf)
    }

    @Test
    fun `quiet app with a watermark is green, as of the watermark`() {
        val health = derive(quietApp())
        assertEquals(HealthState.NO_PROBLEMS, health.state)
        assertEquals(AsOf(AsOfKind.WATERMARK, sec(60), 1_792_000_060_000, 1_792_000_060_100, seq = 3), health.asOf)
        assertEquals(PossiblyBlockedEvaluation(EvaluationStatus.EVALUATED), health.possiblyBlocked)
    }

    @Test
    fun `an event after the last watermark makes the ingest the latest observation`() {
        val script = quietApp()
        script.created("late", at = sec(61))
        val health = derive(script)

        assertEquals(AsOf(AsOfKind.INGEST, sec(61), receivedEpochMs = 1_792_000_059_000, seq = 4), health.asOf)
        assertEquals(EvaluationStatus.EVALUATED, health.possiblyBlocked.status, "blocking still uses the watermark")
    }

    @Test
    fun `without a watermark possibly blocked is not evaluated and the state is not green`() {
        val health = derive(quietApp(), watermark = null)
        assertEquals(HealthState.NO_FAILURES_PARTIAL, health.state)
        assertEquals(NotEvaluatedReason.NO_WATERMARK, health.possiblyBlocked.reason)
        assertEquals(AsOfKind.INGEST, health.asOf?.kind)
        assertEquals(1_792_000_059_000, health.asOf?.receivedEpochMs)
    }

    @Test
    fun `demo sessions are app coroutines, not evaluated for blocking, as of the last event`() {
        val script = quietApp()
        script.created("legacy", at = sec(3), origin = null)
        val health = derive(script, kind = SessionKind.DEMO)

        assertEquals(NotEvaluatedReason.DEMO, health.possiblyBlocked.reason)
        assertEquals(AsOfKind.LAST_EVENT, health.asOf?.kind)
        assertEquals(SessionOrigin.REPORTED, health.sessionOrigin)
        assertEquals(2, health.counts.app)
    }

    @Test
    fun `asOf is the latest time observed up to the last seq when times arrive out of order`() {
        val script = EventScript()
        script.created("a", at = sec(61))
        script.created("b", at = sec(59))
        val ingest = AsOf(AsOfKind.INGEST, sec(61), receivedEpochMs = 1_792_000_059_000, seq = 2)

        assertEquals(AsOf(AsOfKind.LAST_EVENT, sec(61), seq = 2), derive(script, kind = SessionKind.DEMO).asOf)
        assertEquals(AsOf(AsOfKind.REPLAY_CURSOR, sec(61), seq = 2), derive(script, mode = HealthMode.Replay(atSeq = 2)).asOf)
        assertEquals(ingest, derive(script).asOf, "the 61 s event is newer than the 60 s watermark")
    }

    @Test
    fun `replay is not evaluated for blocking and is as of the cursor`() {
        val health = derive(quietApp(), mode = HealthMode.Replay(atSeq = 3))
        assertEquals(NotEvaluatedReason.REPLAY, health.possiblyBlocked.reason)
        assertEquals(AsOf(AsOfKind.REPLAY_CURSOR, sec(2), seq = 3), health.asOf)
    }

    @Test
    fun `lost events make failures at least and blocking not evaluated`() {
        val health = derive(quietApp(), transport = TransportStatus(lostEvents = 4, sinceSeq = 2))
        assertEquals(NotEvaluatedReason.EVENTS_DROPPED, health.possiblyBlocked.reason)
        assertTrue(health.failuresAtLeast)
        assertEquals(HealthState.NO_FAILURES_PARTIAL, health.state)
    }

    @Test
    fun `lost events are reported as such even without a watermark`() {
        val health = derive(quietApp(), watermark = null, transport = TransportStatus(deliveryUncertain = true, sinceSeq = 1))
        assertEquals(NotEvaluatedReason.EVENTS_DROPPED, health.possiblyBlocked.reason)
    }

    @Test
    fun `overflow counts untracked coroutines and is never green`() {
        val script = quietApp()
        script.created("extra", at = sec(3))
        val health = derive(script, trackedCapacity = 1)

        assertEquals(1, health.counts.untracked)
        assertEquals(NotEvaluatedReason.OVERFLOW, health.possiblyBlocked.reason)
        assertTrue(health.failuresAtLeast)
        assertEquals(HealthState.NO_FAILURES_PARTIAL, health.state)
    }

    @Test
    fun `vizcore coroutines are excluded from problems and counted as the connection`() {
        val script = quietApp()
        script.created("cio-reader", at = sec(1), origin = CoroutineOrigin.VIZCORE)
        script.failed("cio-reader", at = sec(2))
        val health = derive(script)

        assertEquals(0, health.problemCount)
        assertEquals(1, health.vizcoreConnection.captured)
        assertEquals(1, health.vizcoreConnection.failed)
        assertEquals(listOf("cio-reader"), health.vizcoreConnection.failures.items.map { it.coroutineId })
        assertEquals(OriginCounts(app = 1, vizcore = 1, unknown = 0, untracked = 0), health.counts)
    }

    @Test
    fun `only vizcore coroutines captured is the no-app-coroutines state`() {
        val script = EventScript()
        script.created("cio-reader", at = sec(1), origin = CoroutineOrigin.VIZCORE)
        assertEquals(HealthState.NO_APP_COROUTINES, derive(script).state)
    }

    @Test
    fun `a stated origin with only watermarks so far is no app coroutines, not origin unknown`() {
        val script = EventScript()
        script.watermark(at = sec(1))
        val health =
            SessionHealthDerivation.derive(
                HealthInput(
                    summaries = script.table(),
                    kind = SessionKind.ATTACHED,
                    watermark = watermark,
                    reportedOrigin = SessionOrigin.REPORTED,
                ),
            )

        assertEquals(SessionOrigin.REPORTED, health.sessionOrigin)
        assertEquals(HealthState.NO_APP_COROUTINES, health.state)
        assertEquals(AsOf(AsOfKind.WATERMARK, sec(60), 1_792_000_060_000, 1_792_000_060_100, seq = 1), health.asOf)
    }

    @Test
    fun `without a stated or reported origin, nothing captured is not claimed as no app coroutines`() {
        val script = EventScript()
        script.watermark(at = sec(1))
        val health = derive(script)

        assertEquals(SessionOrigin.UNKNOWN, health.sessionOrigin)
        assertEquals(HealthState.NO_FAILURES_PARTIAL, health.state)
    }

    @Test
    fun `untracked coroutines are never claimed as no app coroutines`() {
        val script = EventScript()
        script.created("cio-reader", at = sec(1), origin = CoroutineOrigin.VIZCORE)
        script.created("req", at = sec(2))
        val health = derive(script, trackedCapacity = 1)

        assertEquals(1, health.counts.untracked)
        assertEquals(HealthState.NO_FAILURES_PARTIAL, health.state)
    }

    @Test
    fun `without any reported origin every coroutine is unknown and evaluated`() {
        val script = EventScript()
        script.created("a", at = sec(1), origin = null)
        script.failed("a", at = sec(2))
        val health = derive(script)

        assertEquals(SessionOrigin.UNKNOWN, health.sessionOrigin)
        assertEquals(OriginCounts(app = 0, vizcore = 0, unknown = 1, untracked = 0), health.counts)
        assertEquals(1, health.problemCount)
    }

    @Test
    fun `a failed round with a possibly blocked sibling is two incidents in one problem round, failures first`() {
        val script = EventScript()
        script.created("req", at = sec(1))
        script.created("a", at = sec(1), parent = "req")
        script.created("b", at = sec(1), parent = "req")
        script.started("b", at = sec(1))
        script.failed("a", at = sec(2))
        val health = derive(script)

        assertEquals(2, health.problemCount)
        assertIs<FailedRoundIncident>(health.incidents.items[0])
        assertIs<PossiblyBlockedIncident>(health.incidents.items[1])
        val round = ProblemRound("round:req", "req", sec(1), listOf(IncidentKind.FAILED, IncidentKind.POSSIBLY_BLOCKED))
        assertEquals(listOf(round), health.problemRounds.items)
        assertEquals(1, health.roundCount)
    }

    @Test
    fun `long-lived and long-suspended are observations with ages frozen at asOf`() {
        val script = EventScript()
        script.created("stream", at = sec(1))
        script.started("stream", at = sec(1))
        script.suspended("stream", at = sec(20))
        val health = derive(script)

        assertEquals(0, health.problemCount)
        assertEquals(listOf(ObservedAge("stream", "stream", sec(59))), health.observations.longLived.items)
        assertEquals(listOf(ObservedAge("stream", "stream", sec(40))), health.observations.longSuspended.items)
    }

    @Test
    fun `without a watermark an age ends at the coroutine's own last event`() {
        val script = EventScript()
        script.created("a", at = sec(1))
        script.started("a", at = sec(1))
        script.created("b", at = sec(100))
        val health = derive(script, watermark = null)

        assertEquals(emptyList(), health.observations.longLived.items, "b's activity is no evidence about a")
    }

    @Test
    fun `thresholds are inputs`() {
        val script = EventScript()
        script.created("stream", at = sec(1))
        script.suspended("stream", at = sec(50))
        val health =
            SessionHealthDerivation.derive(
                HealthInput(
                    summaries = script.table(),
                    kind = SessionKind.ATTACHED,
                    watermark = watermark,
                    leakThresholdMs = 120_000,
                    longSuspendedThresholdNanos = sec(5),
                ),
            )

        assertEquals(emptyList(), health.observations.longLived.items, "59 s is under a 120 s leak threshold")
        assertEquals(listOf("stream"), health.observations.longSuspended.items.map { it.coroutineId })
    }

    @Test
    fun `lists are capped at 50 with their total`() {
        val script = EventScript()
        for (i in 1..60) {
            script.created("o$i", at = sec(1), parent = "never-seen")
            script.failed("o$i", at = sec(2))
        }
        val health = derive(script)

        assertEquals(60, health.problemCount)
        assertEquals(50, health.incidents.items.size)
        assertEquals(60, health.incidents.total)
    }

    @Test
    fun `health round-trips through appJson`() {
        val script = quietApp()
        script.created("a", at = sec(1), parent = "req")
        script.failed("a", at = sec(2))
        val health = derive(script)

        val json = appJson.encodeToString(SessionHealth.serializer(), health)
        assertEquals(health, appJson.decodeFromString(SessionHealth.serializer(), json))
        assertFalse(health.failuresAtLeast)
    }
}
