package com.jh.proj.coroutineviz.observability

import com.jh.proj.coroutineviz.harness.EgressLoadDriver
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * OTEL-01 zero-cost-when-off proof — the THROUGHPUT half (the construction-gate half lives in
 * [OtelGatingTest]).
 *
 * Floods N synthetic events through the real `EventBus.send` → egress chain ([EgressLoadDriver])
 * twice over a private [VizSession]:
 *  - **OFF**: no [CoroutineSpanExporter] attached — the baseline `send()` throughput.
 *  - **ON**: a [CoroutineSpanExporter] (fed by an [OtelTracing] SDK pointed at an UNREACHABLE
 *    collector) is subscribed to the bus before the same flood. The exporter is an additional bus
 *    subscriber turning lifecycle events into OTLP spans handed to a bounded `BatchSpanProcessor`.
 *
 * Two guarantees:
 *  1. The ON run COMPLETES (bounded by [RUN_TIMEOUT_MS]) — a stalled/unreachable collector must not
 *     back-pressure emission (`BatchSpanProcessor` is bounded + drops on overflow, off `sendLock`,
 *     D-11 / T-12-10). If ON blocked, this test would time out.
 *  2. The ON throughput is within a TOLERANT noise band of OFF (not exact equality — throughput is
 *     environment-sensitive). The assertion is a generous floor, not a tight bound.
 *
 * Runs under `withContext(Dispatchers.Default)` — the egress collectors and the exporter's bus
 * collector run on the session's real `Dispatchers.Default` scope; virtual time would not drive
 * them (the LoadHarnessSmokeTest convention). [SessionManager] listeners are reset around each run
 * (object-singleton accretion guard).
 */
class OtelOverheadTest {
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
    fun `off-vs-on send throughput delta stays within a tolerant noise band and the on run never blocks`() =
        runBlocking {
            withContext(Dispatchers.Default) {
                // ── OFF: baseline flood, no exporter attached ──
                val offNanos = floodAndTime(attachExporter = false, tracing = null)

                // ── ON: build the SDK against an UNREACHABLE collector, attach a per-session
                // exporter, then flood the same N. Collector-down must NOT block emission (D-11). ──
                val tracing =
                    OtelTracing.build(
                        OtelConfig(
                            enabled = true,
                            endpoint = UNREACHABLE_ENDPOINT,
                            serviceName = "otel-overhead-test",
                            samplingRatio = 1.0,
                        ),
                    )
                val onNanos =
                    try {
                        floodAndTime(attachExporter = true, tracing = tracing)
                    } finally {
                        tracing.shutdown()
                    }

                // (1) The ON run completed (floodAndTime is itself bounded by RUN_TIMEOUT_MS); reaching
                // here at all proves the unreachable collector did not block emission.
                assertTrue(onNanos > 0, "ON flood must complete and report a positive duration")

                // (2) Tolerant noise band: ON must not be pathologically slower than OFF. A generous
                // multiplier absorbs JIT/GC/scheduling jitter — this is a "no order-of-magnitude
                // regression" floor, NOT an exact-equality assertion (throughput is environment-sensitive).
                assertTrue(
                    onNanos <= offNanos * MAX_SLOWDOWN_FACTOR,
                    "ON flood ($onNanos ns) must stay within ${MAX_SLOWDOWN_FACTOR}x of OFF ($offNanos ns) — " +
                        "off-vs-on throughput delta within noise (OTEL-01 SC#1)",
                )
            }
        }

    /**
     * Flood [FLOOD_N] events through a fresh session and return the elapsed nanos. When
     * [attachExporter] is true, a [CoroutineSpanExporter] (from [tracing]) is subscribed to the bus
     * BEFORE the flood so the ON path exercises the real per-session span emission. Bounded by
     * [RUN_TIMEOUT_MS] so a (regression) block surfaces as a timeout, not a hang.
     */
    private suspend fun floodAndTime(
        attachExporter: Boolean,
        tracing: OtelTracing?,
    ): Long {
        val session = VizSession(sessionId = "otel-overhead-${if (attachExporter) "on" else "off"}")
        if (attachExporter) {
            requireNotNull(tracing) { "tracing must be provided when attachExporter=true" }
            CoroutineSpanExporter(session, tracing.tracer)
        }
        return try {
            val start = System.nanoTime()
            withTimeout(RUN_TIMEOUT_MS) {
                EgressLoadDriver.run(session, n = FLOOD_N)
            }
            System.nanoTime() - start
        } finally {
            session.close()
        }
    }

    private companion object {
        const val FLOOD_N = 20_000

        /** A syntactically valid OTLP/gRPC endpoint that refuses connections (collector-down). */
        const val UNREACHABLE_ENDPOINT = "http://localhost:1"

        /** Per-flood timeout — a (regression) emission block surfaces here as a failure, not a hang. */
        const val RUN_TIMEOUT_MS = 30_000L

        /** Generous slowdown ceiling — absorbs JIT/GC/scheduling jitter (no order-of-magnitude regression). */
        const val MAX_SLOWDOWN_FACTOR = 8.0
    }
}
