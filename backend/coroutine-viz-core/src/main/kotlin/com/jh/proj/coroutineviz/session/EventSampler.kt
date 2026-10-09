package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.VizEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Tunable thresholds for the adaptive throughput gate (D-02).
 *
 * Two watermarks give the gate HYSTERESIS so it does not flap when observed throughput hovers
 * at the boundary (Pitfall P8): sampling ENGAGES once the observed rate exceeds
 * [highWatermarkPerSec] and stays engaged until the rate falls below [lowWatermarkPerSec].
 *
 * @property windowNanos Sliding-window span used to estimate events/sec (same idiom as
 *   [MetricsProjection]). Defaults to 1 second.
 * @property highWatermarkPerSec Observed events/sec at or above which sampling engages.
 * @property lowWatermarkPerSec Observed events/sec below which sampling disengages
 *   (must be <= [highWatermarkPerSec] for hysteresis).
 */
data class AdaptiveConfig(
    val windowNanos: Long = DEFAULT_WINDOW_NANOS,
    val highWatermarkPerSec: Double = DEFAULT_HIGH_WATERMARK_PER_SEC,
    val lowWatermarkPerSec: Double = DEFAULT_LOW_WATERMARK_PER_SEC,
) {
    init {
        require(windowNanos > 0) { "windowNanos must be positive, got $windowNanos" }
        require(highWatermarkPerSec >= lowWatermarkPerSec) {
            "highWatermarkPerSec ($highWatermarkPerSec) must be >= lowWatermarkPerSec ($lowWatermarkPerSec)"
        }
        require(lowWatermarkPerSec >= 0.0) { "lowWatermarkPerSec must be >= 0, got $lowWatermarkPerSec" }
    }

    companion object {
        /** 1-second throughput estimation window. */
        const val DEFAULT_WINDOW_NANOS = 1_000_000_000L

        /** Engage sampling above ~500 events/sec (research-informed default, D-02). */
        const val DEFAULT_HIGH_WATERMARK_PER_SEC = 500.0

        /** Disengage below ~300 events/sec — the hysteresis gap avoids boundary flapping. */
        const val DEFAULT_LOW_WATERMARK_PER_SEC = 300.0
    }
}

/**
 * Probabilistic, structural-aware, optionally-adaptive egress event sampler.
 *
 * ## Structural protection (PERF-01 / D-01)
 * Structural events (coroutine lifecycle create/complete/cancel, topology, birth/death) are
 * ALWAYS kept, regardless of configured rate or load. Protection is delegated to
 * [StructuralClassifier.isStructural] — the single, explicit, auditable allow-set shared with
 * the Plan-02 shed buffer. This replaces the old leaky lifecycle-suffix heuristic.
 *
 * ## Deterministic per-type sampling
 * For a sheddable event the keep/drop decision is deterministic in [VizEvent.seq] and the
 * effective rate: the same seq + rate always yields the same decision (multiplicative-hash).
 *
 * ## Adaptive throughput gate (PERF-01 / D-02)
 * When [adaptive] is true the sampler observes its own arrival rate over a sliding window
 * ([AdaptiveConfig.windowNanos]) and runs at FULL FIDELITY (keeps everything non-structural)
 * until the observed rate crosses [AdaptiveConfig.highWatermarkPerSec]. Once ENGAGED it applies
 * the configured per-type rates until the rate falls below [AdaptiveConfig.lowWatermarkPerSec]
 * (two-watermark hysteresis — see [AdaptiveConfig]). When [adaptive] is false the configured
 * rates apply unconditionally (the original, byte-equivalent behavior).
 *
 * @property defaultRate Default sampling rate for kinds without a per-type override
 *   (1.0 = keep all, 0.0 = drop all, 0.5 = keep ~50%).
 * @property perTypeRates Per-[VizEvent.kind] rate overrides.
 * @property adaptive Whether the adaptive throughput gate is active (default off, D-02 wires it on).
 * @property adaptiveConfig Threshold/window tuning for the adaptive gate.
 */
class EventSampler(
    private val defaultRate: Double = 1.0,
    perTypeRates: Map<String, Double> = emptyMap(),
    private val adaptive: Boolean = false,
    private val adaptiveConfig: AdaptiveConfig = AdaptiveConfig(),
) {
    private val perTypeRates = ConcurrentHashMap<String, Double>(perTypeRates)

    /** Recent arrival timestamps (nanos), bounded to [AdaptiveConfig.windowNanos]. */
    private val recentArrivalNanos = ArrayDeque<Long>()

    /** Hysteresis state: once true, sampling stays engaged until the rate drops below the low watermark. */
    private var engaged = false

    private val gateLock = Any()

    init {
        require(defaultRate in 0.0..1.0) { "defaultRate must be in [0.0, 1.0], got $defaultRate" }
        perTypeRates.forEach { (kind, rate) ->
            require(rate in 0.0..1.0) { "Rate for '$kind' must be in [0.0, 1.0], got $rate" }
        }
    }

    companion object {
        /** Large prime for deterministic hash-based sampling; mixing reduces sequential-seq clustering. */
        private const val HASH_PRIME = 2_654_435_761L

        /** Scale factor: map a 32-bit hash to the [0.0, 1.0) range. */
        private const val UINT_MAX_PLUS_ONE = 4_294_967_296.0 // 2^32

        /** Nanos per second, for events/sec conversion. */
        private const val NANOS_PER_SEC = 1_000_000_000.0
    }

    /**
     * Determines whether [event] should be kept (true) or dropped (false).
     *
     * Structural kinds are always kept (delegated to [StructuralClassifier]). For sheddable
     * kinds, when [adaptive] is on and the throughput gate is NOT engaged, the event is kept at
     * full fidelity; otherwise the deterministic per-type rate decision applies.
     *
     * @param nowNanos monotonic read clock for the adaptive gate; defaulted to [System.nanoTime]
     *   for production and supplied explicitly by tests to advance the window deterministically.
     */
    fun shouldKeep(event: VizEvent, nowNanos: Long = System.nanoTime()): Boolean {
        if (StructuralClassifier.isStructural(event.kind)) return true

        if (adaptive && !isGateEngaged(nowNanos)) return true

        val rate = getEffectiveRate(event.kind)
        if (rate >= 1.0) return true
        if (rate <= 0.0) return false

        return deterministicKeep(event.seq, rate)
    }

    /**
     * Record an arrival and recompute the two-watermark hysteresis gate against [nowNanos].
     * Returns true when sampling is currently ENGAGED (rates should apply).
     */
    private fun isGateEngaged(nowNanos: Long): Boolean =
        synchronized(gateLock) {
            recentArrivalNanos.addLast(nowNanos)
            evictOlderThan(nowNanos - adaptiveConfig.windowNanos)

            val ratePerSec = observedRatePerSec(nowNanos)
            if (!engaged && ratePerSec >= adaptiveConfig.highWatermarkPerSec) {
                engaged = true
            } else if (engaged && ratePerSec < adaptiveConfig.lowWatermarkPerSec) {
                engaged = false
            }
            engaged
        }

    /** Drop arrivals older than [cutoffNanos] from the front of the window. */
    private fun evictOlderThan(cutoffNanos: Long) {
        while (recentArrivalNanos.isNotEmpty() && recentArrivalNanos.first() < cutoffNanos) {
            recentArrivalNanos.removeFirst()
        }
    }

    /**
     * Observed events/sec = number of arrivals retained in the last [AdaptiveConfig.windowNanos]
     * divided by the window duration in seconds. Using the FIXED window denominator (rather than
     * the span between the first and last retained arrival) keeps the estimate well-defined for a
     * burst that lands at a single instant (span 0) and matches the natural "events in the last
     * window" interpretation. [nowNanos] is unused here (eviction already trimmed the window) but
     * kept for signature symmetry with the eviction step.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun observedRatePerSec(nowNanos: Long): Double {
        val windowSeconds = adaptiveConfig.windowNanos / NANOS_PER_SEC
        return recentArrivalNanos.size / windowSeconds
    }

    /**
     * Updates the sampling rate for [eventKind] at runtime (D-02 "configurable thresholds").
     *
     * @throws IllegalArgumentException if [rate] is outside [0.0, 1.0].
     */
    fun updateRate(eventKind: String, rate: Double) {
        require(rate in 0.0..1.0) { "Rate for '$eventKind' must be in [0.0, 1.0], got $rate" }
        perTypeRates[eventKind] = rate
    }

    /**
     * Returns the effective sampling rate for [eventKind] — the per-type override if configured,
     * otherwise [defaultRate].
     */
    fun getEffectiveRate(eventKind: String): Double =
        perTypeRates[eventKind] ?: defaultRate

    /**
     * Deterministic keep/drop decision based on [seq].
     *
     * Multiplicative hash maps [seq] to [0.0, 1.0), compared against [rate], so the same
     * seq + rate always yields the same decision.
     */
    private fun deterministicKeep(seq: Long, rate: Double): Boolean {
        val hash = (seq * HASH_PRIME) and 0xFFFFFFFFL
        val normalized = hash / UINT_MAX_PLUS_ONE // [0.0, 1.0)
        return normalized < rate
    }
}
