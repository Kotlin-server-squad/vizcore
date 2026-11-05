package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.WaitKind
import com.jh.proj.coroutineviz.session.EventContext
import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.session.coroutineResumed
import com.jh.proj.coroutineviz.session.coroutineSuspended
import kotlinx.coroutines.currentCoroutineContext

/**
 * Records a wait that could not be satisfied at once as a suspension (spec 2026-11-05, S-7): `CoroutineSuspended`
 * with [kind] and [resourceId] before [wait], `CoroutineResumed` when the wait ends — by returning, or by an
 * exception the coroutine may survive (a closed channel, a `withTimeout` around the wait). When the coroutine's
 * own job is being cancelled no Resumed is sent, however the wait ended: its cancellation event ends the
 * suspension. The job is looked up by coroutine id, not taken from the context, because inside `withTimeout` the
 * context's job is the timeout's, which is cancelled although the coroutine goes on. Outside an instrumented
 * coroutine it only waits.
 *
 * Callers try first without suspending and call this only when they must wait.
 */
internal suspend fun <T> VizSession.recordWait(
    kind: WaitKind,
    resourceId: String,
    wait: suspend () -> T,
): T {
    val element = currentCoroutineContext()[VizCoroutineElement] ?: return wait()
    val ctx = EventContext(this, element.coroutineId, element.jobId, element.parentCoroutineId, element.scopeId, element.label)
    val (point, provenance) = SuspensionPoint.captureWithProvenance(kind.reason)
    send(
        ctx.coroutineSuspended(
            reason = kind.reason,
            suspensionPoint = point,
            provenance = provenance,
            waitKind = kind,
            resourceId = resourceId,
        ),
    )
    try {
        return wait()
    } finally {
        // However the wait ended, no resume for a coroutine whose own job is being cancelled: its cancellation
        // event ends the suspension. A wait can also return normally while that happens (inside NonCancellable).
        if (snapshot.jobOf(element.coroutineId)?.isCancelled != true) send(ctx.coroutineResumed())
    }
}
