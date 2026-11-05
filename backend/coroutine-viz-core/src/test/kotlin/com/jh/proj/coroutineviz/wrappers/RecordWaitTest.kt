package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.SuspensionProvenance
import com.jh.proj.coroutineviz.events.WaitKind
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals

/** The S-7 rules every wrapper relies on: when a wait sends Suspended, and when it sends Resumed. */
@Timeout(30)
class RecordWaitTest {
    private val session = VizSession("record-wait")
    private val viz = VizScope(session)

    @AfterEach
    fun close() = session.close()

    @Test
    fun `outside an instrumented coroutine it only waits`() =
        runBlocking {
            assertEquals(7, session.recordWait(WaitKind.MUTEX, "mutex-1") { 7 })
            assertEquals(emptyList(), session.store.all().filterIsInstance<CoroutineSuspended>())
        }

    @Test
    fun `a wait that returns is a suspension, then a resume`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val waiter = viz.vizLaunch("waiter") { session.recordWait(WaitKind.CHANNEL_RECEIVE, "channel-9") { gate.await() } }
            val suspended = session.awaitSuspension("waiter")

            assertEquals(WaitKind.CHANNEL_RECEIVE, suspended.waitKind)
            assertEquals("channel-9", suspended.resourceId)
            assertEquals("channel-receive", suspended.reason)
            assertEquals(SuspensionProvenance.REAL_FRAME, suspended.provenance)
            assertEquals("RecordWaitTest.kt", suspended.suspensionPoint?.fileName)

            gate.complete(Unit)
            waiter.join()
            assertEquals(listOf("CoroutineSuspended", "CoroutineResumed"), session.suspensionKindsOf("waiter"))
        }

    @Test
    fun `a wait ended by an exception the coroutine survives records the resume`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val survivor =
                viz.vizLaunch("survivor") {
                    runCatching { session.recordWait(WaitKind.CHANNEL_RECEIVE, "channel-9") { gate.await() } }
                }
            session.awaitSuspension("survivor")

            gate.completeExceptionally(IllegalStateException("closed"))
            survivor.join()

            assertEquals(listOf("CoroutineSuspended", "CoroutineResumed"), session.suspensionKindsOf("survivor"))
        }

    @Test
    fun `a wait ended by cancelling the coroutine records no resume`() =
        runBlocking {
            val doomed = viz.vizLaunch("doomed") { session.recordWait(WaitKind.SEMAPHORE, "semaphore-3") { awaitCancellation() } }
            session.awaitSuspension("doomed")

            doomed.cancelAndJoin()
            session.awaitEvent { it is CoroutineCancelled && it.label == "doomed" }

            assertEquals(listOf("CoroutineSuspended"), session.suspensionKindsOf("doomed"))
        }

    @Test
    fun `a wait that returns while its coroutine is being cancelled records no resume`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val doomed =
                viz.vizLaunch("doomed") {
                    session.recordWait(WaitKind.MUTEX, "mutex-1") { withContext(NonCancellable) { gate.await() } }
                }
            session.awaitSuspension("doomed")

            doomed.cancel()
            gate.complete(Unit)
            doomed.join()
            session.awaitEvent { it is CoroutineCancelled && it.label == "doomed" }

            assertEquals(listOf("CoroutineSuspended"), session.suspensionKindsOf("doomed"))
        }
}
