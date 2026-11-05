package com.jh.proj.coroutineviz.checksystem

import com.jh.proj.coroutineviz.events.DeadlockDetected
import com.jh.proj.coroutineviz.events.MutexLockAcquired
import com.jh.proj.coroutineviz.events.MutexLockRequested
import com.jh.proj.coroutineviz.events.MutexUnlocked
import com.jh.proj.coroutineviz.events.SemaphorePermitAcquired
import com.jh.proj.coroutineviz.events.SemaphorePermitReleased
import com.jh.proj.coroutineviz.events.SemaphoreStateChanged
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyncValidatorsTest {
    // ========================================================================
    // Helper factories
    // ========================================================================

    private var seqCounter = 0L

    private fun nextSeq(): Long = ++seqCounter

    private fun deadlockDetected(
        involvedCoroutines: List<String> = listOf("c-1", "c-2"),
        involvedMutexes: List<String> = listOf("m-1", "m-2"),
        cycleDescription: String = "c-1 -> m-1 -> c-2 -> m-2 -> c-1",
    ): DeadlockDetected {
        val seq = nextSeq()
        return DeadlockDetected(
            sessionId = "test-session",
            seq = seq,
            tsNanos = seq * 1_000_000,
            involvedCoroutines = involvedCoroutines,
            involvedCoroutineLabels = involvedCoroutines.map { null },
            involvedMutexes = involvedMutexes,
            involvedMutexLabels = involvedMutexes.map { null },
            waitGraph = involvedCoroutines.zip(involvedMutexes).toMap(),
            holdGraph = involvedMutexes.zip(involvedCoroutines).toMap(),
            cycleDescription = cycleDescription,
        )
    }

    private fun mutexLockRequested(
        mutexId: String,
        requesterId: String,
        isLocked: Boolean = false,
        queuePosition: Int = 0,
    ): MutexLockRequested {
        val seq = nextSeq()
        return MutexLockRequested(
            sessionId = "test-session",
            seq = seq,
            tsNanos = seq * 1_000_000,
            mutexId = mutexId,
            mutexLabel = null,
            requesterId = requesterId,
            requesterLabel = null,
            isLocked = isLocked,
            queuePosition = queuePosition,
        )
    }

    private fun mutexLockAcquired(
        mutexId: String,
        acquirerId: String,
        waitDurationNanos: Long = 0,
    ): MutexLockAcquired {
        val seq = nextSeq()
        return MutexLockAcquired(
            sessionId = "test-session",
            seq = seq,
            tsNanos = seq * 1_000_000,
            mutexId = mutexId,
            mutexLabel = null,
            acquirerId = acquirerId,
            acquirerLabel = null,
            waitDurationNanos = waitDurationNanos,
        )
    }

    private fun mutexUnlocked(
        mutexId: String,
        releaserId: String,
        nextWaiterId: String? = null,
        holdDurationNanos: Long = 1_000_000,
    ): MutexUnlocked {
        val seq = nextSeq()
        return MutexUnlocked(
            sessionId = "test-session",
            seq = seq,
            tsNanos = seq * 1_000_000,
            mutexId = mutexId,
            mutexLabel = null,
            releaserId = releaserId,
            releaserLabel = null,
            nextWaiterId = nextWaiterId,
            holdDurationNanos = holdDurationNanos,
        )
    }

    private fun semaphorePermitAcquired(
        semaphoreId: String,
        acquirerId: String,
        remainingPermits: Int = 0,
        waitDurationNanos: Long = 0,
    ): SemaphorePermitAcquired {
        val seq = nextSeq()
        return SemaphorePermitAcquired(
            sessionId = "test-session",
            seq = seq,
            tsNanos = seq * 1_000_000,
            semaphoreId = semaphoreId,
            semaphoreLabel = null,
            acquirerId = acquirerId,
            acquirerLabel = null,
            remainingPermits = remainingPermits,
            waitDurationNanos = waitDurationNanos,
        )
    }

    private fun semaphorePermitReleased(
        semaphoreId: String,
        releaserId: String,
        newAvailablePermits: Int = 1,
        holdDurationNanos: Long = 1_000_000,
    ): SemaphorePermitReleased {
        val seq = nextSeq()
        return SemaphorePermitReleased(
            sessionId = "test-session",
            seq = seq,
            tsNanos = seq * 1_000_000,
            semaphoreId = semaphoreId,
            semaphoreLabel = null,
            releaserId = releaserId,
            releaserLabel = null,
            newAvailablePermits = newAvailablePermits,
            holdDurationNanos = holdDurationNanos,
        )
    }

    private fun semaphoreStateChanged(
        semaphoreId: String,
        availablePermits: Int,
        totalPermits: Int,
        activeHolders: List<String> = emptyList(),
        waitingCoroutines: List<String> = emptyList(),
        activeHolderPermits: List<Int> = emptyList(),
        unknownHolderPermits: Int = 0,
    ): SemaphoreStateChanged {
        val seq = nextSeq()
        return SemaphoreStateChanged(
            sessionId = "test-session",
            seq = seq,
            tsNanos = seq * 1_000_000,
            semaphoreId = semaphoreId,
            semaphoreLabel = null,
            availablePermits = availablePermits,
            totalPermits = totalPermits,
            activeHolders = activeHolders,
            activeHolderLabels = activeHolders.map { null },
            waitingCoroutines = waitingCoroutines,
            waitingLabels = waitingCoroutines.map { null },
            activeHolderPermits = activeHolderPermits,
            unknownHolderPermits = unknownHolderPermits,
        )
    }

    // ========================================================================
    // MutexValidator tests
    // ========================================================================

    @Test
    fun `verifyNoDeadlock passes with no deadlock events`() {
        val recorder = EventRecorder()
        // Record some normal mutex events but no deadlocks
        recorder.record(mutexLockAcquired("m-1", "c-1"))
        recorder.record(mutexUnlocked("m-1", "c-1"))

        val validator = MutexValidator(recorder)
        validator.verifyNoDeadlock() // Should not throw
    }

    @Test
    fun `verifyNoDeadlock fails when deadlock detected`() {
        val recorder = EventRecorder()
        recorder.record(
            deadlockDetected(
                involvedCoroutines = listOf("c-1", "c-2"),
                involvedMutexes = listOf("m-1", "m-2"),
                cycleDescription = "c-1 -> m-1 -> c-2 -> m-2 -> c-1",
            ),
        )

        val validator = MutexValidator(recorder)
        val error =
            assertFailsWith<AssertionError> {
                validator.verifyNoDeadlock()
            }
        assertTrue(error.message!!.contains("Deadlock detected"))
        assertTrue(error.message!!.contains("c-1"))
        assertTrue(error.message!!.contains("c-2"))
    }

    @Test
    fun `verifyMutualExclusion passes with proper lock-unlock sequence`() {
        val recorder = EventRecorder()
        val mutexId = "m-1"
        // A acquires, A unlocks, B acquires, B unlocks
        recorder.record(mutexLockAcquired(mutexId, "c-A"))
        recorder.record(mutexUnlocked(mutexId, "c-A"))
        recorder.record(mutexLockAcquired(mutexId, "c-B"))
        recorder.record(mutexUnlocked(mutexId, "c-B"))

        val validator = MutexValidator(recorder)
        validator.verifyMutualExclusion(mutexId) // Should not throw
    }

    @Test
    fun `verifyMutualExclusion fails with double acquire`() {
        val recorder = EventRecorder()
        val mutexId = "m-1"
        // A acquires, then B acquires without A unlocking
        recorder.record(mutexLockAcquired(mutexId, "c-A"))
        recorder.record(mutexLockAcquired(mutexId, "c-B"))

        val validator = MutexValidator(recorder)
        val error =
            assertFailsWith<AssertionError> {
                validator.verifyMutualExclusion(mutexId)
            }
        assertTrue(error.message!!.contains("c-A"))
        assertTrue(error.message!!.contains("c-B"))
    }

    @Test
    fun `verifyMutualExclusion fails with wrong releaser`() {
        val recorder = EventRecorder()
        val mutexId = "m-1"
        // A acquires, B unlocks (wrong releaser)
        recorder.record(mutexLockAcquired(mutexId, "c-A"))
        recorder.record(mutexUnlocked(mutexId, "c-B"))

        val validator = MutexValidator(recorder)
        val error =
            assertFailsWith<AssertionError> {
                validator.verifyMutualExclusion(mutexId)
            }
        assertTrue(error.message!!.contains("c-B"))
        assertTrue(error.message!!.contains("c-A"))
    }

    @Test
    fun `verifyFairness passes with FIFO ordering`() {
        val recorder = EventRecorder()
        val mutexId = "m-1"
        // A and B queue (both contended), then A acquires first, then B
        recorder.record(mutexLockRequested(mutexId, "c-A", isLocked = true, queuePosition = 1))
        recorder.record(mutexLockRequested(mutexId, "c-B", isLocked = true, queuePosition = 2))
        recorder.record(mutexLockAcquired(mutexId, "c-A", waitDurationNanos = 5_000))
        recorder.record(mutexLockAcquired(mutexId, "c-B", waitDurationNanos = 10_000))

        val validator = MutexValidator(recorder)
        validator.verifyFairness(mutexId) // Should not throw
    }

    @Test
    fun `verifyNoLockLeaks passes with balanced operations`() {
        val recorder = EventRecorder()
        val mutexId = "m-1"
        recorder.record(mutexLockAcquired(mutexId, "c-A"))
        recorder.record(mutexUnlocked(mutexId, "c-A"))
        recorder.record(mutexLockAcquired(mutexId, "c-B"))
        recorder.record(mutexUnlocked(mutexId, "c-B"))

        val validator = MutexValidator(recorder)
        validator.verifyNoLockLeaks(mutexId) // Should not throw
    }

    @Test
    fun `verifyNoLockLeaks fails with unbalanced operations`() {
        val recorder = EventRecorder()
        val mutexId = "m-1"
        // Two acquires but only one unlock
        recorder.record(mutexLockAcquired(mutexId, "c-A"))
        recorder.record(mutexUnlocked(mutexId, "c-A"))
        recorder.record(mutexLockAcquired(mutexId, "c-B"))
        // c-B never unlocks

        val validator = MutexValidator(recorder)
        val error =
            assertFailsWith<AssertionError> {
                validator.verifyNoLockLeaks(mutexId)
            }
        assertTrue(error.message!!.contains("lock leak"))
        assertTrue(error.message!!.contains("2 acquires"))
        assertTrue(error.message!!.contains("1 releases"))
    }

    @Test
    fun `getContentionStats returns correct statistics`() {
        val recorder = EventRecorder()
        val mutexId = "m-1"

        // Request 1: uncontended (isLocked = false)
        recorder.record(mutexLockRequested(mutexId, "c-A", isLocked = false, queuePosition = 0))
        recorder.record(mutexLockAcquired(mutexId, "c-A", waitDurationNanos = 0))
        recorder.record(mutexUnlocked(mutexId, "c-A", holdDurationNanos = 5_000_000))

        // Request 2: contended (isLocked = true)
        recorder.record(mutexLockRequested(mutexId, "c-B", isLocked = true, queuePosition = 1))
        recorder.record(mutexLockAcquired(mutexId, "c-B", waitDurationNanos = 2_000_000))
        recorder.record(mutexUnlocked(mutexId, "c-B", holdDurationNanos = 3_000_000))

        // Request 3: contended (isLocked = true)
        recorder.record(mutexLockRequested(mutexId, "c-C", isLocked = true, queuePosition = 1))
        recorder.record(mutexLockAcquired(mutexId, "c-C", waitDurationNanos = 4_000_000))
        recorder.record(mutexUnlocked(mutexId, "c-C", holdDurationNanos = 1_000_000))

        val validator = MutexValidator(recorder)
        val stats = validator.getContentionStats(mutexId)

        assertEquals(3, stats.totalRequests)
        assertEquals(2, stats.contentionRequests)
        assertEquals(2.0 / 3.0, stats.contentionRate, 0.001)
        assertEquals(2_000_000.0, stats.avgWaitTimeNanos, 0.001)
        assertEquals(4_000_000L, stats.maxWaitTimeNanos)
        assertEquals(3_000_000.0, stats.avgHoldTimeNanos, 0.001)
        assertEquals(5_000_000L, stats.maxHoldTimeNanos)
    }

    // ========================================================================
    // SemaphoreValidator tests
    // ========================================================================

    @Test
    fun `verifyPermitBounds passes with valid state`() {
        val recorder = EventRecorder()
        val semId = "s-1"
        val maxPermits = 3

        // State with 2 active holders out of 3 permits -- valid
        recorder.record(
            semaphoreStateChanged(
                semId,
                availablePermits = 1,
                totalPermits = maxPermits,
                activeHolders = listOf("c-A", "c-B"),
            ),
        )
        // State with 3 active holders out of 3 permits -- still valid
        recorder.record(
            semaphoreStateChanged(
                semId,
                availablePermits = 0,
                totalPermits = maxPermits,
                activeHolders = listOf("c-A", "c-B", "c-C"),
            ),
        )

        val validator = SemaphoreValidator(recorder)
        validator.verifyPermitBounds(semId, maxPermits) // Should not throw
    }

    @Test
    fun `verifyPermitBounds fails with too many holders`() {
        val recorder = EventRecorder()
        val semId = "s-1"
        val maxPermits = 2

        // 3 active holders but only 2 permits
        recorder.record(
            semaphoreStateChanged(
                semId,
                availablePermits = 0,
                totalPermits = maxPermits,
                activeHolders = listOf("c-A", "c-B", "c-C"),
            ),
        )

        val validator = SemaphoreValidator(recorder)
        val error =
            assertFailsWith<AssertionError> {
                validator.verifyPermitBounds(semId, maxPermits)
            }
        assertTrue(error.message!!.contains("permit limit exceeded"))
        assertTrue(error.message!!.contains("3 holders"))
        assertTrue(error.message!!.contains("2 permits"))
    }

    @Test
    fun `verifyPermitBounds counts permits in use when holders carry counts`() {
        val recorder = EventRecorder()
        // A and B acquired, a coroutine holding none released one, C acquired: 3 listed, 2 in use.
        recorder.record(
            semaphoreStateChanged(
                "s-1",
                availablePermits = 0,
                totalPermits = 2,
                activeHolders = listOf("c-A", "c-B", "c-C"),
                activeHolderPermits = listOf(1, 1, 1),
                unknownHolderPermits = 1,
            ),
        )

        SemaphoreValidator(recorder).verifyPermitBounds("s-1", 2) // Should not throw
    }

    @Test
    fun `verifyPermitBounds fails when counted permits exceed the limit`() {
        val recorder = EventRecorder()
        recorder.record(
            semaphoreStateChanged(
                "s-1",
                availablePermits = 0,
                totalPermits = 2,
                activeHolders = listOf("c-A"),
                activeHolderPermits = listOf(3),
            ),
        )

        val error = assertFailsWith<AssertionError> { SemaphoreValidator(recorder).verifyPermitBounds("s-1", 2) }
        assertTrue(error.message!!.contains("3 permits held"))
    }

    @Test
    fun `verifyPermitBounds fails with negative permits`() {
        val recorder = EventRecorder()
        val semId = "s-1"

        recorder.record(
            semaphoreStateChanged(
                semId,
                availablePermits = -1,
                totalPermits = 3,
                activeHolders = listOf("c-A", "c-B", "c-C"),
            ),
        )

        val validator = SemaphoreValidator(recorder)
        val error =
            assertFailsWith<AssertionError> {
                validator.verifyPermitBounds(semId, 3)
            }
        assertTrue(error.message!!.contains("negative permits"))
    }

    @Test
    fun `verifyNoPermitLeaks passes when all permits returned`() {
        val recorder = EventRecorder()
        val semId = "s-1"
        val totalPermits = 3

        // Some intermediate states then final state with all permits available
        recorder.record(
            semaphoreStateChanged(semId, availablePermits = 2, totalPermits = totalPermits, activeHolders = listOf("c-A")),
        )
        recorder.record(
            semaphoreStateChanged(semId, availablePermits = 1, totalPermits = totalPermits, activeHolders = listOf("c-A", "c-B")),
        )
        recorder.record(
            semaphoreStateChanged(semId, availablePermits = totalPermits, totalPermits = totalPermits, activeHolders = emptyList()),
        )

        val validator = SemaphoreValidator(recorder)
        validator.verifyNoPermitLeaks(semId, totalPermits) // Should not throw
    }

    @Test
    fun `verifyNoPermitLeaks fails when permits not returned`() {
        val recorder = EventRecorder()
        val semId = "s-1"
        val totalPermits = 3

        // Final state still has a holder
        recorder.record(
            semaphoreStateChanged(semId, availablePermits = 2, totalPermits = totalPermits, activeHolders = listOf("c-A")),
        )

        val validator = SemaphoreValidator(recorder)
        val error =
            assertFailsWith<AssertionError> {
                validator.verifyNoPermitLeaks(semId, totalPermits)
            }
        assertTrue(error.message!!.contains("permit leak"))
        assertTrue(error.message!!.contains("2/$totalPermits"))
    }

    @Test
    fun `verifyBalancedOperations passes with equal acquires and releases`() {
        val recorder = EventRecorder()
        val semId = "s-1"

        recorder.record(semaphorePermitAcquired(semId, "c-A"))
        recorder.record(semaphorePermitAcquired(semId, "c-B"))
        recorder.record(semaphorePermitReleased(semId, "c-A"))
        recorder.record(semaphorePermitReleased(semId, "c-B"))

        val validator = SemaphoreValidator(recorder)
        validator.verifyBalancedOperations(semId) // Should not throw
    }

    @Test
    fun `verifyBalancedOperations fails with unbalanced counts`() {
        val recorder = EventRecorder()
        val semId = "s-1"

        recorder.record(semaphorePermitAcquired(semId, "c-A"))
        recorder.record(semaphorePermitAcquired(semId, "c-B"))
        recorder.record(semaphorePermitReleased(semId, "c-A"))
        // c-B never releases

        val validator = SemaphoreValidator(recorder)
        val error =
            assertFailsWith<AssertionError> {
                validator.verifyBalancedOperations(semId)
            }
        assertTrue(error.message!!.contains("unbalanced"))
        assertTrue(error.message!!.contains("2 acquires"))
        assertTrue(error.message!!.contains("1 releases"))
    }

    @Test
    fun `getUtilizationStats returns correct statistics`() {
        val recorder = EventRecorder()
        val semId = "s-1"
        val totalPermits = 3

        // State changes showing varying utilization
        recorder.record(
            semaphoreStateChanged(
                semId,
                availablePermits = 2,
                totalPermits = totalPermits,
                activeHolders = listOf("c-A"),
                waitingCoroutines = emptyList(),
            ),
        )
        recorder.record(
            semaphoreStateChanged(
                semId,
                availablePermits = 0,
                totalPermits = totalPermits,
                activeHolders = listOf("c-A", "c-B", "c-C"),
                waitingCoroutines = listOf("c-D"),
            ),
        )
        recorder.record(
            semaphoreStateChanged(
                semId,
                availablePermits = totalPermits,
                totalPermits = totalPermits,
                activeHolders = emptyList(),
                waitingCoroutines = emptyList(),
            ),
        )

        // Acquire/release events
        recorder.record(semaphorePermitAcquired(semId, "c-A", waitDurationNanos = 1_000_000))
        recorder.record(semaphorePermitAcquired(semId, "c-B", waitDurationNanos = 3_000_000))
        recorder.record(semaphorePermitReleased(semId, "c-A", holdDurationNanos = 5_000_000))
        recorder.record(semaphorePermitReleased(semId, "c-B", holdDurationNanos = 7_000_000))

        val validator = SemaphoreValidator(recorder)
        val stats = validator.getUtilizationStats(semId)

        assertEquals(2, stats.totalAcquires)
        assertEquals(2, stats.totalReleases)

        // Utilization samples: 1/3, 3/3, 0/3 => avg = (1/3 + 1.0 + 0.0) / 3
        val expectedAvgUtil = (1.0 / 3.0 + 1.0 + 0.0) / 3.0
        assertEquals(expectedAvgUtil, stats.avgUtilization, 0.001)
        assertEquals(1.0, stats.maxUtilization, 0.001)

        assertEquals(3, stats.maxConcurrentHolders)
        assertEquals(1, stats.maxWaitingQueue)

        assertEquals(2_000_000.0, stats.avgWaitTimeNanos, 0.001)
        assertEquals(3_000_000L, stats.maxWaitTimeNanos)
        assertEquals(6_000_000.0, stats.avgHoldTimeNanos, 0.001)
        assertEquals(7_000_000L, stats.maxHoldTimeNanos)
    }
}
