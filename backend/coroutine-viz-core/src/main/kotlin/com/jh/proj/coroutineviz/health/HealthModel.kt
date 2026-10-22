package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.SuspensionProvenance
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SessionKind { ATTACHED, DEMO }

@Serializable
enum class SessionOrigin { REPORTED, UNKNOWN }

@Serializable
enum class AsOfKind { WATERMARK, INGEST, LAST_EVENT, REPLAY_CURSOR }

/**
 * The observation a health statement is about (spec H-2, C-3 `asOf`). [seq] is the last event sequence the
 * health reflects; [appNanos] is the latest app-clock time observed in the events up to [seq] (events can
 * be appended out of time order, so it need not be the time of the event at [seq]; it is never earlier
 * than anything the health includes). [appEpochMs] is the app's wall-clock time when known (only a
 * watermark carries it); [receivedEpochMs] the backend's receipt time.
 */
@Serializable
data class AsOf(
    val kind: AsOfKind,
    val appNanos: Long,
    val appEpochMs: Long? = null,
    val receivedEpochMs: Long? = null,
    val seq: Long,
)

/** A list capped at [HealthThresholds.LIST_CAP] entries, with the total before the cap. */
@Serializable
data class Capped<T>(
    val items: List<T>,
    val total: Int,
) {
    companion object {
        fun <T> of(all: List<T>): Capped<T> = Capped(all.take(HealthThresholds.LIST_CAP), all.size)
    }
}

@Serializable
enum class EvaluationStatus { EVALUATED, NOT_EVALUATED }

@Serializable
enum class NotEvaluatedReason { NO_WATERMARK, DEMO, REPLAY, OVERFLOW, EVENTS_DROPPED }

@Serializable
data class PossiblyBlockedEvaluation(
    val status: EvaluationStatus,
    val reason: NotEvaluatedReason? = null,
)

/** The health strip's state (spec "Health strip states"). */
@Serializable
enum class HealthState { AWAITING_FIRST_DATA, PROBLEMS, NO_PROBLEMS, NO_FAILURES_PARTIAL, NO_APP_COROUTINES }

@Serializable
enum class IncidentKind { FAILED, POSSIBLY_BLOCKED }

@Serializable
data class FailureEntry(
    val coroutineId: String,
    val name: String?,
    val exceptionType: String?,
    val message: String?,
    val failedAtSeq: Long,
    /** The summary was reduced to a stub: type and message are no longer known. */
    val detailsDropped: Boolean,
)

@Serializable
sealed interface Incident {
    val id: String
    val kind: IncidentKind

    /** Newest-first ordering key within a kind. */
    val sortSeq: Long
}

@Serializable
@SerialName("FAILED_ROUND")
data class FailedRoundIncident(
    override val id: String,
    val roundId: String,
    val anchorId: String,
    val failures: Capped<FailureEntry>,
    /** Cancellations in this round recorded after its first failure (context, not a cause). */
    val cancelledAfter: Capped<String>,
    val firstFailureSeq: Long,
    override val sortSeq: Long,
) : Incident {
    override val kind: IncidentKind get() = IncidentKind.FAILED
}

@Serializable
@SerialName("UNGROUPED_FAILURE")
data class UngroupedFailureIncident(
    override val id: String,
    val failure: FailureEntry,
    override val sortSeq: Long,
) : Incident {
    override val kind: IncidentKind get() = IncidentKind.FAILED
}

@Serializable
@SerialName("POSSIBLY_BLOCKED")
data class PossiblyBlockedIncident(
    override val id: String,
    val coroutineId: String,
    val name: String?,
    val roundId: String?,
    val anchorId: String?,
    /** The thread assigned in the current run, `null` when none was observed since the latest start/resume. */
    val lastThread: ThreadObservation?,
    val dispatcher: String?,
    val lastSuspension: SuspensionPoint?,
    val lastSuspensionProvenance: SuspensionProvenance?,
    val lastRunStart: Transition,
    val runningForNanos: Long,
    val transitions: List<Transition>,
    /** The retained transitions do not reach back to the first observation. */
    val transitionsGap: Boolean,
    /** No suspension was ever observed for this coroutine. */
    val firstObservedRunning: Boolean,
    /** The matched event-loop prefix, or `null` (a clue, never a verdict). */
    val eventLoopClue: String?,
    override val sortSeq: Long,
) : Incident {
    override val kind: IncidentKind get() = IncidentKind.POSSIBLY_BLOCKED
}

@Serializable
data class ObservedAge(
    val coroutineId: String,
    val name: String?,
    val ageNanos: Long,
)

@Serializable
data class Observations(
    val longLived: Capped<ObservedAge>,
    val longSuspended: Capped<ObservedAge>,
)

@Serializable
data class OriginCounts(
    val app: Int,
    val vizcore: Int,
    val unknown: Int,
    val untracked: Long,
)

@Serializable
data class VizcoreConnectionHealth(
    val captured: Int,
    val failed: Int,
    val failures: Capped<FailureEntry>,
)

/** Transport state the backend supplies (spec C-3 `transport`), echoed in the health. */
@Serializable
data class TransportStatus(
    val lostEvents: Long = 0,
    val deliveryUncertain: Boolean = false,
    val sinceSeq: Long? = null,
)

@Serializable
data class ProblemRound(
    val roundId: String,
    val anchorId: String,
    val anchorCreatedNanos: Long,
    val kinds: List<IncidentKind>,
)

/** The derived health of one session (spec C-3, the parts core computes). */
@Serializable
data class SessionHealth(
    val state: HealthState,
    val asOf: AsOf?,
    val asOfSeq: Long,
    val sessionOrigin: SessionOrigin,
    val problemCount: Int,
    val failedRoundCount: Int,
    val ungroupedFailureCount: Int,
    val possiblyBlockedCount: Int,
    val incidents: Capped<Incident>,
    val possiblyBlocked: PossiblyBlockedEvaluation,
    val observations: Observations,
    val counts: OriginCounts,
    /** Counts may be low: coroutines beyond capacity, or events lost or possibly lost on the way. */
    val failuresAtLeast: Boolean,
    val vizcoreConnection: VizcoreConnectionHealth,
    val transport: TransportStatus,
    val roundCount: Int,
    val problemRoundCount: Int,
    val problemRounds: Capped<ProblemRound>,
)

@Serializable
data class WatermarkObservation(
    val appNanos: Long,
    val appEpochMs: Long,
    val receivedEpochMs: Long,
)

sealed interface HealthMode {
    data object Live : HealthMode

    /** Replay at [atSeq]: the summaries are the fold of the retained events up to it. */
    data class Replay(
        val atSeq: Long,
    ) : HealthMode
}

/** Inputs of [SessionHealthDerivation.derive] (spec C-1). */
data class HealthInput(
    val summaries: SummaryView,
    val kind: SessionKind,
    val mode: HealthMode = HealthMode.Live,
    val watermark: WatermarkObservation? = null,
    val lastIngestEpochMs: Long? = null,
    /** The session's origin as the producer stated it (PR 2/3); `null` = infer it from the coroutines. */
    val reportedOrigin: SessionOrigin? = null,
    val transport: TransportStatus = TransportStatus(),
    val leakThresholdMs: Long = HealthThresholds.DEFAULT_LEAK_THRESHOLD_MS,
    val longSuspendedThresholdNanos: Long = HealthThresholds.LONG_SUSPENDED_NANOS,
    /** Retained transitions of one coroutine, oldest first; used for possibly blocked coroutines only. */
    val transitionsFor: (String) -> List<Transition> = { emptyList() },
)
