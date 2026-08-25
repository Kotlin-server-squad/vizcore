package com.jh.proj.coroutineviz.session

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves [StructuralClassifier] is the explicit, auditable allow-set that is the
 * shared spine of PERF-01 (never sample these) and PERF-04 (never shed these).
 */
class StructuralClassifierTest {
    // -- coroutine lifecycle (D-01: create/complete/cancel ALWAYS protected) --

    @Test
    fun `every coroutine lifecycle kind is structural`() {
        val lifecycle = listOf(
            "CoroutineCreated",
            "CoroutineStarted",
            "CoroutineCompleted",
            "CoroutineCancelled",
            "CoroutineFailed",
            "CoroutineBodyCompleted",
        )
        for (kind in lifecycle) {
            assertTrue(StructuralClassifier.isStructural(kind), "$kind must be protected (D-01)")
        }
    }

    @Test
    fun `topology and birth-death kinds are structural`() {
        val structural = listOf(
            // job parentage / terminal
            "JobStateChanged",
            "JobCancellationRequested",
            "JobJoinCompleted",
            "JobJoinRequested",
            "WaitingForChildren",
            // channel birth/death
            "ChannelCreated",
            "ChannelClosed",
            // flow existence boundaries
            "FlowCreated",
            "FlowCollectionStarted",
            "FlowCollectionCompleted",
            "FlowCollectionCancelled",
            // primitive birth
            "MutexCreated",
            "SemaphoreCreated",
            // actor birth/death
            "ActorCreated",
            "ActorClosed",
            // rare high-signal
            "DeadlockDetected",
            "PotentialDeadlockWarning",
            "AntiPatternDetected",
        )
        for (kind in structural) {
            assertTrue(StructuralClassifier.isStructural(kind), "$kind must be structural")
        }
    }

    @Test
    fun `borderline low-frequency kinds default to structural (A2 safe-over-protect)`() {
        val borderline = listOf(
            "MutexUnlocked",
            "SemaphorePermitReleased",
            "SelectStarted",
            "SelectClauseRegistered",
            "SelectClauseWon",
            "SelectCompleted",
            "DeferredAwaitStarted",
            "DeferredAwaitCompleted",
            "DeferredValueAvailable",
        )
        for (kind in borderline) {
            assertTrue(StructuralClassifier.isStructural(kind), "$kind defaults to structural (A2)")
        }
    }

    // -- high-frequency sampleable / sheddable --

    @Test
    fun `high-frequency sampleable kinds are not structural`() {
        val sheddable = listOf(
            "CoroutineSuspended",
            "CoroutineResumed",
            "DispatcherSelected",
            "ThreadAssigned",
            "FlowValueEmitted",
            "FlowValueTransformed",
            "FlowValueFiltered",
            "FlowBackpressure",
            "FlowBufferOverflow",
            "FlowOperatorApplied",
            "SharedFlowEmission",
            "SharedFlowSubscription",
            "StateFlowValueChanged",
            "ChannelSendStarted",
            "ChannelSendSuspended",
            "ChannelSendCompleted",
            "ChannelReceiveStarted",
            "ChannelReceiveSuspended",
            "ChannelReceiveCompleted",
            "ChannelBufferStateChanged",
            "MutexLockRequested",
            "MutexLockAcquired",
            "MutexQueueChanged",
            "MutexTryLockFailed",
            "SemaphoreAcquireRequested",
            "SemaphorePermitAcquired",
            "SemaphoreStateChanged",
            "SemaphoreTryAcquireFailed",
            "ActorMailboxChanged",
            "ActorMessageSent",
            "ActorMessageProcessing",
            "ActorMessageProcessed",
            "ActorStateChanged",
        )
        for (kind in sheddable) {
            assertFalse(StructuralClassifier.isStructural(kind), "$kind must be sheddable (not structural)")
        }
    }

    @Test
    fun `unknown kind is not structural (only the explicit allow-set is protected)`() {
        assertFalse(StructuralClassifier.isStructural("SomethingNobodyRegistered"))
        assertFalse(StructuralClassifier.isStructural(""))
        // A leaky suffix heuristic would wrongly protect these high-frequency value events:
        assertFalse(StructuralClassifier.isStructural("FlowValueEmitted"))
    }
}
