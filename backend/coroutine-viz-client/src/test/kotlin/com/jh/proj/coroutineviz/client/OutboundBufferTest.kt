package com.jh.proj.coroutineviz.client

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [OutboundBuffer] — the lifetime-scoped bridge between the
 * `replay = 0` EventBus and the churning ingest sockets. Driven under runTest with
 * an [UnconfinedTestDispatcher] so the single feed collector and the active drainer
 * run EAGERLY on emit/launch — making `session.send -> feed -> channel -> drain`
 * deterministic without manual scheduler stepping (mirrors the runTest convention of
 * [ReconnectTest], which uses virtual time for its delay-driven loop). Synthetic
 * events are pushed through a real [VizSession] via `session.send` (no real
 * DebugProbes, no `@Tag("integration")`).
 *
 * Assertions key off the immutable `coroutineId` (`c0`, `c1`, ...), NOT `seq`:
 * [VizSession.send] re-stamps `seq` under its ordering lock, so a synthetic `seq = 0`
 * becomes `1` on store. Identity/order is asserted via `coroutineId` instead.
 *
 * The long-lived feed + drain coroutines run on a child [feedScope]; each test
 * cancels it (and [VizSession.close]s the session, cancelling the eager
 * ProjectionService collector) before the body ends so runTest sees no leaked job.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutboundBufferTest {
    private fun event(n: Int): VizEvent =
        CoroutineCreated(
            sessionId = "s",
            seq = n.toLong(),
            tsNanos = n.toLong(),
            coroutineId = "c$n",
            jobId = "j$n",
            parentCoroutineId = null,
            scopeId = "scope",
            label = "e$n",
        )

    private val VizEvent.cid: String get() = (this as CoroutineCreated).coroutineId

    /** Child scope on the (unconfined) test dispatcher; cancel tears down only its jobs. */
    private fun childScope(context: CoroutineContext): CoroutineScope = CoroutineScope(context + Job(context.job))

    @Test
    fun `feed does not return until its own bus subscription is established`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = VizSession(sessionId = "s")
            val feedScope = childScope(coroutineContext)
            val buffer = OutboundBuffer()

            // The session eagerly subscribes its own ProjectionService collector, so
            // the baseline may already be non-zero. feed() must add exactly one more.
            val baseline = session.bus.subscriptionCount.value
            buffer.feed(session, feedScope)
            assertTrue(
                session.bus.subscriptionCount.value > baseline,
                "feed() must await its OWN bus subscription (count above baseline)",
            )

            // After feed has returned, emits are deterministically captured.
            session.send(event(0))
            val received = mutableListOf<VizEvent>()
            val drainJob = feedScope.launch { buffer.drain { received += it } }

            assertTrue(received.any { it.cid == "c0" }, "event emitted after feed subscribed must be captured")

            drainJob.cancelAndJoin()
            buffer.close()
            feedScope.cancel()
            session.close()
        }

    @Test
    fun `the feed collector subscribes to the bus exactly once and survives drain restarts`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = VizSession(sessionId = "s")
            val feedScope = childScope(coroutineContext)
            val buffer = OutboundBuffer()

            buffer.feed(session, feedScope)

            val received = mutableListOf<VizEvent>()

            // Socket A drains the first window.
            session.send(event(0))
            session.send(event(1))
            val drainA = feedScope.launch { buffer.drain { received += it } }

            // Snapshot the subscriber count with both feed + ProjectionService settled.
            val subscribersBeforeRestart = session.bus.subscriptionCount.value

            // Simulate disconnect: cancel socket A's drain.
            drainA.cancelAndJoin()

            // Bus subscription must be unchanged (feed survives, never re-subscribes).
            assertEquals(
                subscribersBeforeRestart,
                session.bus.subscriptionCount.value,
                "one feed survives the drain restart (no re-subscribe)",
            )

            // More events while no drainer runs.
            session.send(event(2))
            session.send(event(3))

            // Socket B drains the rest.
            val drainB = feedScope.launch { buffer.drain { received += it } }

            assertEquals(
                listOf("c0", "c1", "c2", "c3"),
                received.map { it.cid },
                "ALL events from both windows arrive in order across the drain restart",
            )

            drainB.cancelAndJoin()
            buffer.close()
            feedScope.cancel()
            session.close()
        }

    @Test
    fun `events emitted while no socket is draining are retained and delivered on the next drain`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = VizSession(sessionId = "s")
            val feedScope = childScope(coroutineContext)
            val buffer = OutboundBuffer()

            buffer.feed(session, feedScope)

            // No active drainer: the no-socket startup/backoff window.
            val n = 50
            repeat(n) { session.send(event(it)) }

            // Now start draining; everything must arrive.
            val received = mutableListOf<VizEvent>()
            val drainJob = feedScope.launch { buffer.drain { received += it } }

            assertEquals(n, received.size, "all events emitted with no drainer are retained and delivered")
            assertEquals((0 until n).map { "c$it" }, received.map { it.cid }, "delivered in order")

            drainJob.cancelAndJoin()
            buffer.close()
            feedScope.cancel()
            session.close()
        }

    @Test
    fun `overflow increments the surfaced dropped counter instead of silently discarding`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = VizSession(sessionId = "s")
            val feedScope = childScope(coroutineContext)
            val capacity = 8
            val buffer = OutboundBuffer(capacity = capacity)

            buffer.feed(session, feedScope)

            // Overflow the buffer while no drainer runs. With the unconfined dispatcher
            // the feed offers each event eagerly, so once capacity is exceeded every
            // further trySend fails and is counted.
            val total = capacity * 4
            repeat(total) { session.send(event(it)) }

            assertTrue(
                buffer.dropped > 0,
                "overflow must increment the surfaced dropped counter (dropped=${buffer.dropped})",
            )

            // Drain the survivors (feed is cancelled first so no new event slips in
            // during the accounting read).
            val received = mutableListOf<VizEvent>()
            val drainJob = feedScope.launch { buffer.drain { received += it } }
            drainJob.cancelAndJoin()

            // Every emitted event is accounted for: delivered + dropped == total.
            assertEquals(
                total.toLong(),
                received.size.toLong() + buffer.dropped,
                "delivered + dropped accounts for every emitted event — loss is COUNTED, never silent",
            )

            buffer.close()
            feedScope.cancel()
            session.close()
        }

    @Test
    fun `close ends draining cleanly and is idempotent`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = VizSession(sessionId = "s")
            val feedScope = childScope(coroutineContext)
            val buffer = OutboundBuffer()

            buffer.feed(session, feedScope)
            session.send(event(0))

            val received = mutableListOf<VizEvent>()
            val drainJob = feedScope.launch { buffer.drain { received += it } }

            // close() ends the drain cleanly (channel closed-for-receive), no throw.
            buffer.close()
            buffer.close() // idempotent
            drainJob.join()

            assertTrue(received.any { it.cid == "c0" }, "buffered event drained before close")
            feedScope.cancel()
            session.close()
        }
}
