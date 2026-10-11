package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineBodyCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.events.dispatcher.ThreadAssigned
import com.jh.proj.coroutineviz.models.CoroutineNode
import com.jh.proj.coroutineviz.models.CoroutineState
import com.jh.proj.coroutineviz.models.RuntimeSnapshot
import org.slf4j.LoggerFactory

/**
 * Applies events to update the [RuntimeSnapshot] state.
 *
 * The EventApplier is responsible for translating events into state changes
 * in the runtime snapshot. It implements the "apply" side of event sourcing,
 * where events are the source of truth and state is derived from them.
 *
 * Each event type triggers specific state transitions:
 * - [CoroutineCreated] → Creates new [CoroutineNode] with CREATED state
 * - [CoroutineStarted] → Transitions to ACTIVE state
 * - [CoroutineSuspended] → Transitions to SUSPENDED state
 * - [CoroutineResumed] → Transitions back to ACTIVE state
 * - [CoroutineBodyCompleted] → Transitions to WAITING_FOR_CHILDREN state
 * - [CoroutineCompleted] → Transitions to COMPLETED state
 * - [CoroutineCancelled] → Transitions to CANCELLED state
 * - [CoroutineFailed] → Transitions to FAILED state
 * - [ThreadAssigned] → Updates thread information on the node
 *
 * Every transition replaces the affected [CoroutineNode] with a copy instead of mutating it,
 * so readers that do not hold the session lock only ever see complete node values.
 *
 * @property snapshot The runtime snapshot to update
 */
class EventApplier(
    private val snapshot: RuntimeSnapshot,
) {
    fun apply(event: VizEvent) {
        when (event) {
            is CoroutineCreated -> handleCreated(event)
            is CoroutineStarted -> handleStarted(event)
            is CoroutineBodyCompleted -> handleBodyCompleted(event)
            is CoroutineCompleted -> handleCompleted(event)
            is CoroutineCancelled -> handleCancelled(event)
            is CoroutineSuspended -> handleSuspended(event)
            is CoroutineResumed -> handleResumed(event)
            is ThreadAssigned -> handleThreadAssigned(event)
            is CoroutineFailed -> handleFailed(event)
            // Later: DispatcherSelected, ThreadAssigned, Flow events, etc.
            else -> {
                // For now we ignore other event types
            }
        }
    }

    private fun handleCreated(e: CoroutineCreated) {
        snapshot.coroutines[e.coroutineId] =
            CoroutineNode(
                id = e.coroutineId,
                jobId = e.jobId,
                parentId = e.parentCoroutineId,
                scopeId = e.scopeId,
                label = e.label,
                state = CoroutineState.CREATED,
            )
    }

    private fun handleStarted(e: CoroutineStarted) {
        val node = snapshot.coroutines[e.coroutineId]

        if (node == null) {
            logger.warn("Received CoroutineStarted for unknown coroutine: ${e.coroutineId}")
            return
        }

        if (node.state != CoroutineState.CREATED) {
            logger.warn("Invalid state transition: ${node.state} -> ACTIVE for ${e.coroutineId}")
        }

        transition(e.coroutineId, CoroutineState.ACTIVE)
    }

    private fun handleBodyCompleted(e: CoroutineBodyCompleted) {
        val node = snapshot.coroutines[e.coroutineId]

        if (node == null) {
            logger.warn("Received CoroutineBodyCompleted for unknown coroutine: ${e.coroutineId}")
            return
        }

        // Transition to WAITING_FOR_CHILDREN state
        // This indicates the coroutine's code has finished but it's waiting for children
        transition(e.coroutineId, CoroutineState.WAITING_FOR_CHILDREN)
    }

    private fun handleCompleted(e: CoroutineCompleted) {
        // Final completion - all children have also completed
        transition(e.coroutineId, CoroutineState.COMPLETED)
    }

    private fun handleCancelled(e: CoroutineCancelled) {
        transition(e.coroutineId, CoroutineState.CANCELLED)
    }

    private fun handleSuspended(e: CoroutineSuspended) {
        transition(e.coroutineId, CoroutineState.SUSPENDED)
    }

    private fun handleResumed(e: CoroutineResumed) {
        transition(e.coroutineId, CoroutineState.ACTIVE)
    }

    private fun handleThreadAssigned(e: ThreadAssigned) {
        snapshot.coroutines.computeIfPresent(e.coroutineId) { _, node ->
            node.copy(
                threadId = e.threadId,
                threadName = e.threadName,
                dispatcherName = e.dispatcherName,
            )
        }
    }

    private fun handleFailed(e: CoroutineFailed) {
        transition(e.coroutineId, CoroutineState.FAILED)
    }

    /**
     * Replaces the node with a copy in [state]. Published nodes are never mutated in place, so a
     * concurrent reader (an HTTP route serializing the snapshot) never observes a half-applied
     * transition. Unknown coroutines are ignored, as before.
     */
    private fun transition(
        coroutineId: String,
        state: CoroutineState,
    ) {
        snapshot.coroutines.computeIfPresent(coroutineId) { _, node -> node.copy(state = state) }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(EventApplier::class.java)
    }
}
