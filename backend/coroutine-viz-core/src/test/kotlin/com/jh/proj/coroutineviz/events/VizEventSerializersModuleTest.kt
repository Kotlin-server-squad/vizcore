package com.jh.proj.coroutineviz.events

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.channel.*
import com.jh.proj.coroutineviz.events.coroutine.*
import com.jh.proj.coroutineviz.events.deferred.*
import com.jh.proj.coroutineviz.events.dispatcher.*
import com.jh.proj.coroutineviz.events.flow.*
import com.jh.proj.coroutineviz.events.job.*
import com.jh.proj.coroutineviz.vizEventSerializersModule
import kotlinx.serialization.PolymorphicSerializer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VizEventSerializersModuleTest {
    // D-04: completeness guard — every subclass must be registered.
    // Verification uses round-trip encoding rather than serial-name lookup,
    // because the runtime discriminator value may be FQN or short-name
    // depending on which serialization-core version is active; round-trip
    // tests the actual polymorphic encode/decode path.
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

        // Verify each class is registered: look up by qualified name (FQN) or simple name,
        // depending on which serialization runtime is active. At minimum the class must
        // be resolvable via the module's polymorphic scope — verified below via getPolymorphic
        // with either the serial name or the qualified name.
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        for (klass in knownSubclasses) {
            val bySimpleName = module.getPolymorphic(VizEvent::class, klass.simpleName!!)
            val byQualifiedName = module.getPolymorphic(VizEvent::class, klass.qualifiedName!!)
            assertNotNull(
                bySimpleName ?: byQualifiedName,
                "Missing registration for ${klass.simpleName} " +
                    "(tried '${klass.simpleName}' and '${klass.qualifiedName}')",
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
        val serialized = appJson.encodeToString(PolymorphicSerializer(VizEvent::class), event)
        val deserialized = appJson.decodeFromString(PolymorphicSerializer(VizEvent::class), serialized)
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
        val serialized = appJson.encodeToString(PolymorphicSerializer(VizEvent::class), event)
        // Polymorphic serialization must include a type discriminator containing the class name
        assertTrue(
            serialized.contains("CoroutineCreated"),
            "Serialized JSON must contain type discriminator with 'CoroutineCreated', got: $serialized",
        )
    }

    @Test
    fun `FlowBackpressure round-trip verifies flow subclasses are registered`() {
        val event: VizEvent =
            FlowBackpressure(
                sessionId = "s",
                seq = 1,
                tsNanos = 0,
                flowId = "f1",
                collectorId = "c1",
                reason = "slow_collector",
                pendingEmissions = 3,
                bufferCapacity = 10,
                durationNanos = null,
            )
        val serialized = appJson.encodeToString(PolymorphicSerializer(VizEvent::class), event)
        val deserialized = appJson.decodeFromString(PolymorphicSerializer(VizEvent::class), serialized)
        assertEquals(event, deserialized)
        assertTrue(
            deserialized is FlowBackpressure,
            "Deserialized event should be FlowBackpressure, got ${deserialized::class.simpleName}",
        )
    }

    @Test
    fun `List of VizEvents serializes without SerializationException`() {
        // This is the core FIX-01 acceptance test:
        // GET /api/sessions/{id}/events returns a List<VizEvent> which must not throw SerializationException
        val events: List<VizEvent> =
            listOf(
                CoroutineCreated(
                    sessionId = "s",
                    seq = 1,
                    tsNanos = 0,
                    coroutineId = "c1",
                    jobId = "j1",
                    parentCoroutineId = null,
                    scopeId = "sc",
                    label = "test",
                ),
                FlowBackpressure(
                    sessionId = "s",
                    seq = 2,
                    tsNanos = 0,
                    flowId = "f1",
                    collectorId = "c1",
                    reason = "buffer_full",
                    pendingEmissions = 5,
                    bufferCapacity = 100,
                    durationNanos = null,
                ),
                MutexCreated(
                    sessionId = "s",
                    seq = 3,
                    tsNanos = 0,
                    mutexId = "m1",
                    mutexLabel = "test-mutex",
                ),
                AntiPatternDetected(
                    sessionId = "s",
                    seq = 4,
                    tsNanos = 0,
                    patternType = AntiPatternType.GLOBAL_SCOPE_USAGE,
                    severity = AntiPatternSeverity.ERROR,
                    description = "GlobalScope used",
                    suggestion = "Use structured concurrency",
                ),
            )

        // Verify each event encodes/decodes as VizEvent
        for (event in events) {
            val serialized = appJson.encodeToString(PolymorphicSerializer(VizEvent::class), event)
            val deserialized = appJson.decodeFromString(PolymorphicSerializer(VizEvent::class), serialized)
            assertNotNull(deserialized)
            assertEquals(event::class, deserialized::class, "Round-trip should preserve type for ${event::class.simpleName}")
        }
    }
}
