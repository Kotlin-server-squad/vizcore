package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineOrigin
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HealthPayloadTest {
    private val watermark = WatermarkObservation(appNanos = sec(20), appEpochMs = 1_792_000_000_000, receivedEpochMs = 1_792_000_000_005)

    private fun appScript(): EventScript =
        EventScript().apply {
            created("a", at = sec(1))
            started("a", at = sec(1))
            suspended("a", at = sec(2))
        }

    @Test
    fun `an incomplete history reads at least and leaves possibly blocked unevaluated`() {
        val health =
            SessionHealthDerivation.derive(
                HealthInput(summaries = appScript().table(), kind = SessionKind.ATTACHED, watermark = watermark, historyIncomplete = true),
            )

        assertTrue(health.failuresAtLeast)
        assertEquals(PossiblyBlockedEvaluation(EvaluationStatus.NOT_EVALUATED, NotEvaluatedReason.HISTORY_TRIMMED), health.possiblyBlocked)
        assertEquals(HealthState.NO_FAILURES_PARTIAL, health.state)
    }

    @Test
    fun `an incomplete history never claims there are no app coroutines`() {
        // Only vizcore's own coroutine was captured: without the flag that reads "no application coroutines".
        val table = EventScript().apply { created("v", at = sec(1), origin = CoroutineOrigin.VIZCORE) }.table()
        val complete = SessionHealthDerivation.derive(HealthInput(summaries = table, kind = SessionKind.ATTACHED))
        val incomplete = SessionHealthDerivation.derive(HealthInput(summaries = table, kind = SessionKind.ATTACHED, historyIncomplete = true))

        assertEquals(HealthState.NO_APP_COROUTINES, complete.state)
        assertEquals(HealthState.NO_FAILURES_PARTIAL, incomplete.state)
    }

    @Test
    fun `lost events outrank a trimmed history, which outranks a missing watermark`() {
        val table = appScript().table()
        val dropped =
            SessionHealthDerivation.derive(
                HealthInput(
                    summaries = table,
                    kind = SessionKind.ATTACHED,
                    transport = TransportStatus(deliveryUncertain = true, sinceSeq = 1),
                    historyIncomplete = true,
                ),
            )
        val trimmed = SessionHealthDerivation.derive(HealthInput(summaries = table, kind = SessionKind.ATTACHED, historyIncomplete = true))

        assertEquals(NotEvaluatedReason.EVENTS_DROPPED, dropped.possiblyBlocked.reason)
        assertEquals(NotEvaluatedReason.HISTORY_TRIMMED, trimmed.possiblyBlocked.reason)
    }

    @Test
    fun `the watermark the health used is echoed for live attached sessions only`() {
        val table = appScript().table()
        val live = SessionHealthDerivation.derive(HealthInput(summaries = table, kind = SessionKind.ATTACHED, watermark = watermark))
        val replay =
            SessionHealthDerivation.derive(
                HealthInput(summaries = table, kind = SessionKind.ATTACHED, mode = HealthMode.Replay(2), watermark = watermark),
            )
        val demo = SessionHealthDerivation.derive(HealthInput(summaries = table, kind = SessionKind.DEMO, watermark = watermark))

        assertEquals(watermark, live.asOfWatermark)
        assertNull(replay.asOfWatermark)
        assertNull(demo.asOfWatermark)
    }

    @Test
    fun `retention is echoed`() {
        val retention = RetentionInfo(oldestRetainedSeq = 3, oldestRetainedAppNanos = sec(2), trimmed = true)

        val health = SessionHealthDerivation.derive(HealthInput(summaries = appScript().table(), kind = SessionKind.DEMO, retention = retention))

        assertEquals(retention, health.retention)
    }

    @Test
    fun `session origin follows the derivation's rule`() {
        val reported = appScript().table()
        val unknown = EventScript().apply { created("a", at = sec(1), origin = null) }.table()

        assertEquals(SessionOrigin.REPORTED, SessionHealthDerivation.sessionOrigin(reported, SessionKind.ATTACHED))
        assertEquals(SessionOrigin.UNKNOWN, SessionHealthDerivation.sessionOrigin(unknown, SessionKind.ATTACHED))
        assertEquals(SessionOrigin.REPORTED, SessionHealthDerivation.sessionOrigin(unknown, SessionKind.DEMO))
    }
}
