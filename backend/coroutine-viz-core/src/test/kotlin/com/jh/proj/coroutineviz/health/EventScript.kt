package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.events.PollWatermark
import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.SuspensionProvenance
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.WaitingForChildren
import com.jh.proj.coroutineviz.events.coroutine.CoroutineBodyCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.events.dispatcher.DispatcherSelected
import com.jh.proj.coroutineviz.events.dispatcher.ThreadAssigned

fun sec(n: Long): Long = n * 1_000_000_000L

fun ms(n: Long): Long = n * 1_000_000L

/** Builds an ordered event list with increasing seqs, the way a session would record it. */
class EventScript(
    private val sessionId: String = "health-test",
) {
    private var seq = 0L
    private val parents = HashMap<String, String?>()
    val events = mutableListOf<VizEvent>()
    val lastSeq: Long get() = seq

    fun created(
        id: String,
        at: Long,
        parent: String? = null,
        label: String? = id,
        origin: CoroutineOrigin? = CoroutineOrigin.APP,
        creationPoint: SuspensionPoint? = null,
    ) {
        parents[id] = parent
        events +=
            CoroutineCreated(
                sessionId = sessionId,
                seq = ++seq,
                tsNanos = at,
                coroutineId = id,
                jobId = "job-$id",
                parentCoroutineId = parent,
                scopeId = "scope",
                label = label,
                creationPoint = creationPoint,
                origin = origin,
            )
    }

    fun started(
        id: String,
        at: Long,
    ) {
        events += CoroutineStarted(sessionId, ++seq, at, id, "job-$id", parents[id], "scope", id)
    }

    fun resumed(
        id: String,
        at: Long,
    ) {
        events += CoroutineResumed(sessionId, ++seq, at, id, "job-$id", parents[id], "scope", id)
    }

    fun suspended(
        id: String,
        at: Long,
        point: SuspensionPoint? = SuspensionPoint(function = "work", fileName = "App.kt", lineNumber = 10, reason = "delay"),
        provenance: SuspensionProvenance? = SuspensionProvenance.REAL_FRAME,
    ) {
        events +=
            CoroutineSuspended(
                sessionId = sessionId,
                seq = ++seq,
                tsNanos = at,
                coroutineId = id,
                jobId = "job-$id",
                parentCoroutineId = parents[id],
                scopeId = "scope",
                label = id,
                reason = "delay",
                suspensionPoint = point,
                provenance = provenance,
            )
    }

    fun bodyCompleted(
        id: String,
        at: Long,
    ) {
        events += CoroutineBodyCompleted(sessionId, ++seq, at, id, "job-$id", parents[id], "scope", id)
    }

    fun completed(
        id: String,
        at: Long,
    ) {
        events += CoroutineCompleted(sessionId, ++seq, at, id, "job-$id", parents[id], "scope", id)
    }

    fun cancelled(
        id: String,
        at: Long,
    ) {
        events += CoroutineCancelled(sessionId, ++seq, at, id, "job-$id", parents[id], "scope", id, cause = null)
    }

    fun failed(
        id: String,
        at: Long,
        type: String? = "java.lang.IllegalStateException",
        message: String? = "boom",
    ) {
        events +=
            CoroutineFailed(
                sessionId = sessionId,
                seq = ++seq,
                tsNanos = at,
                coroutineId = id,
                jobId = "job-$id",
                parentCoroutineId = parents[id],
                scopeId = "scope",
                label = id,
                exceptionType = type,
                message = message,
                stackTrace = emptyList(),
            )
    }

    fun thread(
        id: String,
        at: Long,
        name: String,
        threadId: Long = 1,
        dispatcher: String? = "Dispatchers.Default",
    ) {
        events +=
            ThreadAssigned(
                sessionId = sessionId,
                seq = ++seq,
                tsNanos = at,
                coroutineId = id,
                jobId = "job-$id",
                parentCoroutineId = parents[id],
                scopeId = "scope",
                label = id,
                threadId = threadId,
                threadName = name,
                dispatcherName = dispatcher,
            )
    }

    fun dispatcherSelected(
        id: String,
        at: Long,
        name: String,
    ) {
        events += DispatcherSelected(sessionId, ++seq, at, id, "job-$id", parents[id], "scope", id, "dispatcher-$name", name)
    }

    fun waitingForChildren(
        id: String,
        at: Long,
    ) {
        events +=
            WaitingForChildren(
                sessionId = sessionId,
                seq = ++seq,
                tsNanos = at,
                coroutineId = id,
                jobId = "job-$id",
                parentCoroutineId = parents[id],
                scopeId = "scope",
                label = id,
                activeChildrenCount = 1,
                activeChildrenIds = listOf("child-of-$id"),
            )
    }

    fun watermark(
        at: Long,
        epochMs: Long = 1_792_000_000_000,
        lost: Long = 0,
        uncertain: Boolean = false,
    ) {
        events += PollWatermark(sessionId, ++seq, at, epochMs, lostEvents = lost, deliveryUncertain = uncertain)
    }

    fun table(
        fullSummaryTarget: Int = HealthThresholds.DEFAULT_FULL_SUMMARY_TARGET,
        trackedCapacity: Int = HealthThresholds.DEFAULT_TRACKED_CAPACITY,
    ): SummaryTable = SummaryTable(fullSummaryTarget, trackedCapacity).also { table -> events.forEach(table::apply) }
}
