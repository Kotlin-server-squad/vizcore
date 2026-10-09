package com.jh.proj.coroutineviz.harness

import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PERF-05 smoke test (D-10/D-11) — exercises the SAME `main`-resident [EgressLoadDriver]
 * the dev-only `loadHarness` entrypoint wraps, so the flood logic has a single source of truth
 * and this `:test` gate always compiles (it never references the `loadHarness` source set).
 *
 * The driver floods synthetic events straight at `EventBus.send` through the real
 * adaptive-sample → structural-shed egress chain. With a deliberately TINY shed capacity the
 * sheddable lane overflows, forcing the sampling-drop counter positive while structural events
 * stay protected. The assertions prove:
 *  1. The THREE drop counters (store / bus / sampling) are reported independently (non-conflated, D-11).
 *  2. The store counter is 0 — the harness injects at the bus, bypassing the store (the D-11
 *     isolation point).
 *  3. EVERY structural (lifecycle) event that reached the egress chain survives it under forced
 *     load — the [com.jh.proj.coroutineviz.session.StructuralAwareBuffer] never sheds structural
 *     events (D-01/D-07). (Phrased against what reached the chain so the pre-existing raw-bus
 *     DROP_OLDEST — store keeps them upstream, D-03 — does not make this flaky.)
 *  4. Sheddable events ARE thinned (sampling drops > 0) under the forced load.
 *
 * Runs under `withContext(Dispatchers.Default)` because the driver's collectors run on the
 * session's real `Dispatchers.Default` scope and await a live `subscriptionCount` — virtual time
 * would not drive them (the MetricsWiringTest convention).
 */
class LoadHarnessSmokeTest {
    @Test
    fun `forced-load flood reports three independent counters and protects structural events`() =
        runBlocking {
            withContext(Dispatchers.Default) {
                withTimeout(TIMEOUT_MS) {
                    val session = VizSession(sessionId = "load-harness-smoke")
                    val counters =
                        try {
                            // Tiny shedCapacity → the sheddable lane MUST overflow under FLOOD_N,
                            // forcing the sampling counter positive deterministically.
                            EgressLoadDriver.run(session, n = FLOOD_N, shedCapacity = TINY_SHED_CAP)
                        } finally {
                            session.close()
                        }

                    // (D-11) Store lane is isolated: the harness bypasses VizSession.send, so the
                    // store is never written and its drop counter stays at zero.
                    assertEquals(
                        0L,
                        counters.storeDrops,
                        "store drops must be 0 — the harness injects at eventBus.send, bypassing the store (D-11)",
                    )

                    // (D-01/D-07) EVERY structural event that reached the egress chain must
                    // survive — the StructuralAwareBuffer never sheds lifecycle events.
                    assertEquals(
                        counters.structuralReceived,
                        counters.structuralSurvived,
                        "every structural (lifecycle) event reaching the egress chain must survive it (never shed)",
                    )

                    // (D-07/D-08) Under the forced load the sheddable lane overflows → sampling
                    // drops are positive AND sheddable survivors are strictly fewer than sent
                    // (thinning actually happened, the counters are not conflated).
                    assertTrue(
                        counters.samplingDrops > 0,
                        "sampling drops must be > 0 under forced load (sheddable lane overflow), was ${counters.samplingDrops}",
                    )
                    assertTrue(
                        counters.sheddableSurvived < counters.sheddableSent,
                        "sheddable events must be thinned under load: survived=${counters.sheddableSurvived} " +
                            "sent=${counters.sheddableSent}",
                    )

                    // (D-11) The sampling counter is attributable to the SAMPLING layer alone, not
                    // conflated with the bus lane: every sheddable event sent is accounted for as
                    // exactly one of survived / sampling-shed / bus-shed.
                    val sheddableBusShed = counters.busDrops - (counters.structuralSent - counters.structuralReceived)
                    assertEquals(
                        counters.sheddableSent,
                        counters.sheddableSurvived + counters.samplingDrops + sheddableBusShed,
                        "every sheddable event must be accounted for as survived + sampling-shed + bus-shed (non-conflated, D-11)",
                    )
                }
            }
        }

    private companion object {
        const val FLOOD_N = 50_000
        const val TINY_SHED_CAP = 100
        const val TIMEOUT_MS = 30_000L
    }
}
