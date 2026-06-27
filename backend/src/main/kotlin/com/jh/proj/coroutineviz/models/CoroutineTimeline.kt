package com.jh.proj.coroutineviz.models

import com.jh.proj.coroutineviz.events.SuspensionPoint
import kotlinx.serialization.Serializable

/**
 * Timeline view for a specific coroutine, with computed durations and aggregated information.
 */
@Serializable
data class CoroutineTimeline(
    val coroutineId: String,
    val name: String,
    val state: String,
    // Total time from creation to completion (nanos)
    val totalDuration: Long?,
    // Time spent actively running (not suspended)
    val activeDuration: Long? = null,
    // Time spent suspended
    val suspendedDuration: Long? = null,
    val parentId: String? = null,
    val childrenIds: List<String> = emptyList(),
    val events: List<TimelineEventSummary> = emptyList(),
)

/**
 * Simplified event summary for timeline display
 */
@Serializable
data class TimelineEventSummary(
    val seq: Long,
    val tsNanos: Long,
    val kind: String,
    val threadName: String? = null,
    val dispatcherName: String? = null,
    // For suspension events
    val reason: String? = null,
    // Source code location where a coroutine suspended (only populated for suspend rows)
    val suspensionPoint: SuspensionPoint? = null,
)
