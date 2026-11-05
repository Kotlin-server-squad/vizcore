package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.MutexLockAcquired
import com.jh.proj.coroutineviz.events.MutexQueueChanged
import com.jh.proj.coroutineviz.events.WaitKind
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** S-7 for VizMutex: only a lock that is really held makes a coroutine wait, and the wait is a suspension. */
@Timeout(30)
class MutexWaitTest {
    private val session = VizSession("mutex-wait")
    private val viz = VizScope(session)
    private val release = CompletableDeferred<Unit>()

    @AfterEach
    fun close() {
        release.complete(Unit)
        session.close()
    }

    /** Starts "holder", which keeps [mutex] until [release] completes; returns once it holds it. */
    private suspend fun holding(mutex: VizMutex): Job =
        viz.vizLaunch("holder") { mutex.withLock { release.await() } }.also {
            session.awaitEvent { e -> e is MutexLockAcquired && e.acquirerLabel == "holder" }
        }

    @Test
    fun `an uncontended lock records no suspension`() =
        runBlocking {
            val mutex = VizMutex(session, "free-lock")
            viz.vizLaunch("solo") { mutex.withLock { } }.join()

            assertEquals(emptyList(), session.suspensionKindsOf("solo"))
        }

    @Test
    fun `a contended lock is a suspension naming the mutex, then a resume`() =
        runBlocking {
            val mutex = VizMutex(session, "inventory-lock")
            val holder = holding(mutex)
            val waiter = viz.vizLaunch("waiter") { mutex.withLock { } }
            val suspended = session.awaitSuspension("waiter")

            assertEquals(WaitKind.MUTEX, suspended.waitKind)
            assertEquals(mutex.mutexId, suspended.resourceId)
            assertEquals("mutex", suspended.reason)
            assertEquals("MutexWaitTest.kt", suspended.suspensionPoint?.fileName)

            release.complete(Unit)
            withTimeout(10.seconds) { joinAll(holder, waiter) }
            assertEquals(listOf("CoroutineSuspended", "CoroutineResumed"), session.suspensionKindsOf("waiter"))
            assertEquals(0, mutex.getWaitQueueSize())
        }

    @Test
    fun `a waiter cancelled in the queue leaves it and records no resume`() =
        runBlocking {
            val mutex = VizMutex(session, "inventory-lock")
            holding(mutex)
            val doomed = viz.vizLaunch("doomed") { mutex.withLock { } }
            session.awaitSuspension("doomed")

            doomed.cancelAndJoin()
            session.awaitEvent { it is CoroutineCancelled && it.label == "doomed" }

            assertEquals(listOf("CoroutineSuspended"), session.suspensionKindsOf("doomed"))
            assertEquals(0, mutex.getWaitQueueSize())
            // Nothing else touches the mutex meanwhile: the holder still waits for `release`.
            assertEquals(emptyList(), session.store.all().filterIsInstance<MutexQueueChanged>().last().waitingCoroutineIds)
        }

    @Test
    fun `a wait ended by a timeout the coroutine survives records the resume and leaves the queue`() =
        runBlocking {
            val mutex = VizMutex(session, "inventory-lock")
            val virtual = VirtualScope(session)
            virtual.scope.vizLaunch("holder") { mutex.withLock { release.await() } }
            val impatient = virtual.scope.vizLaunch("impatient") { assertNull(withTimeoutOrNull(100) { mutex.withLock { } }) }
            // Virtual time: the holder takes the lock and "impatient" starts waiting before any timeout can fire.
            virtual.runCurrent()
            assertEquals(listOf("CoroutineSuspended"), session.suspensionKindsOf("impatient"))

            virtual.advancePast(100)

            assertEquals(listOf("CoroutineSuspended", "CoroutineResumed"), session.suspensionKindsOf("impatient"))
            assertTrue(impatient.isCompleted && !impatient.isCancelled, "impatient went on after the timeout")
            assertEquals(0, mutex.getWaitQueueSize())
        }

    @Test
    fun `workers handed the lock in a loop record balanced waits`() =
        runBlocking {
            val mutex = VizMutex(session, "hot-lock")
            val holder = holding(mutex)
            val workers = (1..5).map { n -> viz.vizLaunch("worker-$n") { repeat(30) { mutex.withLock { yield() } } } }
            // Every worker has queued behind the holder before anyone gets the lock: contention is certain.
            for (n in 1..5) session.awaitSuspension("worker-$n")
            release.complete(Unit)
            withTimeout(20.seconds) { (workers + holder).joinAll() }

            for (n in 1..5) {
                assertTrue(session.waitsAreBalanced("worker-$n"), "worker-$n: ${session.suspensionKindsOf("worker-$n")}")
            }
            assertEquals(0, mutex.getWaitQueueSize())
            assertTrue(!mutex.isLocked)
        }
}
