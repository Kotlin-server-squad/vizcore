package com.jh.proj.coroutineviz.models

import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.SuspensionProvenance
import com.jh.proj.coroutineviz.events.WaitKind
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
    // The phase an unfinished coroutine has been in since its last recorded transition
    // ("ACTIVE" or "SUSPENDED"), and that transition's tsNanos. Null once finished.
    val openPhase: String? = null,
    val openPhaseSinceNanos: Long? = null,
    // Creation on the emitting JVM's two clocks: tsNanos (monotonic) and wall-clock millis.
    // With [observedAtEpochMs] a client can estimate the emitting JVM's "now" in tsNanos
    // terms (assuming the two hosts' wall clocks agree, as the leak age does).
    val createdAtNanos: Long? = null,
    val createdAtEpochMs: Long? = null,
    // The backend's wall clock when it built this timeline.
    val observedAtEpochMs: Long? = null,
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
    // What a mutex, semaphore or channel wrapper waited for, and which one (suspend rows only; spec 2026-11-05, S-7)
    val waitKind: WaitKind? = null,
    val resourceId: String? = null,
    // Whether [suspensionPoint] is a frame the SDK read or a fallback (triage "Suspension-point provenance"); suspend rows
    // only, null when the producer did not say
    val provenance: SuspensionProvenance? = null,
) {
    /** The 0.4.x JVM constructor (no [provenance]), kept for binary compatibility. `copy` is source-compatible only. */
    @Deprecated("binary compatibility with 0.4.x", level = DeprecationLevel.HIDDEN)
    constructor(
        seq: Long,
        tsNanos: Long,
        kind: String,
        threadName: String?,
        dispatcherName: String?,
        reason: String?,
        suspensionPoint: SuspensionPoint?,
        waitKind: WaitKind?,
        resourceId: String?,
    ) : this(seq, tsNanos, kind, threadName, dispatcherName, reason, suspensionPoint, waitKind, resourceId, null)

    /** The 0.3.x JVM constructor (no [waitKind] / [resourceId]), kept for binary compatibility. `copy` is source-compatible only. */
    @Deprecated("binary compatibility with 0.3.x", level = DeprecationLevel.HIDDEN)
    constructor(
        seq: Long,
        tsNanos: Long,
        kind: String,
        threadName: String? = null,
        dispatcherName: String? = null,
        reason: String? = null,
        suspensionPoint: SuspensionPoint? = null,
    ) : this(seq, tsNanos, kind, threadName, dispatcherName, reason, suspensionPoint, null, null, null)
}
