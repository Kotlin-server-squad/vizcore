package com.jh.proj.coroutineviz.observability

import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * OTEL-01 zero-cost-when-off proof — the CONSTRUCTION-GATE half (the throughput half lives in
 * [OtelOverheadTest]).
 *
 * Every [VizSession] constructs its own internal `ProjectionService` + `MetricsProjection`, each of
 * which subscribes to the bus in its `init`. So a bare session already has a NON-ZERO bus
 * subscriptionCount baseline that has nothing to do with OTel. The OTEL-01 guarantee is therefore a
 * DELTA: when OTel is disabled, a `module()`-created session must add NO extra subscriber over that
 * bare baseline; when enabled, it must add EXACTLY ONE (the per-session [CoroutineSpanExporter]).
 * The baseline is measured live from a bare [VizSession] so the test is robust to how many internal
 * projections a session carries.
 *
 * Also asserts the disabled path constructs no NEW `BatchSpanProcessor` worker thread — the
 * disabled-path tell (Pitfall 2). The check is a BEFORE/AFTER delta rather than an absolute
 * count: JUnit runs the whole class in one JVM, so an enabled sibling test may leave a (dying)
 * BatchSpanProcessor daemon behind; what OTEL-01 actually forbids is the disabled `module()`
 * SPAWNING one, i.e. the count must not INCREASE across booting the disabled app.
 *
 * [SessionManager] is an object singleton that accretes listeners across tests, so both registries
 * are reset in `@BeforeEach`/`@AfterEach` (the MetricsWiringTest listener-reset convention). The
 * bus collectors subscribe asynchronously on real `Dispatchers.Default`, so subscription awaits run
 * under `withContext(Dispatchers.Default)` (virtual time would not drive them).
 */
class OtelGatingTest {
    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
        SessionManager.clearSessionListeners()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.clearAll()
        SessionManager.clearSessionListeners()
    }

    @Test
    fun `disabled - session adds no extra bus subscriber over the bare baseline and no worker thread`() =
        testApplication {
            environment {
                config = MapApplicationConfig() // observability.otel.enabled absent ⇒ default false
            }
            application { module() }

            // Count any pre-existing BatchSpanProcessor daemons (possibly left dying by an enabled
            // sibling test) BEFORE booting the disabled app, so we measure only what THIS boot adds.
            val batchThreadsBefore = batchSpanProcessorThreadCount()
            startApplication()

            withContext(Dispatchers.Default) {
                // Bare-session baseline: a VizSession with NO module()/OTel wiring at all. Its own
                // ProjectionService + MetricsProjection subscribe in init, so this is the non-OTel
                // subscriber count we compare against.
                val baseline = bareSessionSubscriberBaseline()

                val session = SessionManager.createSession("otel-gating-off")
                // Give any (non-existent) exporter subscriber a bounded window to appear, then read.
                val count = awaitAtLeastOrTimeout(session.eventBus.subscriptionCount, baseline + 1)

                assertEquals(
                    baseline,
                    count,
                    "disabled OTel must add NO bus subscriber over the bare-session baseline " +
                        "($baseline) — no CoroutineSpanExporter was registered",
                )
                assertFalse(
                    batchSpanProcessorThreadCount() > batchThreadsBefore,
                    "disabled OTel must construct NO NEW BatchSpanProcessor worker thread (Pitfall 2): " +
                        "before=$batchThreadsBefore after=${batchSpanProcessorThreadCount()}",
                )
            }
        }

    @Test
    fun `enabled - session adds exactly one extra bus subscriber over the bare baseline (gate is not vacuous)`() =
        testApplication {
            environment {
                config =
                    MapApplicationConfig(
                        "observability.otel.enabled" to "true",
                        // A dummy/unreachable endpoint: the gate + wiring must build regardless of
                        // collector reachability (collector-down must not block, D-11).
                        "observability.otel.endpoint" to "http://localhost:1",
                    )
            }
            application { module() }
            startApplication()

            withContext(Dispatchers.Default) {
                val baseline = bareSessionSubscriberBaseline()

                val session = SessionManager.createSession("otel-gating-on")
                // The CoroutineSpanExporter subscribes to the bus asynchronously in its init; await
                // the baseline+1 subscriber count.
                val count =
                    withTimeout(SUBSCRIBE_TIMEOUT_MS) {
                        session.eventBus.subscriptionCount.first { it >= baseline + 1 }
                    }
                assertEquals(
                    baseline + 1,
                    count,
                    "enabled OTel must add EXACTLY ONE per-session CoroutineSpanExporter subscriber " +
                        "over the bare-session baseline ($baseline)",
                )
            }
        }

    /**
     * Construct a bare [VizSession] (no `module()`, no OTel) and return the bus subscriptionCount
     * once its own internal projections have subscribed. This is the non-OTel baseline the gating
     * assertions compare against. The session is closed before returning so it leaks nothing.
     */
    private suspend fun bareSessionSubscriberBaseline(): Int {
        val probe = VizSession(sessionId = "otel-gating-baseline-probe")
        return try {
            // A bare session has MULTIPLE internal projections (ProjectionService + MetricsProjection)
            // that each subscribe asynchronously — so wait until the count SETTLES (stops advancing
            // across a short window), not just until it first reaches 1. Reading too early would
            // catch a partially-subscribed count and make the delta assertion flaky.
            awaitSettled(probe.eventBus.subscriptionCount)
        } finally {
            probe.close()
        }
    }

    /**
     * Suspend until [counter] stops advancing across [settleMs], then return the settled value.
     * Bounded by [SUBSCRIBE_TIMEOUT_MS] so a pathological stall surfaces as a test timeout.
     */
    private suspend fun awaitSettled(counter: kotlinx.coroutines.flow.StateFlow<Int>): Int =
        withTimeout(SUBSCRIBE_TIMEOUT_MS) {
            var previous = -1
            while (true) {
                val current = counter.value
                if (current == previous && current >= 1) return@withTimeout current
                previous = current
                kotlinx.coroutines.delay(SETTLE_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            counter.value
        }

    /**
     * Await [counter] reaching [target] within a bounded window; return the value observed at the
     * end of the window (which may be below [target] if nothing further subscribed). Used for the
     * NEGATIVE assertion — we expect the target is NEVER reached, so we time out and read the value.
     */
    private suspend fun awaitAtLeastOrTimeout(
        counter: kotlinx.coroutines.flow.StateFlow<Int>,
        target: Int,
    ): Int =
        try {
            withTimeout(NO_SUBSCRIBE_WINDOW_MS) {
                counter.first { it >= target }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            counter.value
        }

    private fun batchSpanProcessorThreadCount(): Int =
        Thread.getAllStackTraces().keys.count { thread ->
            thread.name.contains("BatchSpanProcessor", ignoreCase = true)
        }

    private companion object {
        /** Bounded wait for the (expected) enabled-path / baseline subscriber to appear. */
        const val SUBSCRIBE_TIMEOUT_MS = 5_000L

        /** Bounded window to confirm NO additional subscriber appears on the disabled path. */
        const val NO_SUBSCRIBE_WINDOW_MS = 750L

        /** Settle interval for the quiescence-based baseline read. */
        const val SETTLE_MS = 50L
    }
}
