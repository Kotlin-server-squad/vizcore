package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.WaitKind
import com.jh.proj.coroutineviz.events.channel.ChannelReceiveCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** S-7 for channels: send, receive, receiveCatching and iteration record their waits; select is not covered. */
@Timeout(30)
class ChannelWaitTest {
    private val session = VizSession("channel-wait")
    private val viz = VizScope(session)

    @AfterEach
    fun close() = session.close()

    /** Starts [label] doing [operation], which must wait; cancels it while it waits: no resume may be recorded. */
    private suspend fun cancelledWhileWaiting(
        label: String,
        operation: suspend () -> Unit,
    ) {
        val job = viz.vizLaunch(label) { operation() }
        session.awaitSuspension(label)

        job.cancelAndJoin()
        session.awaitEvent { it is CoroutineCancelled && it.label == label }

        assertEquals(listOf("CoroutineSuspended"), session.suspensionKindsOf(label), label)
    }

    /**
     * Runs [operation], which must wait, under a timeout the coroutine survives: the resume must be recorded.
     * Virtual time makes the order certain: the wait starts before the timeout can fire.
     */
    private suspend fun survivesTimeout(
        label: String,
        operation: suspend () -> Unit,
    ) {
        val virtual = VirtualScope(session)
        val job = virtual.scope.vizLaunch(label) { assertNull(withTimeoutOrNull(100) { operation() }) }
        virtual.runCurrent()
        assertEquals(listOf("CoroutineSuspended"), session.suspensionKindsOf(label), label)

        virtual.advancePast(100)

        assertEquals(listOf("CoroutineSuspended", "CoroutineResumed"), session.suspensionKindsOf(label), label)
        assertTrue(job.isCompleted && !job.isCancelled, "$label went on after the timeout")
    }

    @Test
    fun `a send that finds room and a receive that finds an element record no suspension`() =
        runBlocking {
            val tray = viz.vizChannel<Int>(1, "tray")
            viz.vizLaunch("cook") { tray.send(1) }.join()
            viz.vizLaunch("waiter") { tray.receive() }.join()

            assertEquals(emptyList(), session.suspensionKindsOf("cook"))
            assertEquals(emptyList(), session.suspensionKindsOf("waiter"))
        }

    @Test
    fun `a send nobody takes yet is a suspension naming the channel, then a resume`() =
        runBlocking {
            val pass = viz.vizChannel<Int>(Channel.RENDEZVOUS, "pass")
            val cook = viz.vizLaunch("cook") { pass.send(1) }
            val suspended = session.awaitSuspension("cook")

            assertEquals(WaitKind.CHANNEL_SEND, suspended.waitKind)
            assertEquals(pass.channelId, suspended.resourceId)
            assertEquals("channel-send", suspended.reason)
            assertEquals("ChannelWaitTest.kt", suspended.suspensionPoint?.fileName)

            viz.vizLaunch("waiter") { pass.receive() }.join()
            cook.join()
            assertEquals(listOf("CoroutineSuspended", "CoroutineResumed"), session.suspensionKindsOf("cook"))
        }

    @Test
    fun `a receive on an empty channel is a suspension, then a resume`() =
        runBlocking {
            val pass = viz.vizChannel<Int>(Channel.RENDEZVOUS, "pass")
            val waiter = viz.vizLaunch("waiter") { pass.receive() }
            val suspended = session.awaitSuspension("waiter")

            assertEquals(WaitKind.CHANNEL_RECEIVE, suspended.waitKind)
            assertEquals(pass.channelId, suspended.resourceId)

            viz.vizLaunch("cook") { pass.send(1) }.join()
            waiter.join()
            assertEquals(listOf("CoroutineSuspended", "CoroutineResumed"), session.suspensionKindsOf("waiter"))
        }

    @Test
    fun `receiveCatching waits, and closing the channel ends the wait`() =
        runBlocking {
            val pass = viz.vizChannel<Int>(Channel.RENDEZVOUS, "pass")
            val closed = CompletableDeferred<Boolean>()
            val waiter = viz.vizLaunch("waiter") { closed.complete(pass.receiveCatching().isClosed) }
            val suspended = session.awaitSuspension("waiter")
            assertEquals(WaitKind.CHANNEL_RECEIVE, suspended.waitKind)
            assertEquals(pass.channelId, suspended.resourceId)

            pass.close()
            waiter.join()

            assertTrue(closed.await())
            assertEquals(listOf("CoroutineSuspended", "CoroutineResumed"), session.suspensionKindsOf("waiter"))
        }

    @Test
    fun `iterating waits for each element and names the receiver`() =
        runBlocking {
            val pass = viz.vizChannel<Int>(Channel.RENDEZVOUS, "pass")
            val received = mutableListOf<Int>()
            val waiter = viz.vizLaunch("waiter") { for (plate in pass) received += plate }
            val suspended = session.awaitSuspension("waiter")
            assertEquals(WaitKind.CHANNEL_RECEIVE, suspended.waitKind)
            assertEquals(pass.channelId, suspended.resourceId)

            pass.send(1)
            pass.send(2)
            pass.close()
            withTimeout(10.seconds) { waiter.join() }

            assertEquals(listOf(1, 2), received)
            assertTrue(session.waitsAreBalanced("waiter"), "${session.suspensionKindsOf("waiter")}")
            assertEquals(
                setOf(session.idOf("waiter")),
                session.store.all().filterIsInstance<ChannelReceiveCompleted>().map { it.coroutineId }.toSet(),
            )
        }

    @Test
    fun `a receive ended by closing the channel, caught by the coroutine, records the resume`() =
        runBlocking {
            val pass = viz.vizChannel<Int>(Channel.RENDEZVOUS, "pass")
            val waiter =
                viz.vizLaunch("waiter") {
                    try {
                        pass.receive()
                    } catch (_: ClosedReceiveChannelException) {
                        // the kitchen closed: go home
                    }
                }
            session.awaitSuspension("waiter")

            pass.close()
            waiter.join()
            session.awaitEvent { it is CoroutineCompleted && it.label == "waiter" }

            assertEquals(listOf("CoroutineSuspended", "CoroutineResumed"), session.suspensionKindsOf("waiter"))
        }

    @Test
    fun `a send to a closed channel fails at once, without a recorded wait`() =
        runBlocking {
            val pass = viz.vizChannel<Int>(Channel.RENDEZVOUS, "pass")
            pass.close()
            val failure = CompletableDeferred<Throwable?>()
            viz.vizLaunch("late") { failure.complete(runCatching { pass.send(1) }.exceptionOrNull()) }.join()

            assertTrue(failure.await() is ClosedSendChannelException)
            assertEquals(emptyList(), session.suspensionKindsOf("late"))
        }

    @Test
    fun `a wait cancelled in send, receive, receiveCatching or a for loop records no resume`() =
        runBlocking {
            val pass = viz.vizChannel<Int>(Channel.RENDEZVOUS, "pass")
            cancelledWhileWaiting("sender") { pass.send(1) }
            cancelledWhileWaiting("receiver") { pass.receive() }
            cancelledWhileWaiting("catcher") { pass.receiveCatching() }
            cancelledWhileWaiting("looper") { pass.iterator().hasNext() }
        }

    @Test
    fun `a wait in send, receive, receiveCatching or a for loop ended by a timeout records the resume`() =
        runBlocking {
            val pass = viz.vizChannel<Int>(Channel.RENDEZVOUS, "pass")
            survivesTimeout("sender") { pass.send(1) }
            survivesTimeout("receiver") { pass.receive() }
            survivesTimeout("catcher") { pass.receiveCatching() }
            survivesTimeout("looper") { pass.iterator().hasNext() }
        }

    @Test
    fun `receiveCatching and a for loop over ready elements record no suspension`() =
        runBlocking {
            val tray = viz.vizChannel<Int>(3, "tray")
            tray.send(1)
            tray.send(2)
            tray.send(3)
            tray.close()
            viz.vizLaunch("catcher") { check(tray.receiveCatching().isSuccess) }.join()
            viz.vizLaunch("looper") { for (plate in tray) check(plate > 0) }.join()

            assertEquals(emptyList(), session.suspensionKindsOf("catcher"))
            assertEquals(emptyList(), session.suspensionKindsOf("looper"))
        }

    @Test
    fun `next after the end of a for loop fails as the channel's own iterator does`() =
        runBlocking {
            val closed = viz.vizChannel<Int>(1, "closed")
            closed.close()
            val ended = closed.iterator()
            assertFalse(ended.hasNext())
            assertFailsWith<ClosedReceiveChannelException> { ended.next() }

            val failed = viz.vizChannel<Int>(1, "failed")
            failed.close(IllegalStateException("kitchen fire"))
            val failing = failed.iterator()
            assertEquals("kitchen fire", assertFailsWith<IllegalStateException> { failing.hasNext() }.message)
            assertEquals("kitchen fire", assertFailsWith<IllegalStateException> { failing.next() }.message)

            assertFailsWith<IllegalStateException> { viz.vizChannel<Int>(1, "fresh").iterator().next() }
        }

    @Test
    fun `senders and receivers handing off in a loop record balanced waits`() =
        runBlocking {
            val belt = viz.vizChannel<Int>(Channel.RENDEZVOUS, "belt")
            val senders = (1..3).map { n -> viz.vizLaunch("sender-$n") { repeat(20) { belt.send(it) } } }
            // A rendezvous channel with no receiver yet: every sender waits before the receivers start.
            for (n in 1..3) session.awaitSuspension("sender-$n")
            val receivers = (1..2).map { n -> viz.vizLaunch("receiver-$n") { repeat(30) { belt.receive() } } }
            withTimeout(20.seconds) { (senders + receivers).joinAll() }

            for (label in (1..3).map { "sender-$it" } + (1..2).map { "receiver-$it" }) {
                assertTrue(session.waitsAreBalanced(label), "$label: ${session.suspensionKindsOf(label)}")
            }
        }
}
