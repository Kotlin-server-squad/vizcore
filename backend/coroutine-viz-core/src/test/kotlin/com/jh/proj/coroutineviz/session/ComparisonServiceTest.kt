package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.dispatcher.ThreadAssigned
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComparisonServiceTest {

    private fun createSession(id: String): VizSession = VizSession(id)

    private fun createdEvent(
        sessionId: String,
        seq: Long,
        coroutineId: String,
        label: String? = null,
        tsNanos: Long = System.nanoTime(),
    ) = CoroutineCreated(
        sessionId = sessionId,
        seq = seq,
        tsNanos = tsNanos,
        coroutineId = coroutineId,
        jobId = "job-$coroutineId",
        parentCoroutineId = null,
        scopeId = "scope-1",
        label = label,
    )

    private fun startedEvent(
        sessionId: String,
        seq: Long,
        coroutineId: String,
        label: String? = null,
        tsNanos: Long = System.nanoTime(),
    ) = CoroutineStarted(
        sessionId = sessionId,
        seq = seq,
        tsNanos = tsNanos,
        coroutineId = coroutineId,
        jobId = "job-$coroutineId",
        parentCoroutineId = null,
        scopeId = "scope-1",
        label = label,
    )

    private fun completedEvent(
        sessionId: String,
        seq: Long,
        coroutineId: String,
        label: String? = null,
        tsNanos: Long = System.nanoTime(),
    ) = CoroutineCompleted(
        sessionId = sessionId,
        seq = seq,
        tsNanos = tsNanos,
        coroutineId = coroutineId,
        jobId = "job-$coroutineId",
        parentCoroutineId = null,
        scopeId = "scope-1",
        label = label,
    )

    @Test
    fun `compare two identical sessions produces no diff`() {
        val sessionA = createSession("session-a")
        val sessionB = createSession("session-b")

        // Populate both sessions with identical coroutines
        sessionA.send(createdEvent("session-a", 1, "c-1", "worker"))
        sessionA.send(startedEvent("session-a", 2, "c-1", "worker"))
        sessionA.send(completedEvent("session-a", 3, "c-1", "worker"))

        sessionB.send(createdEvent("session-b", 1, "c-1", "worker"))
        sessionB.send(startedEvent("session-b", 2, "c-1", "worker"))
        sessionB.send(completedEvent("session-b", 3, "c-1", "worker"))

        val result = ComparisonService.compare(sessionA, sessionB)

        assertEquals("session-a", result.sessionA)
        assertEquals("session-b", result.sessionB)
        assertEquals(0, result.coroutineCountDiff)
        assertEquals(0, result.eventCountDiff)
        assertTrue(result.coroutinesOnlyInA.isEmpty())
        assertTrue(result.coroutinesOnlyInB.isEmpty())
        assertEquals(1, result.commonCoroutines.size)

        val common = result.commonCoroutines.first()
        assertEquals("c-1", common.coroutineId)
        assertEquals("worker", common.label)
        assertEquals(common.stateA, common.stateB)
        assertEquals(common.eventCountA, common.eventCountB)
    }

    @Test
    fun `compare sessions with different coroutine counts`() {
        val sessionA = createSession("session-a")
        val sessionB = createSession("session-b")

        // Session A: 1 coroutine
        sessionA.send(createdEvent("session-a", 1, "c-1", "alpha"))
        sessionA.send(startedEvent("session-a", 2, "c-1", "alpha"))

        // Session B: 3 coroutines
        sessionB.send(createdEvent("session-b", 1, "c-1", "alpha"))
        sessionB.send(startedEvent("session-b", 2, "c-1", "alpha"))
        sessionB.send(createdEvent("session-b", 3, "c-2", "beta"))
        sessionB.send(startedEvent("session-b", 4, "c-2", "beta"))
        sessionB.send(createdEvent("session-b", 5, "c-3", "gamma"))

        val result = ComparisonService.compare(sessionA, sessionB)

        assertEquals(2, result.coroutineCountDiff) // B(3) - A(1)
        assertEquals(3, result.eventCountDiff) // B(5) - A(2)
        assertTrue(result.coroutinesOnlyInA.isEmpty())
        assertEquals(listOf("c-2", "c-3"), result.coroutinesOnlyInB)
        assertEquals(1, result.commonCoroutines.size)

        val common = result.commonCoroutines.first()
        assertEquals("c-1", common.coroutineId)
        assertEquals("alpha", common.label)
        assertEquals("ACTIVE", common.stateA)
        assertEquals("ACTIVE", common.stateB)
        assertEquals(2, common.eventCountA)
        assertEquals(2, common.eventCountB)
    }

    @Test
    fun `compare sessions with coroutines only in one`() {
        val sessionA = createSession("session-a")
        val sessionB = createSession("session-b")

        // Session A: coroutines a-1 and a-2
        sessionA.send(createdEvent("session-a", 1, "a-1", "only-in-a-1"))
        sessionA.send(createdEvent("session-a", 2, "a-2", "only-in-a-2"))

        // Session B: coroutines b-1 and b-2
        sessionB.send(createdEvent("session-b", 1, "b-1", "only-in-b-1"))
        sessionB.send(createdEvent("session-b", 2, "b-2", "only-in-b-2"))

        val result = ComparisonService.compare(sessionA, sessionB)

        assertEquals(0, result.coroutineCountDiff) // Both have 2
        assertEquals(0, result.eventCountDiff) // Both have 2 events
        assertEquals(listOf("a-1", "a-2"), result.coroutinesOnlyInA)
        assertEquals(listOf("b-1", "b-2"), result.coroutinesOnlyInB)
        assertTrue(result.commonCoroutines.isEmpty())
    }

    @Test
    fun `compare empty sessions`() {
        val sessionA = createSession("session-a")
        val sessionB = createSession("session-b")

        val result = ComparisonService.compare(sessionA, sessionB)

        assertEquals(0, result.coroutineCountDiff)
        assertEquals(0, result.eventCountDiff)
        assertEquals(0L, result.totalDurationDiffNanos)
        assertTrue(result.coroutinesOnlyInA.isEmpty())
        assertTrue(result.coroutinesOnlyInB.isEmpty())
        assertTrue(result.commonCoroutines.isEmpty())
    }

    @Test
    fun `compare sessions with mixed common and unique coroutines`() {
        val sessionA = createSession("session-a")
        val sessionB = createSession("session-b")

        // Shared coroutine c-shared in both, with different states
        sessionA.send(createdEvent("session-a", 1, "c-shared", "shared"))
        sessionA.send(startedEvent("session-a", 2, "c-shared", "shared"))

        sessionB.send(createdEvent("session-b", 1, "c-shared", "shared"))
        sessionB.send(startedEvent("session-b", 2, "c-shared", "shared"))
        sessionB.send(completedEvent("session-b", 3, "c-shared", "shared"))

        // Unique coroutines
        sessionA.send(createdEvent("session-a", 3, "c-only-a"))
        sessionB.send(createdEvent("session-b", 4, "c-only-b"))

        val result = ComparisonService.compare(sessionA, sessionB)

        assertEquals(0, result.coroutineCountDiff) // A has 2, B has 2
        assertEquals(1, result.eventCountDiff) // A has 3, B has 4
        assertEquals(listOf("c-only-a"), result.coroutinesOnlyInA)
        assertEquals(listOf("c-only-b"), result.coroutinesOnlyInB)
        assertEquals(1, result.commonCoroutines.size)

        val common = result.commonCoroutines.first()
        assertEquals("c-shared", common.coroutineId)
        assertEquals("shared", common.label)
        assertEquals("ACTIVE", common.stateA)
        assertEquals("COMPLETED", common.stateB)
        assertEquals(2, common.eventCountA)
        assertEquals(3, common.eventCountB)
    }

    private fun threadAssignedEvent(
        sessionId: String,
        seq: Long,
        coroutineId: String,
        threadId: Long,
        threadName: String = "thread-$threadId",
        tsNanos: Long = System.nanoTime(),
    ) = ThreadAssigned(
        sessionId = sessionId,
        seq = seq,
        tsNanos = tsNanos,
        coroutineId = coroutineId,
        jobId = "job-$coroutineId",
        parentCoroutineId = null,
        scopeId = "scope-1",
        label = null,
        threadId = threadId,
        threadName = threadName,
        dispatcherName = "Default",
    )

    @Test
    fun `distinctThreadsDiff is the difference in distinct thread count B minus A`() {
        val sessionA = createSession("session-a")
        val sessionB = createSession("session-b")

        // Session A: 1 distinct thread (thread 10)
        sessionA.send(createdEvent("session-a", 1, "c-1"))
        sessionA.send(threadAssignedEvent("session-a", 2, "c-1", threadId = 10))

        // Session B: 3 distinct threads (10, 11, 12)
        sessionB.send(createdEvent("session-b", 1, "c-1"))
        sessionB.send(threadAssignedEvent("session-b", 2, "c-1", threadId = 10))
        sessionB.send(threadAssignedEvent("session-b", 3, "c-2", threadId = 11))
        sessionB.send(threadAssignedEvent("session-b", 4, "c-3", threadId = 12))

        val result = ComparisonService.compare(sessionA, sessionB)

        assertEquals(2, result.distinctThreadsDiff) // B(3) - A(1)
    }

    @Test
    fun `distinctThreadsDiff is zero when both sessions use the same single thread`() {
        val sessionA = createSession("session-a")
        val sessionB = createSession("session-b")

        // Both sessions run everything on one thread (id 7), even with multiple assignments
        sessionA.send(threadAssignedEvent("session-a", 1, "c-1", threadId = 7))
        sessionA.send(threadAssignedEvent("session-a", 2, "c-2", threadId = 7))

        sessionB.send(threadAssignedEvent("session-b", 1, "c-1", threadId = 7))
        sessionB.send(threadAssignedEvent("session-b", 2, "c-2", threadId = 7))
        sessionB.send(threadAssignedEvent("session-b", 3, "c-3", threadId = 7))

        val result = ComparisonService.compare(sessionA, sessionB)

        assertEquals(0, result.distinctThreadsDiff)
    }

    @Test
    fun `distinctThreadsDiff does not perturb the existing diff fields`() {
        val sessionA = createSession("session-a")
        val sessionB = createSession("session-b")

        sessionA.send(createdEvent("session-a", 1, "c-1", "alpha"))
        sessionA.send(threadAssignedEvent("session-a", 2, "c-1", threadId = 10))

        sessionB.send(createdEvent("session-b", 1, "c-1", "alpha"))
        sessionB.send(threadAssignedEvent("session-b", 2, "c-1", threadId = 10))
        sessionB.send(createdEvent("session-b", 3, "c-2", "beta"))
        sessionB.send(threadAssignedEvent("session-b", 4, "c-2", threadId = 11))

        val result = ComparisonService.compare(sessionA, sessionB)

        // Existing aggregate fields remain correct alongside the new thread metric
        assertEquals(1, result.coroutineCountDiff) // B(2) - A(1)
        assertEquals(2, result.eventCountDiff) // B(4) - A(2)
        assertEquals(listOf("c-2"), result.coroutinesOnlyInB)
        assertTrue(result.coroutinesOnlyInA.isEmpty())
        assertEquals(1, result.commonCoroutines.size)
        assertEquals(1, result.distinctThreadsDiff) // B(2 threads) - A(1 thread)
    }

    @Test
    fun `duration diff is computed from event timestamps`() {
        val baseTime = 1_000_000_000L

        val sessionA = createSession("session-a")
        val sessionB = createSession("session-b")

        // Session A spans 100ns
        sessionA.send(createdEvent("session-a", 1, "c-1", tsNanos = baseTime))
        sessionA.send(startedEvent("session-a", 2, "c-1", tsNanos = baseTime + 100))

        // Session B spans 500ns
        sessionB.send(createdEvent("session-b", 1, "c-1", tsNanos = baseTime))
        sessionB.send(startedEvent("session-b", 2, "c-1", tsNanos = baseTime + 500))

        val result = ComparisonService.compare(sessionA, sessionB)

        assertEquals(400L, result.totalDurationDiffNanos) // 500 - 100
    }
}
