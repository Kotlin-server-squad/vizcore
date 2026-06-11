package com.jh.proj.coroutineviz.events

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.channel.*
import com.jh.proj.coroutineviz.events.coroutine.*
import com.jh.proj.coroutineviz.events.deferred.*
import com.jh.proj.coroutineviz.events.dispatcher.*
import com.jh.proj.coroutineviz.events.flow.*
import com.jh.proj.coroutineviz.events.job.*
import com.jh.proj.coroutineviz.vizEventSerializersModule
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class VizEventSerializersModuleTest {
    // D-04: completeness guard — every subclass must be registered
    @Test
    fun `all VizEvent subclasses are registered in SerializersModule`() {
        val module = vizEventSerializersModule
        val knownSubclasses =
            listOf(
                // coroutine (8)
                CoroutineBodyCompleted::class,
                CoroutineCancelled::class,
                CoroutineCompleted::class,
                CoroutineCreated::class,
                CoroutineFailed::class,
                CoroutineResumed::class,
                CoroutineStarted::class,
                CoroutineSuspended::class,
                // job (4)
                JobCancellationRequested::class,
                JobJoinCompleted::class,
                JobJoinRequested::class,
                JobStateChanged::class,
                // flow (13)
                FlowBackpressure::class,
                FlowBufferOverflow::class,
                FlowCollectionCancelled::class,
                FlowCollectionCompleted::class,
                FlowCollectionStarted::class,
                FlowCreated::class,
                FlowOperatorApplied::class,
                FlowValueEmitted::class,
                FlowValueFiltered::class,
                FlowValueTransformed::class,
                SharedFlowEmission::class,
                SharedFlowSubscription::class,
                StateFlowValueChanged::class,
                // dispatcher (2)
                DispatcherSelected::class,
                ThreadAssigned::class,
                // deferred (3)
                DeferredAwaitCompleted::class,
                DeferredAwaitStarted::class,
                DeferredValueAvailable::class,
                // channel (9)
                ChannelBufferStateChanged::class,
                ChannelClosed::class,
                ChannelCreated::class,
                ChannelReceiveCompleted::class,
                ChannelReceiveStarted::class,
                ChannelReceiveSuspended::class,
                ChannelSendCompleted::class,
                ChannelSendStarted::class,
                ChannelSendSuspended::class,
                // mutex (6)
                MutexCreated::class,
                MutexLockAcquired::class,
                MutexLockRequested::class,
                MutexQueueChanged::class,
                MutexTryLockFailed::class,
                MutexUnlocked::class,
                // semaphore (6)
                SemaphoreAcquireRequested::class,
                SemaphoreCreated::class,
                SemaphorePermitAcquired::class,
                SemaphorePermitReleased::class,
                SemaphoreStateChanged::class,
                SemaphoreTryAcquireFailed::class,
                // actor (7)
                ActorClosed::class,
                ActorCreated::class,
                ActorMailboxChanged::class,
                ActorMessageProcessed::class,
                ActorMessageProcessing::class,
                ActorMessageSent::class,
                ActorStateChanged::class,
                // select (4)
                SelectClauseRegistered::class,
                SelectClauseWon::class,
                SelectCompleted::class,
                SelectStarted::class,
                // deadlock (2)
                DeadlockDetected::class,
                PotentialDeadlockWarning::class,
                // anti-pattern (1)
                AntiPatternDetected::class,
                // structured concurrency (1)
                WaitingForChildren::class,
            )

        assertEquals(66, knownSubclasses.size, "Should have exactly 66 VizEvent subclasses")

        for (klass in knownSubclasses) {
            assertNotNull(
                module.getPolymorphic(VizEvent::class, klass.simpleName!!),
                "Missing registration for ${klass.simpleName}",
            )
        }
    }

    @Test
    fun `VizEvent polymorphic round-trip via appJson`() {
        val event: VizEvent =
            CoroutineCreated(
                sessionId = "s",
                seq = 1,
                tsNanos = 0,
                coroutineId = "c",
                jobId = "j",
                parentCoroutineId = null,
                scopeId = "sc",
                label = null,
            )
        val serialized = appJson.encodeToString(VizEvent.serializer(), event)
        val deserialized = appJson.decodeFromString(VizEvent.serializer(), serialized)
        assertEquals(event, deserialized)
    }

    @Test
    fun `appJson encodes VizEvent with type discriminator`() {
        val event: VizEvent =
            CoroutineCreated(
                sessionId = "s",
                seq = 1,
                tsNanos = 0,
                coroutineId = "c",
                jobId = "j",
                parentCoroutineId = null,
                scopeId = "sc",
                label = null,
            )
        val serialized = appJson.encodeToString(VizEvent.serializer(), event)
        // Polymorphic serialization includes type discriminator
        assert(serialized.contains("CoroutineCreated")) {
            "Serialized JSON should contain type discriminator 'CoroutineCreated', got: $serialized"
        }
    }
}
