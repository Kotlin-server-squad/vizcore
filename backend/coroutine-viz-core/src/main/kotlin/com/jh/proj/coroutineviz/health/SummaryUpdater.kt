package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.SuspensionProvenance
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.events.dispatcher.DispatcherSelected
import com.jh.proj.coroutineviz.events.dispatcher.ThreadAssigned

/** Pure per-summary updates (spec C-2): the one place a summary changes. */
internal object SummaryUpdater {
    fun created(e: CoroutineCreated): CoroutineSummary {
        val transition = Transition(TransitionKind.CREATED, e.tsNanos, e.seq)
        return CoroutineSummary(
            id = e.coroutineId,
            parentId = e.parentCoroutineId,
            origin = e.origin,
            name = e.label,
            creationLocation = e.creationPoint,
            createdAtEpochMs = e.createdAtEpochMs,
            firstObserved = transition,
            lastTransition = transition,
            lastObservedNanos = e.tsNanos,
            lastModifiedSeq = e.seq,
        )
    }

    /** The summary right after [s]'s creation, from what a durable summary (or stub) keeps of it (replay seed). */
    fun createdFrom(s: CoroutineSummary): CoroutineSummary =
        CoroutineSummary(
            id = s.id,
            parentId = s.parentId,
            origin = s.origin,
            name = s.name,
            creationLocation = s.creationLocation,
            createdAtEpochMs = s.createdAtEpochMs,
            firstObserved = s.firstObserved,
            lastTransition = s.firstObserved,
            lastObservedNanos = s.firstObserved.appNanos,
            lastModifiedSeq = s.firstObserved.seq,
        )

    /** [current] finished as [durable] did (replay seed); a stub's failure keeps no details, and says so. */
    fun finishedAs(
        current: CoroutineSummary,
        durable: CoroutineSummary,
    ): CoroutineSummary {
        val terminal = checkNotNull(durable.terminal) { "${durable.id} has no terminal transition" }
        return current.copy(
            lastTransition = terminal,
            terminal = terminal,
            lastObservedNanos = maxOf(current.lastObservedNanos, terminal.appNanos),
            failed = durable.failed,
            cancelled = durable.cancelled,
            failure = durable.failure,
            reduced = durable.reduced,
            lastModifiedSeq = terminal.seq,
        )
    }

    /** [s] after [e]; [s] itself only when [s] is terminal (a terminal summary never changes) or [e] is a re-creation. */
    fun update(
        s: CoroutineSummary,
        e: CoroutineEvent,
    ): CoroutineSummary {
        if (s.isTerminal) return s
        return when (e) {
            is ThreadAssigned ->
                s.copy(
                    lastThread = ThreadObservation(e.threadId, e.threadName, e.seq),
                    dispatcher = e.dispatcherName ?: s.dispatcher,
                    lastObservedNanos = maxOf(s.lastObservedNanos, e.tsNanos),
                    lastModifiedSeq = e.seq,
                )
            is DispatcherSelected ->
                s.copy(
                    dispatcher = e.dispatcherName,
                    lastObservedNanos = maxOf(s.lastObservedNanos, e.tsNanos),
                    lastModifiedSeq = e.seq,
                )
            is CoroutineCreated -> s
            // Any other event about the coroutine (join, waiting for children, channel, …) observes it. lastModifiedSeq is bumped
            // even when no field changes: deliberately conservative, a replay read may answer STALE more often, never mixes states.
            else ->
                Transitions.of(e)?.let { transition(s, e, it) }
                    ?: s.copy(lastObservedNanos = maxOf(s.lastObservedNanos, e.tsNanos), lastModifiedSeq = e.seq)
        }
    }

    private fun transition(
        s: CoroutineSummary,
        e: CoroutineEvent,
        t: Transition,
    ): CoroutineSummary {
        val moved = s.copy(lastTransition = t, lastObservedNanos = maxOf(s.lastObservedNanos, t.appNanos), lastModifiedSeq = t.seq)
        return when (t.kind) {
            TransitionKind.STARTED, TransitionKind.RESUMED -> moved.copy(lastRunStart = t)
            TransitionKind.SUSPENDED -> if (e is CoroutineSuspended) suspended(moved, e) else moved
            TransitionKind.COMPLETED -> moved.copy(terminal = t)
            TransitionKind.CANCELLED -> moved.copy(terminal = t, cancelled = true)
            TransitionKind.FAILED -> if (e is CoroutineFailed) failed(moved, e, t) else moved
            // CREATED is unreachable here: update() returns early for CoroutineCreated.
            TransitionKind.CREATED, TransitionKind.BODY_COMPLETED -> moved
        }
    }

    private fun failed(
        s: CoroutineSummary,
        e: CoroutineFailed,
        t: Transition,
    ): CoroutineSummary {
        val cancellation = HealthThresholds.isCancellation(e.exceptionType)
        return s.copy(
            terminal = t,
            failed = !cancellation,
            cancelled = cancellation,
            failure = FailureInfo(e.exceptionType, e.message),
        )
    }

    private fun suspended(
        s: CoroutineSummary,
        e: CoroutineSuspended,
    ): CoroutineSummary {
        val point = e.suspensionPoint
        // A creation fallback carries no suspension frame either: it must not replace a known location (spec provenance).
        val frameless = point == null || e.provenance == SuspensionProvenance.CREATION_FALLBACK
        return when {
            frameless && s.lastSuspension != null ->
                s.copy(everSuspended = true, lastSuspensionProvenance = SuspensionProvenance.STALE)
            point != null -> s.copy(everSuspended = true, lastSuspension = point, lastSuspensionProvenance = e.provenance)
            else -> s.copy(everSuspended = true)
        }
    }
}
