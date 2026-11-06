package com.jh.proj.coroutineviz.client

import com.jh.proj.coroutineviz.events.PollWatermark
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The SDK's watermark and loss reporting through the outbound buffer (spec 2026-10-21, C-7; P-5..P-9). */
@OptIn(ExperimentalCoroutinesApi::class)
class OutboundBufferWatermarkTest {
    private val second = 1_000_000_000L

    private fun event(n: Int): VizEvent = CoroutineCreated("s", n.toLong(), n.toLong(), "c$n", "j$n", null, "scope", "e$n")

    private fun childScope(context: CoroutineContext) = CoroutineScope(context + Job(context.job))

    private val VizEvent.label: String
        get() = if (this is PollWatermark) "wm@${tsNanos / second}${if (final) "!" else ""}" else (this as CoroutineCreated).coroutineId

    /**
     * Runs [body] with the feed on a single thread that is provably blocked while [body] produces (the blocker has
     * started before [body] runs), then releases it and drains the buffer to its close (the body ends with a cutoff).
     * Returns everything the drain sent, collected on the calling thread.
     */
    private fun withBlockedFeed(
        buffer: OutboundBuffer,
        session: VizSession,
        body: () -> Unit,
    ): List<VizEvent> {
        val feedThread: ExecutorService = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(feedThread.asCoroutineDispatcher() + Job())
        try {
            runBlocking { buffer.feed(session, scope) }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            feedThread.execute {
                entered.countDown()
                release.await()
            }
            entered.await()
            body()
            release.countDown()
            val sent = mutableListOf<VizEvent>()
            runBlocking { withTimeout(10_000) { buffer.drain { sent += it } } }
            return sent
        } finally {
            scope.cancel()
            session.close()
            feedThread.shutdownNow()
        }
    }

    @Test
    fun `a watermark follows the events its poll sent, even when the socket drains slowly`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = VizSession("s")
            val scope = childScope(coroutineContext)
            val buffer = OutboundBuffer()
            buffer.feed(session, scope)
            session.send(event(0))
            session.send(event(1))
            buffer.poll(session, 5 * second, 5_000)
            session.send(event(2))
            val sent = mutableListOf<String>()
            val drain = scope.launch { buffer.drain { delay(10); sent += it.label } }
            withTimeout(5_000) { while (sent.size < 4) delay(5) }
            drain.cancelAndJoin()
            assertEquals(listOf("c0", "c1", "wm@5", "c2"), sent)
            scope.cancel()
            session.close()
        }

    @Test
    fun `at most one watermark a second, the first at once, sent with seq 0`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = VizSession("s")
            val scope = childScope(coroutineContext)
            val buffer = OutboundBuffer()
            buffer.feed(session, scope)
            listOf(0L, 500_000_000L, 1_000_000_000L, 1_600_000_000L, 2_100_000_000L).forEach { buffer.poll(session, it, 1) }
            val sent = mutableListOf<PollWatermark>()
            val drain = scope.launch { buffer.drain { sent += it as PollWatermark } }
            drain.cancelAndJoin()
            assertEquals(listOf(0L, 1_000_000_000L, 2_100_000_000L), sent.map { it.tsNanos })
            assertTrue(sent.all { it.seq == 0L })
            scope.cancel()
            session.close()
        }

    @Test
    fun `a watermark carries the events dropped on overflow, and a dropped watermark is not one`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = VizSession("s")
            val scope = childScope(coroutineContext)
            val buffer = OutboundBuffer(2)
            buffer.feed(session, scope)
            repeat(3) { session.send(event(it)) } // the third does not fit
            buffer.poll(session, 0, 1) // does not fit either: skipped, not counted
            assertEquals(1, buffer.lost)
            val sent = mutableListOf<VizEvent>()
            val drain = scope.launch { buffer.drain { sent += it } }
            buffer.poll(session, 2 * second, 1)
            drain.cancelAndJoin()
            assertEquals(listOf("c0", "c1", "wm@2"), sent.map { it.label })
            assertEquals(1, (sent.last() as PollWatermark).lostEvents)
            scope.cancel()
            session.close()
        }

    @Test
    fun `evicted bus entries count as lost only when they were events`() {
        val session = VizSession("s")
        val buffer = OutboundBuffer(20_000)
        val sent =
            withBlockedFeed(buffer, session) {
                repeat(20) { buffer.poll(session, it.toLong(), 1) } // the 20 oldest entries: watermarks
                repeat(10_000) { session.send(event(it)) }
                buffer.poll(session, 10 * second, 1) // 10,021 entries: the bus keeps 10,000, the 21 oldest are evicted
                buffer.cutoff(session) // evicts one more event: the bus keeps the newest 10,000
            }
        assertEquals(9_998, sent.count { it !is PollWatermark })
        val final = sent.last() as PollWatermark
        assertTrue(final.final)
        assertEquals(2, final.lostEvents, "20 evicted watermarks are not events; 2 evicted events are")
    }

    @Test
    fun `watermarks evicted alone lose nothing`() {
        val session = VizSession("s")
        val buffer = OutboundBuffer(20_000)
        val sent =
            withBlockedFeed(buffer, session) {
                repeat(10_005) { buffer.poll(session, it.toLong(), 1) }
                buffer.cutoff(session)
            }
        val final = sent.last() as PollWatermark
        assertTrue(final.final)
        assertEquals(0, final.lostEvents)
        assertFalse(final.deliveryUncertain)
    }

    @Test
    fun `a gap before the cutoff still ends with a final watermark that counts it`() {
        val session = VizSession("s")
        val buffer = OutboundBuffer(20_000)
        val sent =
            withBlockedFeed(buffer, session) {
                repeat(10_010) { session.send(event(it)) } // entries 1..10,010
                buffer.poll(session, 1 * second, 1) // entry 10,011
                buffer.cutoff(session) // entry 10,012: the bus keeps 13..10,012 for the feed; 12 events are evicted
            }
        assertEquals(9_998, sent.count { it !is PollWatermark })
        val final = sent.last() as PollWatermark
        assertTrue(final.final, "the marker survived: a clean close")
        assertEquals(12, final.lostEvents)
    }

    @Test
    fun `an evicted marker ends the capture at its index, with the losses before it counted`() {
        val session = VizSession("s")
        val buffer = OutboundBuffer(20_000)
        val sent =
            withBlockedFeed(buffer, session) {
                buffer.poll(session, 1 * second, 1) // entry 1
                session.send(event(0)) // entry 2
                buffer.cutoff(session) // entry 3: the marker …
                repeat(10_000) { session.send(event(it + 1)) } // … evicted with entries 1 and 2 by what follows
            }
        assertEquals(emptyList(), sent, "nothing after the cutoff is sent, and no poll was received: no final watermark (P-9)")
        assertEquals(1, buffer.lost, "the one event before the marker; the evicted watermark is not one")
    }
    @Test
    fun `a socket that ends after taking an event leaves delivery uncertain, sticky`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = VizSession("s")
            val scope = childScope(coroutineContext)
            val buffer = OutboundBuffer()
            buffer.feed(session, scope)
            buffer.poll(session, 0, 1)
            runCatching { buffer.drain { throw IOException("socket dropped") } }
            assertFalse(buffer.deliveryUncertain, "only a watermark was in flight")
            session.send(event(0))
            runCatching { buffer.drain { throw IOException("socket dropped") } }
            assertTrue(buffer.deliveryUncertain)
            buffer.poll(session, 5 * second, 1)
            val sent = mutableListOf<VizEvent>()
            val drain = scope.launch { buffer.drain { sent += it } }
            drain.cancelAndJoin()
            assertTrue((sent.last() as PollWatermark).deliveryUncertain)
            scope.cancel()
            session.close()
        }

    @Test
    fun `the cutoff ends the capture with a final watermark carrying the newest poll`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = VizSession("s")
            val scope = childScope(coroutineContext)
            val buffer = OutboundBuffer()
            buffer.feed(session, scope)
            buffer.poll(session, 1 * second, 1)
            session.send(event(0))
            buffer.poll(session, 1 * second + 300_000_000L, 1) // held back by the rate limit
            buffer.cutoff(session)
            session.send(event(1)) // after the cutoff: outside the capture
            buffer.poll(session, 9 * second, 1) // after the cutoff: not even put on the bus
            val sent = mutableListOf<VizEvent>()
            withTimeout(5_000) { buffer.drain { sent += it } } // returns: the buffer closed after the final watermark
            assertEquals(listOf("wm@1", "c0", "wm@1!"), sent.map { it.label })
            val final = sent.last() as PollWatermark
            assertEquals(1 * second + 300_000_000L, final.tsNanos, "the newest poll before the cutoff")
            assertEquals(0, final.lostEvents)
            assertEquals(0, buffer.lost, "nothing after the cutoff counts as lost")
            scope.cancel()
            session.close()
        }

    @Test
    fun `without a completed poll the capture ends with no final watermark`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = VizSession("s")
            val scope = childScope(coroutineContext)
            val buffer = OutboundBuffer()
            buffer.feed(session, scope)
            session.send(event(0))
            buffer.cutoff(session)
            val sent = mutableListOf<VizEvent>()
            withTimeout(5_000) { buffer.drain { sent += it } }
            assertEquals(listOf("c0"), sent.map { it.label }, "no watermark is made up (H-1)")
            scope.cancel()
            session.close()
        }

    @Test
    fun `a cutoff before the feed subscribed closes the buffer at once`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = VizSession("s")
            val buffer = OutboundBuffer()
            buffer.cutoff(session)
            withTimeout(1_000) { buffer.drain { error("nothing was captured") } }
            session.close()
        }
}
