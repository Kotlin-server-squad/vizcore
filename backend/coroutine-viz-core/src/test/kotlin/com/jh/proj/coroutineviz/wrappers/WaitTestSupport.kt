package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.fail

/** The suspension and resume events of the coroutine labelled [label], oldest first. */
internal fun VizSession.suspensionsOf(label: String): List<CoroutineEvent> =
    store.all().filterIsInstance<CoroutineEvent>().filter {
        it.label == label && (it is CoroutineSuspended || it is CoroutineResumed)
    }

/** The kinds of [suspensionsOf], e.g. `[CoroutineSuspended, CoroutineResumed]`. */
internal fun VizSession.suspensionKindsOf(label: String): List<String> = suspensionsOf(label).map { it.kind }

/** Every wait of [label] is a suspension followed by its resume. */
internal fun VizSession.waitsAreBalanced(label: String): Boolean =
    suspensionKindsOf(label).chunked(2).all { it == listOf("CoroutineSuspended", "CoroutineResumed") }

/** The id of the coroutine labelled [label]. */
internal fun VizSession.idOf(label: String): String =
    store.all().filterIsInstance<CoroutineCreated>().single { it.label == label }.coroutineId

/** Polls the store until an event matches [predicate] and returns it; fails after [timeoutMs]. */
internal suspend fun VizSession.awaitEvent(
    timeoutMs: Long = 5_000,
    predicate: (VizEvent) -> Boolean,
): VizEvent {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        store.all().firstOrNull(predicate)?.let { return it }
        delay(10)
    }
    fail("no matching event within $timeoutMs ms")
}

/** Waits until the coroutine labelled [label] has recorded a suspension, and returns it. */
internal suspend fun VizSession.awaitSuspension(label: String): CoroutineSuspended =
    awaitEvent { it is CoroutineSuspended && it.label == label } as CoroutineSuspended

/**
 * A [VizScope] on a virtual-time dispatcher, for tests whose order must not depend on real time (a wait that a
 * `withTimeout` ends): [runCurrent] runs everything that is ready, [advancePast] moves virtual time on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class VirtualScope(
    session: VizSession,
) {
    private val scheduler = TestCoroutineScheduler()
    val scope = VizScope(session, context = StandardTestDispatcher(scheduler))

    fun runCurrent() = scheduler.runCurrent()

    fun advancePast(millis: Long) {
        scheduler.advanceTimeBy(millis + 1)
        scheduler.runCurrent()
    }
}
