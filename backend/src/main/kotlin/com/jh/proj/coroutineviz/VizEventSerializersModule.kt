package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.events.*
import com.jh.proj.coroutineviz.events.channel.*
import com.jh.proj.coroutineviz.events.coroutine.*
import com.jh.proj.coroutineviz.events.deferred.*
import com.jh.proj.coroutineviz.events.dispatcher.*
import com.jh.proj.coroutineviz.events.flow.*
import com.jh.proj.coroutineviz.events.job.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Polymorphic SerializersModule registering all 66 VizEvent subclasses.
 *
 * Per D-05, VizEvent is NOT sealed to allow extension from subpackages.
 * This module must be kept in sync with D-04 completeness test.
 */
val vizEventSerializersModule = SerializersModule {
    polymorphic(VizEvent::class) {
        // coroutine package (8)
        subclass(CoroutineBodyCompleted::class)
        subclass(CoroutineCancelled::class)
        subclass(CoroutineCompleted::class)
        subclass(CoroutineCreated::class)
        subclass(CoroutineFailed::class)
        subclass(CoroutineResumed::class)
        subclass(CoroutineStarted::class)
        subclass(CoroutineSuspended::class)
        // job package (4)
        subclass(JobCancellationRequested::class)
        subclass(JobJoinCompleted::class)
        subclass(JobJoinRequested::class)
        subclass(JobStateChanged::class)
        // flow package (13)
        subclass(FlowBackpressure::class)
        subclass(FlowBufferOverflow::class)
        subclass(FlowCollectionCancelled::class)
        subclass(FlowCollectionCompleted::class)
        subclass(FlowCollectionStarted::class)
        subclass(FlowCreated::class)
        subclass(FlowOperatorApplied::class)
        subclass(FlowValueEmitted::class)
        subclass(FlowValueFiltered::class)
        subclass(FlowValueTransformed::class)
        subclass(SharedFlowEmission::class)
        subclass(SharedFlowSubscription::class)
        subclass(StateFlowValueChanged::class)
        // dispatcher package (2)
        subclass(DispatcherSelected::class)
        subclass(ThreadAssigned::class)
        // deferred package (3)
        subclass(DeferredAwaitCompleted::class)
        subclass(DeferredAwaitStarted::class)
        subclass(DeferredValueAvailable::class)
        // channel package (9)
        subclass(ChannelBufferStateChanged::class)
        subclass(ChannelClosed::class)
        subclass(ChannelCreated::class)
        subclass(ChannelReceiveCompleted::class)
        subclass(ChannelReceiveStarted::class)
        subclass(ChannelReceiveSuspended::class)
        subclass(ChannelSendCompleted::class)
        subclass(ChannelSendStarted::class)
        subclass(ChannelSendSuspended::class)
        // mutex (6) — in MutexEvents.kt
        subclass(MutexCreated::class)
        subclass(MutexLockAcquired::class)
        subclass(MutexLockRequested::class)
        subclass(MutexQueueChanged::class)
        subclass(MutexTryLockFailed::class)
        subclass(MutexUnlocked::class)
        // semaphore (6) — in SemaphoreEvents.kt
        subclass(SemaphoreAcquireRequested::class)
        subclass(SemaphoreCreated::class)
        subclass(SemaphorePermitAcquired::class)
        subclass(SemaphorePermitReleased::class)
        subclass(SemaphoreStateChanged::class)
        subclass(SemaphoreTryAcquireFailed::class)
        // actor (7) — in ActorEvents.kt
        subclass(ActorClosed::class)
        subclass(ActorCreated::class)
        subclass(ActorMailboxChanged::class)
        subclass(ActorMessageProcessed::class)
        subclass(ActorMessageProcessing::class)
        subclass(ActorMessageSent::class)
        subclass(ActorStateChanged::class)
        // select (4) — in SelectEvents.kt
        subclass(SelectClauseRegistered::class)
        subclass(SelectClauseWon::class)
        subclass(SelectCompleted::class)
        subclass(SelectStarted::class)
        // deadlock (2) — in DeadlockEvents.kt
        subclass(DeadlockDetected::class)
        subclass(PotentialDeadlockWarning::class)
        // anti-pattern (1)
        subclass(AntiPatternDetected::class)
        // structured concurrency (1) — in WaitingForChildren.kt
        subclass(WaitingForChildren::class)
    }
}

/** Shared Json instance — the one source of truth for all serialization in the app. */
val appJson = Json {
    serializersModule = vizEventSerializersModule
    encodeDefaults = true
    ignoreUnknownKeys = true
}
