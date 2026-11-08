package com.jh.proj.coroutineviz.health

/**
 * The two limits of a session's summary table (spec C-2), as one value so every table of a host is built alike: the
 * live sessions, the replay fold and a database session rebuilt from its checkpoint. The backend reads them from
 * `health.summaries.*`; anything else uses [DEFAULT].
 *
 * @property fullSummaryTarget best-effort number of full summaries; finished ones beyond it are reduced to stubs.
 * @property trackedCapacity hard limit of tracked coroutines (summaries plus stubs); later ones are only counted.
 */
data class SummaryLimits(
    val fullSummaryTarget: Int = HealthThresholds.DEFAULT_FULL_SUMMARY_TARGET,
    val trackedCapacity: Int = HealthThresholds.DEFAULT_TRACKED_CAPACITY,
) {
    init {
        require(fullSummaryTarget > 0) { "fullSummaryTarget must be positive, was $fullSummaryTarget" }
        require(trackedCapacity > 0) { "trackedCapacity must be positive, was $trackedCapacity" }
        require(fullSummaryTarget <= trackedCapacity) {
            "fullSummaryTarget ($fullSummaryTarget) must not exceed trackedCapacity ($trackedCapacity)"
        }
    }

    companion object {
        /** The spec's limits: 50 000 full summaries, 100 000 tracked coroutines. */
        val DEFAULT = SummaryLimits()
    }
}
