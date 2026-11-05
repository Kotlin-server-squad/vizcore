package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.SemaphorePermitAcquired
import com.jh.proj.coroutineviz.events.SemaphorePermitReleased
import com.jh.proj.coroutineviz.events.SemaphoreStateChanged
import com.jh.proj.coroutineviz.events.WaitKind
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** S-7 for VizSemaphore: recorded waits, permits counted per holder, and releases whose holder is unknown. */
@Timeout(30)
class SemaphoreWaitTest {
    private val session = VizSession("semaphore-wait")
    private val viz = VizScope(session)
    private val release = CompletableDeferred<Unit>()

    @AfterEach
    fun close() {
        release.complete(Unit)
        session.close()
    }

    private fun states() = session.store.all().filterIsInstance<SemaphoreStateChanged>()

    private fun releases() = session.store.all().filterIsInstance<SemaphorePermitReleased>()

    /** Starts "holder", which keeps a permit of [semaphore] until [release] completes; returns once it holds it. */
    private suspend fun holding(semaphore: VizSemaphore): Job =
        viz.vizLaunch("holder") { semaphore.withPermit { release.await() } }.also {
            session.awaitEvent { e -> e is SemaphorePermitAcquired && e.acquirerLabel == "holder" }
        }

    @Test
    fun `a free permit records no suspension`() =
        runBlocking {
            val semaphore = VizSemaphore(session, permits = 1, label = "gateway")
            viz.vizLaunch("solo") { semaphore.withPermit { } }.join()

            assertEquals(emptyList(), session.suspensionKindsOf("solo"))
        }

    @Test
    fun `waiting for a permit is a suspension naming the semaphore, then a resume`() =
        runBlocking {
            val semaphore = VizSemaphore(session, permits = 1, label = "gateway")
            val holder = holding(semaphore)
            val waiter = viz.vizLaunch("waiter") { semaphore.withPermit { } }
            val suspended = session.awaitSuspension("waiter")

            assertEquals(WaitKind.SEMAPHORE, suspended.waitKind)
            assertEquals(semaphore.semaphoreId, suspended.resourceId)
            assertEquals("semaphore", suspended.reason)
            assertEquals("SemaphoreWaitTest.kt", suspended.suspensionPoint?.fileName)

            release.complete(Unit)
            joinAll(holder, waiter)
            assertEquals(listOf("CoroutineSuspended", "CoroutineResumed"), session.suspensionKindsOf("waiter"))
            assertEquals(0, semaphore.getWaitQueueSize())
        }

    @Test
    fun `a waiter cancelled in the queue leaves it and records no resume`() =
        runBlocking {
            val semaphore = VizSemaphore(session, permits = 1, label = "gateway")
            holding(semaphore)
            val doomed = viz.vizLaunch("doomed") { semaphore.withPermit { } }
            session.awaitSuspension("doomed")

            doomed.cancelAndJoin()
            session.awaitEvent { it is CoroutineCancelled && it.label == "doomed" }

            assertEquals(listOf("CoroutineSuspended"), session.suspensionKindsOf("doomed"))
            assertEquals(0, semaphore.getWaitQueueSize())
            assertEquals(emptyList(), states().last().waitingCoroutines)
        }

    @Test
    fun `a wait ended by a timeout the coroutine survives records the resume and leaves the queue`() =
        runBlocking {
            val semaphore = VizSemaphore(session, permits = 1, label = "gateway")
            val virtual = VirtualScope(session)
            virtual.scope.vizLaunch("holder") { semaphore.withPermit { release.await() } }
            val impatient = virtual.scope.vizLaunch("impatient") { assertNull(withTimeoutOrNull(100) { semaphore.withPermit { } }) }
            // Virtual time: the holder takes the permit and "impatient" starts waiting before any timeout can fire.
            virtual.runCurrent()
            assertEquals(listOf("CoroutineSuspended"), session.suspensionKindsOf("impatient"))

            virtual.advancePast(100)

            assertEquals(listOf("CoroutineSuspended", "CoroutineResumed"), session.suspensionKindsOf("impatient"))
            assertTrue(impatient.isCompleted && !impatient.isCancelled, "impatient went on after the timeout")
            assertEquals(0, semaphore.getWaitQueueSize())
        }

    @Test
    fun `workers handed permits in a loop record balanced waits`() =
        runBlocking {
            val semaphore = VizSemaphore(session, permits = 2, label = "pool")
            val holders = (1..2).map { n -> viz.vizLaunch("holder-$n") { semaphore.withPermit { release.await() } } }
            session.awaitEvent { it is SemaphoreStateChanged && it.availablePermits == 0 }
            val workers = (1..5).map { n -> viz.vizLaunch("worker-$n") { repeat(30) { semaphore.withPermit { yield() } } } }
            // Every worker has queued behind the two holders before anyone gets a permit: contention is certain.
            for (n in 1..5) session.awaitSuspension("worker-$n")
            release.complete(Unit)
            withTimeout(20.seconds) { (workers + holders).joinAll() }

            for (n in 1..5) {
                assertTrue(session.waitsAreBalanced("worker-$n"), "worker-$n: ${session.suspensionKindsOf("worker-$n")}")
            }
            // Live state once everyone is done, not a snapshot event (their order under handoff is not guaranteed).
            assertEquals(2, semaphore.availablePermits)
            assertEquals(0, semaphore.getWaitQueueSize())
            assertEquals(0, semaphore.getActiveHolderCount())
        }

    @Test
    fun `a coroutine holding two permits is listed once, with both`() =
        runBlocking {
            val semaphore = VizSemaphore(session, permits = 3, label = "pool")
            viz.vizLaunch("double") {
                semaphore.acquire()
                semaphore.acquire()
                semaphore.release()
                semaphore.release()
            }.join()

            assertEquals(listOf(listOf(1), listOf(2), listOf(1), emptyList()), states().map { it.activeHolderPermits })
            assertEquals(listOf(listOf("double"), listOf("double"), listOf("double"), emptyList()), states().map { it.activeHolderLabels })
            assertEquals(listOf(false, false), releases().map { it.holderUnknown })
            assertTrue(releases().all { it.holdDurationNanos > 0 })
        }

    @Test
    fun `a permit released by a coroutine that holds none has an unknown holder`() =
        runBlocking {
            val semaphore = VizSemaphore(session, permits = 3, label = "pool")
            val owner =
                viz.vizLaunch("owner") {
                    semaphore.acquire()
                    semaphore.acquire()
                    release.await()
                    semaphore.release()
                }
            session.awaitEvent { it is SemaphoreStateChanged && it.activeHolderPermits == listOf(2) }
            viz.vizLaunch("stranger") { semaphore.release() }.join()

            val byStranger = releases().single()
            assertEquals(session.idOf("stranger"), byStranger.releaserId)
            assertTrue(byStranger.holderUnknown)
            assertEquals(0L, byStranger.holdDurationNanos)
            val afterStranger = states().last()
            assertEquals(listOf(session.idOf("owner")), afterStranger.activeHolders)
            // One permit is still in use, so the owner is listed with no more than that.
            assertEquals(listOf(1), afterStranger.activeHolderPermits)
            assertEquals(0, afterStranger.unknownHolderPermits)

            release.complete(Unit)
            owner.join()
            assertFalse(releases().last().holderUnknown)
            val idle = states().last()
            assertEquals(3, idle.availablePermits)
            assertEquals(emptyList(), idle.activeHolders, "with every permit free, nobody holds one")
            assertEquals(0, idle.unknownHolderPermits)
        }

    @Test
    fun `a release outside any coroutine has an unknown holder`() =
        runBlocking {
            val semaphore = VizSemaphore(session, permits = 2, label = "slots", acquiredPermits = 2)
            semaphore.release()

            val released = releases().single()
            assertEquals("unknown", released.releaserId)
            assertTrue(released.holderUnknown)
        }

    @Test
    fun `permits released by another coroutine do not pile up while the semaphore is never free`() =
        runBlocking {
            val semaphore = VizSemaphore(session, permits = 2, label = "slots")
            val keeper = viz.vizLaunch("keeper") { semaphore.withPermit { release.await() } }
            session.awaitEvent { it is SemaphorePermitAcquired && it.acquirerLabel == "keeper" }
            // The producer acquires, the consumer releases: every release by the consumer has an unknown holder.
            val handoff = Channel<Unit>()
            val producer =
                viz.vizLaunch("producer") {
                    repeat(50) {
                        semaphore.acquire()
                        handoff.send(Unit)
                    }
                }
            val consumer =
                viz.vizLaunch("consumer") {
                    repeat(50) {
                        handoff.receive()
                        semaphore.release()
                    }
                }
            withTimeout(20.seconds) { joinAll(producer, consumer) }

            // One permit is in use (the keeper's): nobody can be listed with more than that.
            val busy = states().last()
            assertEquals(1, 2 - busy.availablePermits)
            assertTrue(busy.activeHolderPermits.all { it <= 1 }, "${busy.activeHolderLabels} ${busy.activeHolderPermits}")

            release.complete(Unit)
            withTimeout(10.seconds) { keeper.join() }
            assertEquals(emptyList(), states().last().activeHolders, "with every permit free, nobody holds one")
        }
}
