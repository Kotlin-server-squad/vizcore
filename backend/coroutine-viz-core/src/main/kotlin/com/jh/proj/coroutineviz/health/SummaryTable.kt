package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.VizEvent

class SummaryTable(
    @Suppress("UnusedPrivateProperty") private val fullSummaryTarget: Int = HealthThresholds.DEFAULT_FULL_SUMMARY_TARGET,
    @Suppress("UnusedPrivateProperty") private val trackedCapacity: Int = HealthThresholds.DEFAULT_TRACKED_CAPACITY,
) {
    @Suppress("UnusedParameter")
    fun apply(event: VizEvent) = Unit
}
