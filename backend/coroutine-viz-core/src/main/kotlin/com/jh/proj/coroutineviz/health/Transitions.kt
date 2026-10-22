package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineBodyCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended

/** Maps lifecycle events to [Transition]s; the backend uses it for a coroutine's retained transitions. */
object Transitions {
    fun of(event: VizEvent): Transition? {
        val kind =
            when (event) {
                is CoroutineCreated -> TransitionKind.CREATED
                is CoroutineStarted -> TransitionKind.STARTED
                is CoroutineSuspended -> TransitionKind.SUSPENDED
                is CoroutineResumed -> TransitionKind.RESUMED
                is CoroutineBodyCompleted -> TransitionKind.BODY_COMPLETED
                is CoroutineCompleted -> TransitionKind.COMPLETED
                is CoroutineCancelled -> TransitionKind.CANCELLED
                is CoroutineFailed -> TransitionKind.FAILED
                else -> return null
            }
        return Transition(kind, event.tsNanos, event.seq)
    }

    /** The transitions of [coroutineId] among [events], in event order. */
    fun forCoroutine(
        events: Iterable<VizEvent>,
        coroutineId: String,
    ): List<Transition> = events.filter { it is CoroutineEvent && it.coroutineId == coroutineId }.mapNotNull(::of)
}
