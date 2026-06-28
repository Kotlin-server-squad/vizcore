package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.VizEvent
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves the ADAPTIVE [EventSampler] behavior (D-01/D-02):
 * - structural kinds are protected absolutely via [StructuralClassifier] (never the deleted suffix rule),
 * - below the load threshold every sheddable event is kept (full fidelity),
 * - above the threshold the configured per-type rate engages,
 * - hysteresis (two watermarks) prevents flapping at the boundary,
 * - the deterministic seq-based decision is unchanged.
 *
 * The adaptive gate is driven by an injectable [nowNanos] clock so throughput can be advanced
 * deterministically, mirroring the [MetricsProjection.snapshot] test idiom.
 */
class EventSamplerAdaptiveTest {
    private data class FakeEvent(
        override val sessionId: String = "s1",
        override var seq: Long,
        override val tsNanos: Long,
        override val kind: String,
    ) : VizEvent

    private fun sheddable(seq: Long, tsNanos: Long): FakeEvent =
        FakeEvent(seq = seq, tsNanos = tsNanos, kind = "FlowValueEmitted")

    private fun structural(seq: Long, kind: String): FakeEvent =
        FakeEvent(seq = seq, tsNanos = 0L, kind = kind)

    /** Feed [count] arrivals within one nanosecond instant so the 1s window observes a high rate. */
    private fun engageLoad(sampler: EventSampler, count: Int, atNanos: Long) {
        repeat(count) { i ->
            sampler.shouldKeep(sheddable(seq = i.toLong(), tsNanos = atNanos), nowNanos = atNanos)
        }
    }

    // -- structural protection (delegates to StructuralClassifier, not the deleted suffix rule) --

    @Test
    fun `structural kinds are kept at rate 0_0 even when adaptive and engaged`() {
        val sampler = EventSampler(defaultRate = 0.0, adaptive = true)
        // Drive load so the gate is engaged.
        engageLoad(sampler, count = 1_000, atNanos = 1_000L)

        for (kind in listOf("CoroutineCreated", "CoroutineCompleted", "CoroutineCancelled")) {
            assertTrue(
                sampler.shouldKeep(structural(seq = 1L, kind = kind), nowNanos = 1_000L),
                "$kind must be kept absolutely (D-01)",
            )
        }
    }

    // -- below threshold: full fidelity --

    @Test
    fun `below threshold keeps every sheddable event regardless of configured rate`() {
        // Per-type rate 0.0 would drop everything in non-adaptive mode; adaptive full-fidelity keeps them.
        val sampler = EventSampler(
            defaultRate = 0.0,
            perTypeRates = mapOf("FlowValueEmitted" to 0.0),
            adaptive = true,
        )
        // A handful of arrivals at the same instant → observed rate ~ a few events, well below threshold.
        var kept = 0
        for (seq in 1L..10L) {
            if (sampler.shouldKeep(sheddable(seq, tsNanos = seq), nowNanos = seq)) kept++
        }
        assertTrue(kept == 10, "below threshold should keep all 10, kept=$kept")
    }

    // -- above threshold: sampling engages --

    @Test
    fun `above threshold honors the configured per-type rate`() {
        val sampler = EventSampler(
            defaultRate = 1.0,
            perTypeRates = mapOf("FlowValueEmitted" to 0.0),
            adaptive = true,
        )
        // Push the observed rate above the high watermark at a fixed instant.
        engageLoad(sampler, count = 1_000, atNanos = 1_000L)

        // Now the FlowValueEmitted rate (0.0) should engage → drop.
        var kept = 0
        for (seq in 0L until 50L) {
            if (sampler.shouldKeep(sheddable(seq, tsNanos = 1_000L), nowNanos = 1_000L)) kept++
        }
        assertTrue(kept == 0, "above threshold with rate 0.0 should drop all, kept=$kept")
    }

    // -- hysteresis: no flapping at the boundary --

    @Test
    fun `once engaged sampling stays engaged between the two watermarks`() {
        val sampler = EventSampler(
            defaultRate = 1.0,
            perTypeRates = mapOf("FlowValueEmitted" to 0.0),
            adaptiveConfig = AdaptiveConfig(
                windowNanos = 1_000_000_000L,
                highWatermarkPerSec = 500.0,
                lowWatermarkPerSec = 300.0,
            ),
            adaptive = true,
        )
        // Cross the HIGH watermark (>500/s) with a burst at t0 to engage.
        engageLoad(sampler, count = 600, atNanos = 1_000L)
        assertFalse(
            sampler.shouldKeep(sheddable(1L, tsNanos = 1_000L), nowNanos = 1_000L),
            "should be engaged after crossing high watermark",
        )

        // Now feed a CONTINUOUS stream at ~450/s — strictly BETWEEN the two watermarks (300..500):
        // never crossing back above high, never falling below low. The window never empties, so a
        // hysteresis gate must STAY engaged (a single-threshold gate would drop out at <500/s).
        // ~450/s = one arrival every ~2.22ms; feed for ~2s so the trailing 1s window holds ~450.
        val spacingNanos = 1_000_000_000L / 450L
        var kept = 0
        var t = 1_000L
        for (i in 0 until 900) {
            t += spacingNanos
            if (sampler.shouldKeep(sheddable(i.toLong(), tsNanos = t), nowNanos = t)) kept++
        }
        assertTrue(kept == 0, "hysteresis: must stay engaged in the 300..500 band, kept=$kept")
    }

    @Test
    fun `falling below the low watermark disengages back to full fidelity`() {
        val sampler = EventSampler(
            defaultRate = 1.0,
            perTypeRates = mapOf("FlowValueEmitted" to 0.0),
            adaptiveConfig = AdaptiveConfig(
                windowNanos = 1_000_000_000L,
                highWatermarkPerSec = 500.0,
                lowWatermarkPerSec = 300.0,
            ),
            adaptive = true,
        )
        // Engage at t0.
        engageLoad(sampler, count = 600, atNanos = 1_000L)
        assertFalse(sampler.shouldKeep(sheddable(1L, tsNanos = 1_000L), nowNanos = 1_000L))

        // Let throughput collapse: advance far past the window with a single sparse arrival.
        val quietT = 10_000_000_000L
        // First arrival recomputes the (now nearly empty) window → below low watermark → disengage.
        val keptQuiet = sampler.shouldKeep(sheddable(2L, tsNanos = quietT), nowNanos = quietT)
        assertTrue(keptQuiet, "after throughput collapse the gate disengages → full fidelity keeps the event")
    }

    // -- deterministic decision preserved --

    @Test
    fun `engaged deterministic decision matches a non-adaptive sampler at the same rate`() {
        val adaptiveEngaged = EventSampler(defaultRate = 0.5, adaptive = true)
        engageLoad(adaptiveEngaged, count = 1_000, atNanos = 1_000L)

        val plain = EventSampler(defaultRate = 0.5, adaptive = false)

        for (seq in 1L..200L) {
            val e = sheddable(seq, tsNanos = 1_000L)
            assertTrue(
                adaptiveEngaged.shouldKeep(e, nowNanos = 1_000L) == plain.shouldKeep(e),
                "engaged adaptive decision must equal the plain deterministic decision for seq $seq",
            )
        }
    }
}
