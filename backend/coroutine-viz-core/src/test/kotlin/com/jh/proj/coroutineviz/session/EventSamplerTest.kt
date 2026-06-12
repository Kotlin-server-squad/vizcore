package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.events.dispatcher.ThreadAssigned
import com.jh.proj.coroutineviz.events.flow.FlowValueEmitted
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventSamplerTest {
    // -- helpers --

    private fun suspendedEvent(seq: Long): CoroutineSuspended =
        CoroutineSuspended(
            sessionId = "s1",
            seq = seq,
            tsNanos = System.nanoTime(),
            coroutineId = "c-$seq",
            jobId = "j-$seq",
            parentCoroutineId = null,
            scopeId = "scope",
            label = null,
            reason = "delay",
        )

    private fun threadAssignedEvent(seq: Long): ThreadAssigned =
        ThreadAssigned(
            sessionId = "s1",
            seq = seq,
            tsNanos = System.nanoTime(),
            coroutineId = "c-$seq",
            jobId = "j-$seq",
            parentCoroutineId = null,
            scopeId = "scope",
            label = null,
            threadId = 1L,
            threadName = "worker-1",
            dispatcherName = "Default",
        )

    private fun flowEmittedEvent(seq: Long): FlowValueEmitted =
        FlowValueEmitted(
            sessionId = "s1",
            seq = seq,
            tsNanos = System.nanoTime(),
            coroutineId = "c-$seq",
            flowId = "flow-1",
            collectorId = "collector-1",
            sequenceNumber = seq.toInt(),
            valuePreview = "value-$seq",
            valueType = "Int",
        )

    private fun createdEvent(seq: Long): CoroutineCreated =
        CoroutineCreated(
            sessionId = "s1",
            seq = seq,
            tsNanos = System.nanoTime(),
            coroutineId = "c-$seq",
            jobId = "j-$seq",
            parentCoroutineId = null,
            scopeId = "scope",
            label = null,
        )

    private fun startedEvent(seq: Long): CoroutineStarted =
        CoroutineStarted(
            sessionId = "s1",
            seq = seq,
            tsNanos = System.nanoTime(),
            coroutineId = "c-$seq",
            jobId = "j-$seq",
            parentCoroutineId = null,
            scopeId = "scope",
            label = null,
        )

    private fun completedEvent(seq: Long): CoroutineCompleted =
        CoroutineCompleted(
            sessionId = "s1",
            seq = seq,
            tsNanos = System.nanoTime(),
            coroutineId = "c-$seq",
            jobId = "j-$seq",
            parentCoroutineId = null,
            scopeId = "scope",
            label = null,
        )

    private fun failedEvent(seq: Long): CoroutineFailed =
        CoroutineFailed(
            sessionId = "s1",
            seq = seq,
            tsNanos = System.nanoTime(),
            coroutineId = "c-$seq",
            jobId = "j-$seq",
            parentCoroutineId = null,
            scopeId = "scope",
            label = null,
            exceptionType = "RuntimeException",
            message = "boom",
            stackTrace = emptyList(),
        )

    private fun cancelledEvent(seq: Long): CoroutineCancelled =
        CoroutineCancelled(
            sessionId = "s1",
            seq = seq,
            tsNanos = System.nanoTime(),
            coroutineId = "c-$seq",
            jobId = "j-$seq",
            parentCoroutineId = null,
            scopeId = "scope",
            label = null,
            cause = "parent cancelled",
        )

    // -- tests --

    @Test
    fun `default rate of 1_0 keeps all events`() {
        val sampler = EventSampler(defaultRate = 1.0)
        val events = (1L..100L).map { suspendedEvent(it) }

        val kept = events.count { sampler.shouldKeep(it) }
        assertEquals(100, kept, "Rate 1.0 should keep all events")
    }

    @Test
    fun `rate 0_0 drops all non-lifecycle events`() {
        val sampler = EventSampler(defaultRate = 0.0)
        val events = (1L..100L).map { suspendedEvent(it) }

        val kept = events.count { sampler.shouldKeep(it) }
        assertEquals(0, kept, "Rate 0.0 should drop all non-lifecycle events")
    }

    @Test
    fun `rate 0_0 drops all thread-assigned events`() {
        val sampler = EventSampler(defaultRate = 0.0)
        val events = (1L..50L).map { threadAssignedEvent(it) }

        val kept = events.count { sampler.shouldKeep(it) }
        assertEquals(0, kept, "Rate 0.0 should drop all ThreadAssigned events")
    }

    @Test
    fun `per-type rate overrides default rate`() {
        val sampler = EventSampler(
            defaultRate = 1.0,
            perTypeRates = mapOf("CoroutineSuspended" to 0.0),
        )

        // Suspended events should all be dropped (per-type = 0.0)
        val suspended = (1L..50L).map { suspendedEvent(it) }
        assertEquals(0, suspended.count { sampler.shouldKeep(it) })

        // ThreadAssigned events should all be kept (default = 1.0)
        val assigned = (1L..50L).map { threadAssignedEvent(it) }
        assertEquals(50, assigned.count { sampler.shouldKeep(it) })
    }

    @Test
    fun `per-type rate can be more permissive than default`() {
        val sampler = EventSampler(
            defaultRate = 0.0,
            perTypeRates = mapOf("FlowValueEmitted" to 1.0),
        )

        // FlowValueEmitted should all be kept (per-type = 1.0)
        val flowEvents = (1L..50L).map { flowEmittedEvent(it) }
        assertEquals(50, flowEvents.count { sampler.shouldKeep(it) })

        // Other non-lifecycle events should all be dropped (default = 0.0)
        val suspended = (1L..50L).map { suspendedEvent(it) }
        assertEquals(0, suspended.count { sampler.shouldKeep(it) })
    }

    @Test
    fun `lifecycle events always kept - CoroutineCreated`() {
        val sampler = EventSampler(
            defaultRate = 0.0,
            perTypeRates = mapOf("CoroutineCreated" to 0.0),
        )
        val events = (1L..20L).map { createdEvent(it) }

        val kept = events.count { sampler.shouldKeep(it) }
        assertEquals(20, kept, "CoroutineCreated events must always be kept")
    }

    @Test
    fun `lifecycle events always kept - CoroutineStarted`() {
        val sampler = EventSampler(defaultRate = 0.0)
        val events = (1L..20L).map { startedEvent(it) }

        val kept = events.count { sampler.shouldKeep(it) }
        assertEquals(20, kept, "CoroutineStarted events must always be kept")
    }

    @Test
    fun `lifecycle events always kept - CoroutineCompleted`() {
        val sampler = EventSampler(defaultRate = 0.0)
        val events = (1L..20L).map { completedEvent(it) }

        val kept = events.count { sampler.shouldKeep(it) }
        assertEquals(20, kept, "CoroutineCompleted events must always be kept")
    }

    @Test
    fun `lifecycle events always kept - CoroutineFailed`() {
        val sampler = EventSampler(defaultRate = 0.0)
        val events = (1L..20L).map { failedEvent(it) }

        val kept = events.count { sampler.shouldKeep(it) }
        assertEquals(20, kept, "CoroutineFailed events must always be kept")
    }

    @Test
    fun `lifecycle events always kept - CoroutineCancelled`() {
        val sampler = EventSampler(defaultRate = 0.0)
        val events = (1L..20L).map { cancelledEvent(it) }

        val kept = events.count { sampler.shouldKeep(it) }
        assertEquals(20, kept, "CoroutineCancelled events must always be kept")
    }

    @Test
    fun `deterministic sampling - same seq always produces same result`() {
        val sampler = EventSampler(defaultRate = 0.5)

        for (seq in 1L..200L) {
            val event = suspendedEvent(seq)
            val first = sampler.shouldKeep(event)
            val second = sampler.shouldKeep(event)
            val third = sampler.shouldKeep(event)
            assertEquals(first, second, "Seq $seq should be deterministic (1st vs 2nd)")
            assertEquals(second, third, "Seq $seq should be deterministic (2nd vs 3rd)")
        }
    }

    @Test
    fun `deterministic sampling - different sampler instances agree`() {
        val sampler1 = EventSampler(defaultRate = 0.3)
        val sampler2 = EventSampler(defaultRate = 0.3)

        for (seq in 1L..200L) {
            val event = suspendedEvent(seq)
            assertEquals(
                sampler1.shouldKeep(event),
                sampler2.shouldKeep(event),
                "Two samplers with same rate should agree for seq $seq",
            )
        }
    }

    @Test
    fun `probabilistic sampling keeps approximately the right fraction`() {
        val sampler = EventSampler(defaultRate = 0.5)
        val total = 10_000
        val events = (1L..total.toLong()).map { suspendedEvent(it) }

        val kept = events.count { sampler.shouldKeep(it) }
        val ratio = kept.toDouble() / total

        // Allow 5% tolerance for a deterministic hash distribution
        assertTrue(ratio > 0.40, "Expected ~50% kept, got ${ratio * 100}%")
        assertTrue(ratio < 0.60, "Expected ~50% kept, got ${ratio * 100}%")
    }

    @Test
    fun `probabilistic sampling at 10 percent keeps roughly 10 percent`() {
        val sampler = EventSampler(defaultRate = 0.1)
        val total = 10_000
        val events = (1L..total.toLong()).map { suspendedEvent(it) }

        val kept = events.count { sampler.shouldKeep(it) }
        val ratio = kept.toDouble() / total

        assertTrue(ratio > 0.05, "Expected ~10% kept, got ${ratio * 100}%")
        assertTrue(ratio < 0.15, "Expected ~10% kept, got ${ratio * 100}%")
    }

    @Test
    fun `updateRate changes effective rate at runtime`() {
        val sampler = EventSampler(defaultRate = 1.0)

        assertEquals(1.0, sampler.getEffectiveRate("FlowValueEmitted"))

        sampler.updateRate("FlowValueEmitted", 0.0)
        assertEquals(0.0, sampler.getEffectiveRate("FlowValueEmitted"))

        // FlowValueEmitted should now be dropped
        val events = (1L..50L).map { flowEmittedEvent(it) }
        assertEquals(0, events.count { sampler.shouldKeep(it) })
    }

    @Test
    fun `getEffectiveRate returns default when no per-type rate set`() {
        val sampler = EventSampler(defaultRate = 0.7)
        assertEquals(0.7, sampler.getEffectiveRate("SomeUnknownEvent"))
    }

    @Test
    fun `getEffectiveRate returns per-type rate when configured`() {
        val sampler = EventSampler(
            defaultRate = 1.0,
            perTypeRates = mapOf("ThreadAssigned" to 0.25),
        )
        assertEquals(0.25, sampler.getEffectiveRate("ThreadAssigned"))
        assertEquals(1.0, sampler.getEffectiveRate("CoroutineSuspended"))
    }

    @Test
    fun `constructor rejects default rate below 0`() {
        assertThrows<IllegalArgumentException> {
            EventSampler(defaultRate = -0.1)
        }
    }

    @Test
    fun `constructor rejects default rate above 1`() {
        assertThrows<IllegalArgumentException> {
            EventSampler(defaultRate = 1.5)
        }
    }

    @Test
    fun `constructor rejects per-type rate out of range`() {
        assertThrows<IllegalArgumentException> {
            EventSampler(perTypeRates = mapOf("Foo" to 2.0))
        }
    }

    @Test
    fun `updateRate rejects out of range values`() {
        val sampler = EventSampler()
        assertThrows<IllegalArgumentException> {
            sampler.updateRate("Foo", -0.5)
        }
        assertThrows<IllegalArgumentException> {
            sampler.updateRate("Foo", 1.1)
        }
    }

    @Test
    fun `non-coroutine lifecycle events with matching suffixes are always kept`() {
        // FlowCollectionCompleted, FlowCollectionCancelled, ChannelCreated, etc.
        // should also be treated as lifecycle events
        val sampler = EventSampler(defaultRate = 0.0)

        // Create a simple VizEvent with a lifecycle-like kind
        data class FakeEvent(
            override val sessionId: String,
            override var seq: Long,
            override val tsNanos: Long,
            override val kind: String,
        ) : VizEvent

        val lifecycleKinds = listOf(
            "FlowCollectionCompleted",
            "FlowCollectionCancelled",
            "ChannelCreated",
            "FlowCollectionStarted",
            "ActorCreated",
        )

        for (kind in lifecycleKinds) {
            val event = FakeEvent("s1", 1L, System.nanoTime(), kind)
            assertTrue(sampler.shouldKeep(event), "$kind should always be kept as a lifecycle event")
        }

        // Non-lifecycle kinds should be dropped
        val nonLifecycle = listOf(
            "FlowValueEmitted",
            "ThreadAssigned",
            "CoroutineSuspended",
            "MutexLockRequested",
        )
        for (kind in nonLifecycle) {
            val event = FakeEvent("s1", 1L, System.nanoTime(), kind)
            assertFalse(sampler.shouldKeep(event), "$kind should NOT be treated as lifecycle at rate 0.0")
        }
    }
}
