package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.SuspensionProvenance
import com.jh.proj.coroutineviz.models.CoroutineState
import kotlinx.serialization.Serializable

@Serializable
enum class TransitionKind { CREATED, STARTED, SUSPENDED, RESUMED, BODY_COMPLETED, COMPLETED, CANCELLED, FAILED }

/** One observed lifecycle transition: its kind, app-clock time and session sequence. */
@Serializable
data class Transition(
    val kind: TransitionKind,
    val appNanos: Long,
    val seq: Long,
)

/** A thread assignment and the sequence it was recorded at. */
@Serializable
data class ThreadObservation(
    val threadId: Long,
    val threadName: String,
    val seq: Long,
)

@Serializable
data class FailureInfo(
    val exceptionType: String?,
    val message: String?,
)

/**
 * The durable per-coroutine summary (spec 2026-10-21, C-2), updated on each event by [SummaryUpdater]
 * and never rebuilt from retained events. A [reduced] summary is the structural stub: identity,
 * ancestry, name, location, times and flags only.
 *
 * [origin] is `null` when the producer did not report one. [failed] is true for a terminal failure
 * that is not a known cancellation and survives reduction. [promotedAtSeq] is set the first time a running
 * genuine root qualifies as a container; new active children never clear it; the root finishing clears it
 * unless the root got a new direct child after promotion ([grewAfterPromotion], kept on the summary so a
 * restored table decides the same way) and was cancelled ([cancelled] and not [failed]).
 */
@Serializable
data class CoroutineSummary(
    val id: String,
    val parentId: String?,
    val origin: CoroutineOrigin?,
    val name: String?,
    val creationLocation: SuspensionPoint?,
    val createdAtEpochMs: Long,
    val firstObserved: Transition,
    val lastTransition: Transition,
    /** App-clock time of the latest event that touched this coroutine (its own last observation). */
    val lastObservedNanos: Long,
    val lastRunStart: Transition? = null,
    val everSuspended: Boolean = false,
    val terminal: Transition? = null,
    val failed: Boolean = false,
    /** Cancelled, or failed with a known cancellation exception; survives reduction. */
    val cancelled: Boolean = false,
    val failure: FailureInfo? = null,
    val lastSuspension: SuspensionPoint? = null,
    val lastSuspensionProvenance: SuspensionProvenance? = null,
    val lastThread: ThreadObservation? = null,
    val dispatcher: String? = null,
    val lastModifiedSeq: Long,
    val promotedAtSeq: Long? = null,
    val grewAfterPromotion: Boolean = false,
    val reduced: Boolean = false,
) {
    val isTerminal: Boolean get() = terminal != null

    val state: CoroutineState
        get() =
            when {
                failed -> CoroutineState.FAILED
                cancelled -> CoroutineState.CANCELLED
                else ->
                    when ((terminal ?: lastTransition).kind) {
                        TransitionKind.CREATED -> CoroutineState.CREATED
                        TransitionKind.STARTED, TransitionKind.RESUMED -> CoroutineState.ACTIVE
                        TransitionKind.SUSPENDED -> CoroutineState.SUSPENDED
                        TransitionKind.BODY_COMPLETED -> CoroutineState.WAITING_FOR_CHILDREN
                        TransitionKind.COMPLETED -> CoroutineState.COMPLETED
                        TransitionKind.CANCELLED -> CoroutineState.CANCELLED
                        TransitionKind.FAILED -> CoroutineState.FAILED
                    }
            }

    /**
     * The structural stub kept after reduction (spec C-2, "Reduction"). Only finished summaries are reduced
     * ([SummaryTable] enforces it): a stub has no [lastRunStart], so it can never be possibly blocked.
     */
    fun toStub(): CoroutineSummary =
        copy(
            lastRunStart = null,
            failure = null,
            lastSuspension = null,
            lastSuspensionProvenance = null,
            lastThread = null,
            dispatcher = null,
            reduced = true,
        )
}
