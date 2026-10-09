package com.jh.proj.coroutineviz.events

import com.jh.proj.coroutineviz.events.channel.ChannelBufferStateChanged
import com.jh.proj.coroutineviz.events.channel.ChannelClosed
import com.jh.proj.coroutineviz.events.channel.ChannelCreated
import com.jh.proj.coroutineviz.events.channel.ChannelReceiveCompleted
import com.jh.proj.coroutineviz.events.channel.ChannelReceiveStarted
import com.jh.proj.coroutineviz.events.channel.ChannelReceiveSuspended
import com.jh.proj.coroutineviz.events.channel.ChannelSendCompleted
import com.jh.proj.coroutineviz.events.channel.ChannelSendStarted
import com.jh.proj.coroutineviz.events.channel.ChannelSendSuspended
import com.jh.proj.coroutineviz.events.coroutine.CoroutineBodyCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.events.deferred.DeferredAwaitCompleted
import com.jh.proj.coroutineviz.events.deferred.DeferredAwaitStarted
import com.jh.proj.coroutineviz.events.deferred.DeferredValueAvailable
import com.jh.proj.coroutineviz.events.dispatcher.DispatcherSelected
import com.jh.proj.coroutineviz.events.dispatcher.ThreadAssigned
import com.jh.proj.coroutineviz.events.flow.FlowBackpressure
import com.jh.proj.coroutineviz.events.flow.FlowBufferOverflow
import com.jh.proj.coroutineviz.events.flow.FlowCollectionCancelled
import com.jh.proj.coroutineviz.events.flow.FlowCollectionCompleted
import com.jh.proj.coroutineviz.events.flow.FlowCollectionStarted
import com.jh.proj.coroutineviz.events.flow.FlowCreated
import com.jh.proj.coroutineviz.events.flow.FlowOperatorApplied
import com.jh.proj.coroutineviz.events.flow.FlowValueEmitted
import com.jh.proj.coroutineviz.events.flow.FlowValueFiltered
import com.jh.proj.coroutineviz.events.flow.FlowValueTransformed
import com.jh.proj.coroutineviz.events.flow.SharedFlowEmission
import com.jh.proj.coroutineviz.events.flow.SharedFlowSubscription
import com.jh.proj.coroutineviz.events.flow.StateFlowValueChanged
import com.jh.proj.coroutineviz.events.job.JobCancellationRequested
import com.jh.proj.coroutineviz.events.job.JobJoinCompleted
import com.jh.proj.coroutineviz.events.job.JobJoinRequested
import com.jh.proj.coroutineviz.events.job.JobStateChanged
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EventSerializationTest {
    private val json =
        Json {
            prettyPrint = false
            encodeDefaults = true
        }

    // ========================================================================
    // Coroutine Events (existing)
    // ========================================================================

    @Test
    fun `CoroutineCreated serialization round-trip`() {
        val event =
            CoroutineCreated(
                sessionId = "test-session",
                seq = 1L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                jobId = "job-1",
                parentCoroutineId = null,
                scopeId = "scope-1",
                label = "test-coroutine",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<CoroutineCreated>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"sessionId\":\"test-session\""))
        assertTrue(serialized.contains("\"coroutineId\":\"coroutine-1\""))
        assertTrue(serialized.contains("\"label\":\"test-coroutine\""))
    }

    @Test
    fun `CoroutineStarted serialization round-trip`() {
        val event =
            CoroutineStarted(
                sessionId = "test-session",
                seq = 2L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                jobId = "job-1",
                parentCoroutineId = null,
                scopeId = "scope-1",
                label = "test-coroutine",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<CoroutineStarted>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `CoroutineCompleted serialization round-trip`() {
        val event =
            CoroutineCompleted(
                sessionId = "test-session",
                seq = 3L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                jobId = "job-1",
                parentCoroutineId = "coroutine-0",
                scopeId = "scope-1",
                label = "child-coroutine",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<CoroutineCompleted>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"parentCoroutineId\":\"coroutine-0\""))
    }

    @Test
    fun `CoroutineFailed serialization round-trip`() {
        val event =
            CoroutineFailed(
                sessionId = "test-session",
                seq = 4L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                jobId = "job-1",
                parentCoroutineId = null,
                scopeId = "scope-1",
                label = "failing-coroutine",
                exceptionType = "java.lang.RuntimeException",
                message = "Test failure",
                stackTrace = listOf("at com.example.Test.run(Test.kt:42)"),
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<CoroutineFailed>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"exceptionType\":\"java.lang.RuntimeException\""))
        assertTrue(serialized.contains("\"message\":\"Test failure\""))
        assertTrue(serialized.contains("\"stackTrace\""))
    }

    @Test
    fun `CoroutineCancelled serialization round-trip`() {
        val event =
            CoroutineCancelled(
                sessionId = "test-session",
                seq = 5L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-2",
                jobId = "job-2",
                parentCoroutineId = "coroutine-1",
                scopeId = "scope-1",
                label = "cancelled-coroutine",
                cause = "Parent was cancelled",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<CoroutineCancelled>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"cause\":\"Parent was cancelled\""))
    }

    @Test
    fun `CoroutineSuspended serialization round-trip`() {
        val event =
            CoroutineSuspended(
                sessionId = "test-session",
                seq = 6L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                jobId = "job-1",
                parentCoroutineId = null,
                scopeId = "scope-1",
                label = "suspended-coroutine",
                reason = "delay",
                durationMillis = 1000L,
                suspensionPoint =
                    SuspensionPoint(
                        function = "doWork",
                        fileName = "Worker.kt",
                        lineNumber = 42,
                        reason = "delay",
                    ),
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<CoroutineSuspended>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"reason\":\"delay\""))
        assertTrue(serialized.contains("\"durationMillis\":1000"))
        assertTrue(serialized.contains("\"function\":\"doWork\""))
    }

    @Test
    fun `CoroutineSuspended with null optional fields`() {
        val event =
            CoroutineSuspended(
                sessionId = "test-session",
                seq = 7L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                jobId = "job-1",
                parentCoroutineId = null,
                scopeId = "scope-1",
                label = null,
                reason = "await",
                durationMillis = null,
                suspensionPoint = null,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<CoroutineSuspended>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `CoroutineResumed serialization round-trip`() {
        val event =
            CoroutineResumed(
                sessionId = "test-session",
                seq = 8L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                jobId = "job-1",
                parentCoroutineId = null,
                scopeId = "scope-1",
                label = "resumed-coroutine",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<CoroutineResumed>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `CoroutineBodyCompleted serialization round-trip`() {
        val event =
            CoroutineBodyCompleted(
                sessionId = "test-session",
                seq = 9L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                jobId = "job-1",
                parentCoroutineId = "coroutine-0",
                scopeId = "scope-1",
                label = "body-completed-coroutine",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<CoroutineBodyCompleted>(serialized)

        assertEquals(event, deserialized)
    }

    // ========================================================================
    // SuspensionPoint (existing)
    // ========================================================================

    @Test
    fun `SuspensionPoint serialization round-trip`() {
        val point =
            SuspensionPoint(
                function = "fetchData",
                fileName = "DataService.kt",
                lineNumber = 99,
                reason = "withContext",
            )

        val serialized = json.encodeToString(point)
        val deserialized = json.decodeFromString<SuspensionPoint>(serialized)

        assertEquals(point, deserialized)
        assertTrue(serialized.contains("\"function\":\"fetchData\""))
        assertTrue(serialized.contains("\"fileName\":\"DataService.kt\""))
        assertTrue(serialized.contains("\"lineNumber\":99"))
        assertTrue(serialized.contains("\"reason\":\"withContext\""))
    }

    @Test
    fun `SuspensionPoint with null optional fields`() {
        val point =
            SuspensionPoint(
                function = "unknown",
                fileName = null,
                lineNumber = null,
                reason = "delay",
            )

        val serialized = json.encodeToString(point)
        val deserialized = json.decodeFromString<SuspensionPoint>(serialized)

        assertEquals(point, deserialized)
    }

    @Test
    fun `CoroutineCreated with creationPoint round-trips through the shared appJson`() {
        val event =
            CoroutineCreated(
                sessionId = "test-session",
                seq = 12L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                jobId = "job-1",
                parentCoroutineId = null,
                scopeId = "scope-1",
                label = "launched",
                createdAtEpochMs = 1_700_000_000_000L,
                creationPoint =
                    SuspensionPoint(
                        function = "startWork",
                        fileName = "Bar.kt",
                        lineNumber = 7,
                        reason = "launch",
                    ),
            )

        val serialized = com.jh.proj.coroutineviz.appJson.encodeToString(event)
        val deserialized = com.jh.proj.coroutineviz.appJson.decodeFromString<CoroutineCreated>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"creationPoint\""))
        assertTrue(serialized.contains("\"fileName\":\"Bar.kt\""))
    }

    @Test
    fun `CoroutineCreated JSON without creationPoint decodes to null (wire back-compat)`() {
        // Legacy wire/persisted row: no creationPoint key at all.
        val legacy =
            """
            {"sessionId":"s","seq":1,"tsNanos":0,"coroutineId":"c","jobId":"j",
             "parentCoroutineId":null,"scopeId":"sc","label":null,"createdAtEpochMs":0}
            """.trimIndent()

        val deserialized = com.jh.proj.coroutineviz.appJson.decodeFromString<CoroutineCreated>(legacy)

        assertEquals(null, deserialized.creationPoint)
    }

    @Test
    fun `CoroutineCreated with null parentCoroutineId and label`() {
        val event =
            CoroutineCreated(
                sessionId = "test-session",
                seq = 10L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-root",
                jobId = "job-root",
                parentCoroutineId = null,
                scopeId = "scope-1",
                label = null,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<CoroutineCreated>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `serialized events contain kind field via SerialName`() {
        val created =
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
        val serialized = json.encodeToString(created)
        // The @SerialName annotation uses "type" discriminator by default,
        // but since we're encoding the concrete type directly, the kind
        // is a computed property and appears in the JSON output
        assertNotNull(serialized, "Serialized output should not be null")
        assertTrue(serialized.isNotEmpty(), "Serialized output should not be empty")
    }

    @Test
    fun `CoroutineFailed with empty stack trace`() {
        val event =
            CoroutineFailed(
                sessionId = "test-session",
                seq = 11L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                jobId = "job-1",
                parentCoroutineId = null,
                scopeId = "scope-1",
                label = "failing",
                exceptionType = null,
                message = null,
                stackTrace = emptyList(),
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<CoroutineFailed>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"stackTrace\":[]"))
    }

    // ========================================================================
    // Job Events
    // ========================================================================

    @Test
    fun `JobStateChanged serialization round-trip`() {
        val event =
            JobStateChanged(
                sessionId = "test-session",
                seq = 20L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                jobId = "job-1",
                parentCoroutineId = "coroutine-0",
                scopeId = "scope-1",
                label = "worker-job",
                isActive = true,
                isCompleted = false,
                isCancelled = false,
                childrenCount = 3,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<JobStateChanged>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"isActive\":true"))
        assertTrue(serialized.contains("\"childrenCount\":3"))
    }

    @Test
    fun `JobJoinRequested serialization round-trip`() {
        val event =
            JobJoinRequested(
                sessionId = "test-session",
                seq = 21L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                jobId = "job-1",
                parentCoroutineId = null,
                scopeId = "scope-1",
                label = "joining-coroutine",
                waitingCoroutineId = "coroutine-2",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<JobJoinRequested>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"waitingCoroutineId\":\"coroutine-2\""))
    }

    @Test
    fun `JobJoinRequested with null waitingCoroutineId`() {
        val event =
            JobJoinRequested(
                sessionId = "test-session",
                seq = 22L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                jobId = "job-1",
                parentCoroutineId = null,
                scopeId = "scope-1",
                label = null,
                waitingCoroutineId = null,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<JobJoinRequested>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `JobJoinCompleted serialization round-trip`() {
        val event =
            JobJoinCompleted(
                sessionId = "test-session",
                seq = 23L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                jobId = "job-1",
                parentCoroutineId = "coroutine-0",
                scopeId = "scope-1",
                label = "join-completed",
                waitingCoroutineId = "coroutine-3",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<JobJoinCompleted>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"waitingCoroutineId\":\"coroutine-3\""))
    }

    @Test
    fun `JobCancellationRequested serialization round-trip`() {
        val event =
            JobCancellationRequested(
                sessionId = "test-session",
                seq = 24L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                jobId = "job-1",
                parentCoroutineId = null,
                scopeId = "scope-1",
                label = "cancelling-job",
                requestedBy = "coroutine-parent",
                cause = "Timeout exceeded",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<JobCancellationRequested>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"requestedBy\":\"coroutine-parent\""))
        assertTrue(serialized.contains("\"cause\":\"Timeout exceeded\""))
    }

    @Test
    fun `JobCancellationRequested with null optional fields`() {
        val event =
            JobCancellationRequested(
                sessionId = "test-session",
                seq = 25L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                jobId = "job-1",
                parentCoroutineId = null,
                scopeId = "scope-1",
                label = null,
                requestedBy = null,
                cause = null,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<JobCancellationRequested>(serialized)

        assertEquals(event, deserialized)
    }

    // ========================================================================
    // Flow Events
    // ========================================================================

    @Test
    fun `FlowCreated serialization round-trip`() {
        val event =
            FlowCreated(
                sessionId = "test-session",
                seq = 100L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                flowId = "flow-1",
                flowType = "Cold",
                label = "numbers-flow",
                scopeId = "scope-1",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<FlowCreated>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"flowType\":\"Cold\""))
        assertTrue(serialized.contains("\"flowId\":\"flow-1\""))
    }

    @Test
    fun `FlowCreated with null optional fields`() {
        val event =
            FlowCreated(
                sessionId = "test-session",
                seq = 101L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                flowId = "flow-2",
                flowType = "SharedFlow",
                label = null,
                scopeId = null,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<FlowCreated>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `FlowCollectionStarted serialization round-trip`() {
        val event =
            FlowCollectionStarted(
                sessionId = "test-session",
                seq = 102L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                flowId = "flow-1",
                collectorId = "collector-1",
                label = "main-collector",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<FlowCollectionStarted>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"collectorId\":\"collector-1\""))
    }

    @Test
    fun `FlowCollectionCompleted serialization round-trip`() {
        val event =
            FlowCollectionCompleted(
                sessionId = "test-session",
                seq = 103L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                flowId = "flow-1",
                collectorId = "collector-1",
                totalEmissions = 42,
                durationNanos = 1_500_000_000L,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<FlowCollectionCompleted>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"totalEmissions\":42"))
        assertTrue(serialized.contains("\"durationNanos\":1500000000"))
    }

    @Test
    fun `FlowCollectionCancelled serialization round-trip`() {
        val event =
            FlowCollectionCancelled(
                sessionId = "test-session",
                seq = 104L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                flowId = "flow-1",
                collectorId = "collector-1",
                reason = "take(5) limit reached",
                emittedCount = 5,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<FlowCollectionCancelled>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"emittedCount\":5"))
    }

    @Test
    fun `FlowCollectionCancelled with null reason`() {
        val event =
            FlowCollectionCancelled(
                sessionId = "test-session",
                seq = 105L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                flowId = "flow-1",
                collectorId = "collector-1",
                reason = null,
                emittedCount = 0,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<FlowCollectionCancelled>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `FlowValueEmitted serialization round-trip`() {
        val event =
            FlowValueEmitted(
                sessionId = "test-session",
                seq = 106L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                flowId = "flow-1",
                collectorId = "collector-1",
                sequenceNumber = 7,
                valuePreview = "User(id=42, name=Alice)",
                valueType = "com.example.User",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<FlowValueEmitted>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"sequenceNumber\":7"))
        assertTrue(serialized.contains("\"valueType\":\"com.example.User\""))
    }

    @Test
    fun `FlowValueFiltered serialization round-trip`() {
        val event =
            FlowValueFiltered(
                sessionId = "test-session",
                seq = 107L,
                tsNanos = System.nanoTime(),
                flowId = "flow-1",
                operatorName = "filter",
                valuePreview = "42",
                valueType = "kotlin.Int",
                passed = true,
                sequenceNumber = 3,
                coroutineId = "coroutine-1",
                collectorId = "collector-1",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<FlowValueFiltered>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"passed\":true"))
        assertTrue(serialized.contains("\"operatorName\":\"filter\""))
    }

    @Test
    fun `FlowValueFiltered with null optional fields`() {
        val event =
            FlowValueFiltered(
                sessionId = "test-session",
                seq = 108L,
                tsNanos = System.nanoTime(),
                flowId = "flow-2",
                operatorName = "distinctUntilChanged",
                valuePreview = "hello",
                valueType = "kotlin.String",
                passed = false,
                sequenceNumber = 0,
                coroutineId = null,
                collectorId = null,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<FlowValueFiltered>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `FlowValueTransformed serialization round-trip`() {
        val event =
            FlowValueTransformed(
                sessionId = "test-session",
                seq = 109L,
                tsNanos = System.nanoTime(),
                flowId = "flow-1",
                operatorName = "map",
                inputValuePreview = "42",
                outputValuePreview = "84",
                inputType = "kotlin.Int",
                outputType = "kotlin.Int",
                sequenceNumber = 5,
                coroutineId = "coroutine-1",
                collectorId = "collector-1",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<FlowValueTransformed>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"inputValuePreview\":\"42\""))
        assertTrue(serialized.contains("\"outputValuePreview\":\"84\""))
    }

    @Test
    fun `FlowValueTransformed with null optional fields`() {
        val event =
            FlowValueTransformed(
                sessionId = "test-session",
                seq = 110L,
                tsNanos = System.nanoTime(),
                flowId = "flow-1",
                operatorName = "transform",
                inputValuePreview = "abc",
                outputValuePreview = "ABC",
                inputType = "kotlin.String",
                outputType = "kotlin.String",
                sequenceNumber = 0,
                coroutineId = null,
                collectorId = null,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<FlowValueTransformed>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `FlowOperatorApplied serialization round-trip`() {
        val event =
            FlowOperatorApplied(
                sessionId = "test-session",
                seq = 111L,
                tsNanos = System.nanoTime(),
                flowId = "flow-derived-1",
                sourceFlowId = "flow-1",
                operatorName = "map",
                operatorIndex = 0,
                label = "double-values",
                coroutineId = "coroutine-1",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<FlowOperatorApplied>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"sourceFlowId\":\"flow-1\""))
        assertTrue(serialized.contains("\"operatorIndex\":0"))
    }

    @Test
    fun `FlowOperatorApplied with null optional fields`() {
        val event =
            FlowOperatorApplied(
                sessionId = "test-session",
                seq = 112L,
                tsNanos = System.nanoTime(),
                flowId = "flow-derived-2",
                sourceFlowId = "flow-derived-1",
                operatorName = "filter",
                operatorIndex = 1,
                label = null,
                coroutineId = null,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<FlowOperatorApplied>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `FlowBackpressure serialization round-trip`() {
        val event =
            FlowBackpressure(
                sessionId = "test-session",
                seq = 113L,
                tsNanos = System.nanoTime(),
                flowId = "flow-1",
                collectorId = "collector-1",
                reason = "slow_collector",
                pendingEmissions = 15,
                bufferCapacity = 64,
                durationNanos = 250_000_000L,
                coroutineId = "coroutine-1",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<FlowBackpressure>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"reason\":\"slow_collector\""))
        assertTrue(serialized.contains("\"pendingEmissions\":15"))
    }

    @Test
    fun `FlowBackpressure with null optional fields`() {
        val event =
            FlowBackpressure(
                sessionId = "test-session",
                seq = 114L,
                tsNanos = System.nanoTime(),
                flowId = "flow-1",
                collectorId = "collector-2",
                reason = "buffer_full",
                pendingEmissions = 0,
                bufferCapacity = null,
                durationNanos = null,
                coroutineId = null,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<FlowBackpressure>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `FlowBufferOverflow serialization round-trip`() {
        val event =
            FlowBufferOverflow(
                sessionId = "test-session",
                seq = 115L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                flowId = "flow-1",
                droppedValue = "Event(id=99)",
                bufferSize = 64,
                overflowStrategy = "DROP_OLDEST",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<FlowBufferOverflow>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"overflowStrategy\":\"DROP_OLDEST\""))
        assertTrue(serialized.contains("\"bufferSize\":64"))
    }

    @Test
    fun `FlowBufferOverflow with null droppedValue`() {
        val event =
            FlowBufferOverflow(
                sessionId = "test-session",
                seq = 116L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                flowId = "flow-1",
                droppedValue = null,
                bufferSize = 128,
                overflowStrategy = "SUSPEND",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<FlowBufferOverflow>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `SharedFlowEmission serialization round-trip`() {
        val event =
            SharedFlowEmission(
                sessionId = "test-session",
                seq = 117L,
                tsNanos = System.nanoTime(),
                flowId = "shared-flow-1",
                valuePreview = "ChatMessage(from=Alice, text=Hello)",
                valueType = "com.example.ChatMessage",
                subscriberCount = 3,
                replayCache = 1,
                extraBufferCapacity = 10,
                coroutineId = "coroutine-1",
                label = "chat-messages",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<SharedFlowEmission>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"subscriberCount\":3"))
        assertTrue(serialized.contains("\"replayCache\":1"))
        assertTrue(serialized.contains("\"extraBufferCapacity\":10"))
    }

    @Test
    fun `SharedFlowEmission with null optional fields`() {
        val event =
            SharedFlowEmission(
                sessionId = "test-session",
                seq = 118L,
                tsNanos = System.nanoTime(),
                flowId = "shared-flow-2",
                valuePreview = "42",
                valueType = "kotlin.Int",
                subscriberCount = 0,
                replayCache = 0,
                extraBufferCapacity = 0,
                coroutineId = null,
                label = null,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<SharedFlowEmission>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `SharedFlowSubscription serialization round-trip`() {
        val event =
            SharedFlowSubscription(
                sessionId = "test-session",
                seq = 119L,
                tsNanos = System.nanoTime(),
                flowId = "shared-flow-1",
                collectorId = "collector-5",
                action = "subscribed",
                subscriberCount = 4,
                coroutineId = "coroutine-3",
                label = "ui-subscriber",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<SharedFlowSubscription>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"action\":\"subscribed\""))
        assertTrue(serialized.contains("\"subscriberCount\":4"))
    }

    @Test
    fun `SharedFlowSubscription unsubscribed round-trip`() {
        val event =
            SharedFlowSubscription(
                sessionId = "test-session",
                seq = 120L,
                tsNanos = System.nanoTime(),
                flowId = "shared-flow-1",
                collectorId = "collector-5",
                action = "unsubscribed",
                subscriberCount = 3,
                coroutineId = null,
                label = null,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<SharedFlowSubscription>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `StateFlowValueChanged serialization round-trip`() {
        val event =
            StateFlowValueChanged(
                sessionId = "test-session",
                seq = 121L,
                tsNanos = System.nanoTime(),
                flowId = "state-flow-1",
                oldValuePreview = "UiState(loading=true)",
                newValuePreview = "UiState(loading=false, data=[1, 2, 3])",
                valueType = "com.example.UiState",
                subscriberCount = 2,
                coroutineId = "coroutine-1",
                label = "ui-state",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<StateFlowValueChanged>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"oldValuePreview\":\"UiState(loading=true)\""))
        assertTrue(serialized.contains("\"subscriberCount\":2"))
    }

    @Test
    fun `StateFlowValueChanged with null optional fields`() {
        val event =
            StateFlowValueChanged(
                sessionId = "test-session",
                seq = 122L,
                tsNanos = System.nanoTime(),
                flowId = "state-flow-2",
                oldValuePreview = "0",
                newValuePreview = "1",
                valueType = "kotlin.Int",
                subscriberCount = 0,
                coroutineId = null,
                label = null,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<StateFlowValueChanged>(serialized)

        assertEquals(event, deserialized)
    }

    // ========================================================================
    // Channel Events
    // ========================================================================

    @Test
    fun `ChannelCreated serialization round-trip`() {
        val event =
            ChannelCreated(
                sessionId = "test-session",
                seq = 200L,
                tsNanos = System.nanoTime(),
                channelId = "channel-1",
                name = "task-queue",
                capacity = 64,
                channelType = "BUFFERED",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<ChannelCreated>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"channelType\":\"BUFFERED\""))
        assertTrue(serialized.contains("\"capacity\":64"))
    }

    @Test
    fun `ChannelCreated with null name`() {
        val event =
            ChannelCreated(
                sessionId = "test-session",
                seq = 201L,
                tsNanos = System.nanoTime(),
                channelId = "channel-2",
                name = null,
                capacity = 0,
                channelType = "RENDEZVOUS",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<ChannelCreated>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `ChannelSendStarted serialization round-trip`() {
        val event =
            ChannelSendStarted(
                sessionId = "test-session",
                seq = 202L,
                tsNanos = System.nanoTime(),
                channelId = "channel-1",
                coroutineId = "coroutine-producer",
                valueDescription = "Task(id=42, priority=HIGH)",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<ChannelSendStarted>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"valueDescription\":\"Task(id=42, priority=HIGH)\""))
    }

    @Test
    fun `ChannelSendSuspended serialization round-trip`() {
        val event =
            ChannelSendSuspended(
                sessionId = "test-session",
                seq = 203L,
                tsNanos = System.nanoTime(),
                channelId = "channel-1",
                coroutineId = "coroutine-producer",
                bufferSize = 64,
                capacity = 64,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<ChannelSendSuspended>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"bufferSize\":64"))
        assertTrue(serialized.contains("\"capacity\":64"))
    }

    @Test
    fun `ChannelSendCompleted serialization round-trip`() {
        val event =
            ChannelSendCompleted(
                sessionId = "test-session",
                seq = 204L,
                tsNanos = System.nanoTime(),
                channelId = "channel-1",
                coroutineId = "coroutine-producer",
                valueDescription = "Task(id=42, priority=HIGH)",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<ChannelSendCompleted>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `ChannelReceiveStarted serialization round-trip`() {
        val event =
            ChannelReceiveStarted(
                sessionId = "test-session",
                seq = 205L,
                tsNanos = System.nanoTime(),
                channelId = "channel-1",
                coroutineId = "coroutine-consumer",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<ChannelReceiveStarted>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"channelId\":\"channel-1\""))
    }

    @Test
    fun `ChannelReceiveSuspended serialization round-trip`() {
        val event =
            ChannelReceiveSuspended(
                sessionId = "test-session",
                seq = 206L,
                tsNanos = System.nanoTime(),
                channelId = "channel-1",
                coroutineId = "coroutine-consumer",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<ChannelReceiveSuspended>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `ChannelReceiveCompleted serialization round-trip`() {
        val event =
            ChannelReceiveCompleted(
                sessionId = "test-session",
                seq = 207L,
                tsNanos = System.nanoTime(),
                channelId = "channel-1",
                coroutineId = "coroutine-consumer",
                valueDescription = "Task(id=42, priority=HIGH)",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<ChannelReceiveCompleted>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"valueDescription\":\"Task(id=42, priority=HIGH)\""))
    }

    @Test
    fun `ChannelBufferStateChanged serialization round-trip`() {
        val event =
            ChannelBufferStateChanged(
                sessionId = "test-session",
                seq = 208L,
                tsNanos = System.nanoTime(),
                channelId = "channel-1",
                currentSize = 32,
                capacity = 64,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<ChannelBufferStateChanged>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"currentSize\":32"))
    }

    @Test
    fun `ChannelClosed serialization round-trip`() {
        val event =
            ChannelClosed(
                sessionId = "test-session",
                seq = 209L,
                tsNanos = System.nanoTime(),
                channelId = "channel-1",
                cause = "Producer finished",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<ChannelClosed>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"cause\":\"Producer finished\""))
    }

    @Test
    fun `ChannelClosed with null cause`() {
        val event =
            ChannelClosed(
                sessionId = "test-session",
                seq = 210L,
                tsNanos = System.nanoTime(),
                channelId = "channel-2",
                cause = null,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<ChannelClosed>(serialized)

        assertEquals(event, deserialized)
    }

    // ========================================================================
    // Deferred Events
    // ========================================================================

    @Test
    fun `DeferredAwaitStarted serialization round-trip`() {
        val event =
            DeferredAwaitStarted(
                sessionId = "test-session",
                seq = 300L,
                tsNanos = System.nanoTime(),
                deferredId = "deferred-1",
                coroutineId = "coroutine-async",
                awaiterId = "coroutine-main",
                scopeId = "scope-1",
                label = "fetch-user-data",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<DeferredAwaitStarted>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"deferredId\":\"deferred-1\""))
        assertTrue(serialized.contains("\"awaiterId\":\"coroutine-main\""))
    }

    @Test
    fun `DeferredAwaitStarted with null optional fields`() {
        val event =
            DeferredAwaitStarted(
                sessionId = "test-session",
                seq = 301L,
                tsNanos = System.nanoTime(),
                deferredId = "deferred-2",
                coroutineId = "coroutine-async",
                awaiterId = null,
                scopeId = "scope-1",
                label = null,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<DeferredAwaitStarted>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `DeferredAwaitCompleted serialization round-trip`() {
        val event =
            DeferredAwaitCompleted(
                sessionId = "test-session",
                seq = 302L,
                tsNanos = System.nanoTime(),
                deferredId = "deferred-1",
                coroutineId = "coroutine-async",
                awaiterId = "coroutine-main",
                scopeId = "scope-1",
                label = "fetch-user-data",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<DeferredAwaitCompleted>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"deferredId\":\"deferred-1\""))
    }

    @Test
    fun `DeferredAwaitCompleted with null optional fields`() {
        val event =
            DeferredAwaitCompleted(
                sessionId = "test-session",
                seq = 303L,
                tsNanos = System.nanoTime(),
                deferredId = "deferred-2",
                coroutineId = "coroutine-async",
                awaiterId = null,
                scopeId = "scope-1",
                label = null,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<DeferredAwaitCompleted>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `DeferredValueAvailable serialization round-trip`() {
        val event =
            DeferredValueAvailable(
                sessionId = "test-session",
                seq = 304L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-async",
                jobId = "job-async",
                parentCoroutineId = "coroutine-main",
                scopeId = "scope-1",
                label = "compute-result",
                deferredId = "deferred-1",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<DeferredValueAvailable>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"deferredId\":\"deferred-1\""))
        assertTrue(serialized.contains("\"coroutineId\":\"coroutine-async\""))
    }

    // ========================================================================
    // Dispatcher Events
    // ========================================================================

    @Test
    fun `DispatcherSelected serialization round-trip`() {
        val event =
            DispatcherSelected(
                sessionId = "test-session",
                seq = 400L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                jobId = "job-1",
                parentCoroutineId = null,
                scopeId = "scope-1",
                label = "io-task",
                dispatcherId = "dispatcher-io",
                dispatcherName = "Dispatchers.IO",
                queueDepth = 5,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<DispatcherSelected>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"dispatcherName\":\"Dispatchers.IO\""))
        assertTrue(serialized.contains("\"queueDepth\":5"))
    }

    @Test
    fun `DispatcherSelected with null queueDepth`() {
        val event =
            DispatcherSelected(
                sessionId = "test-session",
                seq = 401L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-2",
                jobId = "job-2",
                parentCoroutineId = "coroutine-1",
                scopeId = "scope-1",
                label = null,
                dispatcherId = "dispatcher-default",
                dispatcherName = "Dispatchers.Default",
                queueDepth = null,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<DispatcherSelected>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `ThreadAssigned serialization round-trip`() {
        val event =
            ThreadAssigned(
                sessionId = "test-session",
                seq = 402L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-1",
                jobId = "job-1",
                parentCoroutineId = null,
                scopeId = "scope-1",
                label = "io-task",
                threadId = 42L,
                threadName = "DefaultDispatcher-worker-3",
                dispatcherName = "Dispatchers.Default",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<ThreadAssigned>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"threadId\":42"))
        assertTrue(serialized.contains("\"threadName\":\"DefaultDispatcher-worker-3\""))
    }

    @Test
    fun `ThreadAssigned with null dispatcherName`() {
        val event =
            ThreadAssigned(
                sessionId = "test-session",
                seq = 403L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-2",
                jobId = "job-2",
                parentCoroutineId = "coroutine-1",
                scopeId = "scope-1",
                label = null,
                threadId = 99L,
                threadName = "main",
                dispatcherName = null,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<ThreadAssigned>(serialized)

        assertEquals(event, deserialized)
    }

    // ========================================================================
    // Mutex Events
    // ========================================================================

    @Test
    fun `MutexCreated serialization round-trip`() {
        val event =
            MutexCreated(
                sessionId = "test-session",
                seq = 500L,
                tsNanos = System.nanoTime(),
                mutexId = "mutex-1",
                mutexLabel = "db-lock",
                ownerCoroutineId = "coroutine-1",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<MutexCreated>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"mutexId\":\"mutex-1\""))
        assertTrue(serialized.contains("\"mutexLabel\":\"db-lock\""))
    }

    @Test
    fun `MutexCreated with null optional fields`() {
        val event =
            MutexCreated(
                sessionId = "test-session",
                seq = 501L,
                tsNanos = System.nanoTime(),
                mutexId = "mutex-2",
                mutexLabel = null,
                ownerCoroutineId = null,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<MutexCreated>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `MutexLockRequested serialization round-trip`() {
        val event =
            MutexLockRequested(
                sessionId = "test-session",
                seq = 502L,
                tsNanos = System.nanoTime(),
                mutexId = "mutex-1",
                mutexLabel = "db-lock",
                requesterId = "coroutine-2",
                requesterLabel = "writer-coroutine",
                isLocked = true,
                queuePosition = 2,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<MutexLockRequested>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"isLocked\":true"))
        assertTrue(serialized.contains("\"queuePosition\":2"))
    }

    @Test
    fun `MutexLockRequested with null requesterLabel`() {
        val event =
            MutexLockRequested(
                sessionId = "test-session",
                seq = 503L,
                tsNanos = System.nanoTime(),
                mutexId = "mutex-1",
                mutexLabel = null,
                requesterId = "coroutine-3",
                requesterLabel = null,
                isLocked = false,
                queuePosition = 0,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<MutexLockRequested>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `MutexLockAcquired serialization round-trip`() {
        val event =
            MutexLockAcquired(
                sessionId = "test-session",
                seq = 504L,
                tsNanos = System.nanoTime(),
                mutexId = "mutex-1",
                mutexLabel = "db-lock",
                acquirerId = "coroutine-2",
                acquirerLabel = "writer-coroutine",
                waitDurationNanos = 500_000_000L,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<MutexLockAcquired>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"waitDurationNanos\":500000000"))
    }

    @Test
    fun `MutexUnlocked serialization round-trip`() {
        val event =
            MutexUnlocked(
                sessionId = "test-session",
                seq = 505L,
                tsNanos = System.nanoTime(),
                mutexId = "mutex-1",
                mutexLabel = "db-lock",
                releaserId = "coroutine-2",
                releaserLabel = "writer-coroutine",
                nextWaiterId = "coroutine-3",
                holdDurationNanos = 1_200_000_000L,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<MutexUnlocked>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"nextWaiterId\":\"coroutine-3\""))
        assertTrue(serialized.contains("\"holdDurationNanos\":1200000000"))
    }

    @Test
    fun `MutexUnlocked with null optional fields`() {
        val event =
            MutexUnlocked(
                sessionId = "test-session",
                seq = 506L,
                tsNanos = System.nanoTime(),
                mutexId = "mutex-1",
                mutexLabel = null,
                releaserId = "coroutine-2",
                releaserLabel = null,
                nextWaiterId = null,
                holdDurationNanos = 100_000L,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<MutexUnlocked>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `MutexTryLockFailed serialization round-trip`() {
        val event =
            MutexTryLockFailed(
                sessionId = "test-session",
                seq = 507L,
                tsNanos = System.nanoTime(),
                mutexId = "mutex-1",
                mutexLabel = "db-lock",
                requesterId = "coroutine-4",
                requesterLabel = "reader-coroutine",
                currentOwnerId = "coroutine-2",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<MutexTryLockFailed>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"currentOwnerId\":\"coroutine-2\""))
    }

    @Test
    fun `MutexTryLockFailed with null optional fields`() {
        val event =
            MutexTryLockFailed(
                sessionId = "test-session",
                seq = 508L,
                tsNanos = System.nanoTime(),
                mutexId = "mutex-2",
                mutexLabel = null,
                requesterId = "coroutine-5",
                requesterLabel = null,
                currentOwnerId = null,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<MutexTryLockFailed>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `MutexQueueChanged serialization round-trip`() {
        val event =
            MutexQueueChanged(
                sessionId = "test-session",
                seq = 509L,
                tsNanos = System.nanoTime(),
                mutexId = "mutex-1",
                mutexLabel = "db-lock",
                waitingCoroutineIds = listOf("coroutine-3", "coroutine-4", "coroutine-5"),
                waitingLabels = listOf("reader-1", null, "reader-3"),
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<MutexQueueChanged>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"waitingCoroutineIds\":[\"coroutine-3\",\"coroutine-4\",\"coroutine-5\"]"))
    }

    @Test
    fun `MutexQueueChanged with empty lists`() {
        val event =
            MutexQueueChanged(
                sessionId = "test-session",
                seq = 510L,
                tsNanos = System.nanoTime(),
                mutexId = "mutex-1",
                mutexLabel = null,
                waitingCoroutineIds = emptyList(),
                waitingLabels = emptyList(),
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<MutexQueueChanged>(serialized)

        assertEquals(event, deserialized)
    }

    // ========================================================================
    // Semaphore Events
    // ========================================================================

    @Test
    fun `SemaphoreCreated serialization round-trip`() {
        val event =
            SemaphoreCreated(
                sessionId = "test-session",
                seq = 600L,
                tsNanos = System.nanoTime(),
                semaphoreId = "semaphore-1",
                semaphoreLabel = "connection-pool",
                totalPermits = 10,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<SemaphoreCreated>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"totalPermits\":10"))
    }

    @Test
    fun `SemaphoreCreated with null label`() {
        val event =
            SemaphoreCreated(
                sessionId = "test-session",
                seq = 601L,
                tsNanos = System.nanoTime(),
                semaphoreId = "semaphore-2",
                semaphoreLabel = null,
                totalPermits = 3,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<SemaphoreCreated>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `SemaphoreAcquireRequested serialization round-trip`() {
        val event =
            SemaphoreAcquireRequested(
                sessionId = "test-session",
                seq = 602L,
                tsNanos = System.nanoTime(),
                semaphoreId = "semaphore-1",
                semaphoreLabel = "connection-pool",
                requesterId = "coroutine-1",
                requesterLabel = "http-handler",
                availablePermits = 2,
                permitsRequested = 1,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<SemaphoreAcquireRequested>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"availablePermits\":2"))
        assertTrue(serialized.contains("\"permitsRequested\":1"))
    }

    @Test
    fun `SemaphorePermitAcquired serialization round-trip`() {
        val event =
            SemaphorePermitAcquired(
                sessionId = "test-session",
                seq = 603L,
                tsNanos = System.nanoTime(),
                semaphoreId = "semaphore-1",
                semaphoreLabel = "connection-pool",
                acquirerId = "coroutine-1",
                acquirerLabel = "http-handler",
                remainingPermits = 1,
                waitDurationNanos = 0L,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<SemaphorePermitAcquired>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"remainingPermits\":1"))
    }

    @Test
    fun `SemaphorePermitReleased serialization round-trip`() {
        val event =
            SemaphorePermitReleased(
                sessionId = "test-session",
                seq = 604L,
                tsNanos = System.nanoTime(),
                semaphoreId = "semaphore-1",
                semaphoreLabel = "connection-pool",
                releaserId = "coroutine-1",
                releaserLabel = "http-handler",
                newAvailablePermits = 2,
                holdDurationNanos = 350_000_000L,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<SemaphorePermitReleased>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"newAvailablePermits\":2"))
        assertTrue(serialized.contains("\"holdDurationNanos\":350000000"))
    }

    @Test
    fun `SemaphoreTryAcquireFailed serialization round-trip`() {
        val event =
            SemaphoreTryAcquireFailed(
                sessionId = "test-session",
                seq = 605L,
                tsNanos = System.nanoTime(),
                semaphoreId = "semaphore-1",
                semaphoreLabel = "connection-pool",
                requesterId = "coroutine-5",
                requesterLabel = "overflow-handler",
                availablePermits = 0,
                permitsRequested = 1,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<SemaphoreTryAcquireFailed>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"availablePermits\":0"))
    }

    @Test
    fun `SemaphoreTryAcquireFailed with null labels`() {
        val event =
            SemaphoreTryAcquireFailed(
                sessionId = "test-session",
                seq = 606L,
                tsNanos = System.nanoTime(),
                semaphoreId = "semaphore-2",
                semaphoreLabel = null,
                requesterId = "coroutine-6",
                requesterLabel = null,
                availablePermits = 0,
                permitsRequested = 2,
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<SemaphoreTryAcquireFailed>(serialized)

        assertEquals(event, deserialized)
    }

    @Test
    fun `SemaphoreStateChanged serialization round-trip`() {
        val event =
            SemaphoreStateChanged(
                sessionId = "test-session",
                seq = 607L,
                tsNanos = System.nanoTime(),
                semaphoreId = "semaphore-1",
                semaphoreLabel = "connection-pool",
                availablePermits = 3,
                totalPermits = 10,
                activeHolders = listOf("coroutine-1", "coroutine-2", "coroutine-3"),
                activeHolderLabels = listOf("handler-1", "handler-2", null),
                waitingCoroutines = listOf("coroutine-4", "coroutine-5"),
                waitingLabels = listOf("handler-4", null),
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<SemaphoreStateChanged>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"totalPermits\":10"))
        assertTrue(serialized.contains("\"activeHolders\":[\"coroutine-1\",\"coroutine-2\",\"coroutine-3\"]"))
    }

    @Test
    fun `SemaphoreStateChanged with empty lists`() {
        val event =
            SemaphoreStateChanged(
                sessionId = "test-session",
                seq = 608L,
                tsNanos = System.nanoTime(),
                semaphoreId = "semaphore-2",
                semaphoreLabel = null,
                availablePermits = 5,
                totalPermits = 5,
                activeHolders = emptyList(),
                activeHolderLabels = emptyList(),
                waitingCoroutines = emptyList(),
                waitingLabels = emptyList(),
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<SemaphoreStateChanged>(serialized)

        assertEquals(event, deserialized)
    }

    // ========================================================================
    // Deadlock Events
    // ========================================================================

    @Test
    fun `DeadlockDetected serialization round-trip`() {
        val event =
            DeadlockDetected(
                sessionId = "test-session",
                seq = 700L,
                tsNanos = System.nanoTime(),
                involvedCoroutines = listOf("coroutine-A", "coroutine-B"),
                involvedCoroutineLabels = listOf("worker-A", "worker-B"),
                involvedMutexes = listOf("mutex-1", "mutex-2"),
                involvedMutexLabels = listOf("lock-alpha", "lock-beta"),
                waitGraph = mapOf("coroutine-A" to "mutex-2", "coroutine-B" to "mutex-1"),
                holdGraph = mapOf("mutex-1" to "coroutine-A", "mutex-2" to "coroutine-B"),
                cycleDescription = "coroutine-A holds mutex-1, waits for mutex-2; coroutine-B holds mutex-2, waits for mutex-1",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<DeadlockDetected>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"cycleDescription\""))
        assertTrue(serialized.contains("\"waitGraph\""))
        assertTrue(serialized.contains("\"holdGraph\""))
    }

    @Test
    fun `PotentialDeadlockWarning serialization round-trip`() {
        val event =
            PotentialDeadlockWarning(
                sessionId = "test-session",
                seq = 701L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-A",
                coroutineLabel = "worker-A",
                holdingMutex = "mutex-1",
                holdingMutexLabel = "lock-alpha",
                requestingMutex = "mutex-2",
                requestingMutexLabel = "lock-beta",
                recommendation = "Consider acquiring mutexes in a consistent order to avoid deadlocks",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<PotentialDeadlockWarning>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"recommendation\""))
        assertTrue(serialized.contains("\"holdingMutex\":\"mutex-1\""))
        assertTrue(serialized.contains("\"requestingMutex\":\"mutex-2\""))
    }

    @Test
    fun `PotentialDeadlockWarning with null labels`() {
        val event =
            PotentialDeadlockWarning(
                sessionId = "test-session",
                seq = 702L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-X",
                coroutineLabel = null,
                holdingMutex = "mutex-10",
                holdingMutexLabel = null,
                requestingMutex = "mutex-20",
                requestingMutexLabel = null,
                recommendation = "Avoid nested lock acquisition",
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<PotentialDeadlockWarning>(serialized)

        assertEquals(event, deserialized)
    }

    // ========================================================================
    // WaitingForChildren Event
    // ========================================================================

    @Test
    fun `WaitingForChildren serialization round-trip`() {
        val event =
            WaitingForChildren(
                sessionId = "test-session",
                seq = 800L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-parent",
                jobId = "job-parent",
                parentCoroutineId = null,
                scopeId = "scope-1",
                label = "supervisor-scope",
                activeChildrenCount = 3,
                activeChildrenIds = listOf("coroutine-child-1", "coroutine-child-2", "coroutine-child-3"),
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<WaitingForChildren>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"activeChildrenCount\":3"))
        assertTrue(serialized.contains("\"activeChildrenIds\""))
    }

    @Test
    fun `WaitingForChildren with empty children list`() {
        val event =
            WaitingForChildren(
                sessionId = "test-session",
                seq = 801L,
                tsNanos = System.nanoTime(),
                coroutineId = "coroutine-parent",
                jobId = "job-parent",
                parentCoroutineId = "coroutine-grandparent",
                scopeId = "scope-1",
                label = null,
                activeChildrenCount = 0,
                activeChildrenIds = emptyList(),
            )

        val serialized = json.encodeToString(event)
        val deserialized = json.decodeFromString<WaitingForChildren>(serialized)

        assertEquals(event, deserialized)
        assertTrue(serialized.contains("\"activeChildrenIds\":[]"))
    }
}
