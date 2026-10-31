package com.jh.proj.coroutineviz.client

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
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

    /** Runs [block] with the buffer's log lines captured; returns them as "LEVEL message". */
    private inline fun capturingLog(block: () -> Unit): List<String> {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(OutboundBuffer::class.java) as Logger
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.map { "${it.level} ${it.formattedMessage}" }
    }

    @Test
    fun `a full buffer logs one warning, not one per dropped event`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = VizSession(sessionId = "s")
            val feedScope = childScope(coroutineContext)
            val buffer = OutboundBuffer(capacity = 8, reportIntervalMillis = 30_000, clock = { 0L })
            buffer.feed(session, feedScope)

            // A backend outage: nothing drains, and the app keeps producing (#255).
            val lines = capturingLog { repeat(5_000) { session.send(event(it)) } }

            assertEquals(5_000L - 8, buffer.dropped, "the drop policy itself is unchanged")
            assertEquals(1, lines.size, "one line for the whole burst, got ${lines.size}: ${lines.take(3)}")
            assertTrue(lines.single().startsWith("WARN"), lines.single())

            feedScope.cancel()
            session.close()
        }

    @Test
    fun `while drops continue, a summary is logged at most once per interval`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = VizSession(sessionId = "s")
            val feedScope = childScope(coroutineContext)
            var now = 0L
            val buffer = OutboundBuffer(capacity = 8, reportIntervalMillis = 30_000, clock = { now })
            buffer.feed(session, feedScope)

            val lines =
                capturingLog {
                    repeat(20) { session.send(event(it)) } // fills the buffer, 12 dropped
                    now = 29_999
                    repeat(100) { session.send(event(100 + it)) } // still inside the interval
                    now = 30_000
                    session.send(event(1_000)) // the interval is over: one summary
                    repeat(100) { session.send(event(2_000 + it)) } // a new interval starts
                }

            assertEquals(2, lines.size, "first drop plus one summary, got: $lines")
            val summary = lines[1]
            assertTrue(summary.startsWith("WARN"), summary)
            // Every drop since the first report: 11 + 100 + the one that closed the interval.
            assertTrue("dropped 112 events" in summary, summary)
            assertTrue("113 in total" in summary, summary)

            feedScope.cancel()
            session.close()
        }

    @Test
    fun `one info line once the buffer has drained to half, none while it hovers at full`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = VizSession(sessionId = "s")
            val feedScope = childScope(coroutineContext)
            val buffer = OutboundBuffer(capacity = 8, reportIntervalMillis = 30_000, clock = { 0L })
            buffer.feed(session, feedScope)
            repeat(12) { session.send(event(it)) } // full, 4 dropped

            // A slow backend: one event leaves and its send stalls, then one more is accepted and
            // the next ones are dropped again. The buffer never gets back to half: still one outage.
            val whileSlow =
                capturingLog {
                    val slow = feedScope.launch { buffer.drain { awaitCancellation() } }
                    repeat(3) { session.send(event(100 + it)) }
                    slow.cancelAndJoin()
                }
            assertTrue(whileSlow.none { it.startsWith("INFO") }, "no recovery while the buffer is still full: $whileSlow")

            // The backend is back: everything drains, and the next event is accepted.
            val lines =
                capturingLog {
                    val fast = feedScope.launch { buffer.drain { } }
                    session.send(event(200))
                    fast.cancelAndJoin()
                }

            assertEquals(1, lines.count { it.startsWith("INFO") }, "exactly one recovery line, got: $lines")
            val info = lines.single { it.startsWith("INFO") }
            assertTrue("${buffer.dropped} in total" in info, info)

            feedScope.cancel()
            session.close()
        }

    @Test
    fun `drops that end before the buffer drains are reported when it drains, with no new event`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = VizSession(sessionId = "s")
            val feedScope = childScope(coroutineContext)
            val buffer = OutboundBuffer(capacity = 8, reportIntervalMillis = 30_000, clock = { 0L })
            buffer.feed(session, feedScope)

            val lines =
                capturingLog {
                    repeat(12) { session.send(event(it)) } // full, 4 dropped, then the app goes quiet
                    // The backend is back and takes everything; nothing new is produced.
                    val drain = feedScope.launch { buffer.drain { } }
                    drain.cancelAndJoin()
                }

            assertEquals(2, lines.size, "the first drop and the recovery, got: $lines")
            val info = lines[1]
            assertTrue(info.startsWith("INFO"), info)
            assertTrue("3 dropped since the last report, 4 in total" in info, info)

            feedScope.cancel()
            session.close()
        }

    @Test
    @Timeout(30)
    fun `a buffer that drains while its first drop is being reported still reports the recovery`() =
        runTest(UnconfinedTestDispatcher()) {
            val session = VizSession(sessionId = "s")
            val feedScope = childScope(coroutineContext)
            lateinit var buffer: OutboundBuffer
            var drainedInTheGap = false
            // The clock is read after the failed send and before the overflow is marked: a transport
            // thread drains the whole buffer right there (#255).
            buffer =
                OutboundBuffer(capacity = 8, reportIntervalMillis = 30_000, clock = {
                    if (!drainedInTheGap) {
                        drainedInTheGap = true
                        val taken = AtomicInteger()
                        val failure = AtomicReference<Throwable>()
                        val transport =
                            thread {
                                runCatching {
                                    runBlocking {
                                        launch {
                                            buffer.drain {
                                                if (taken.incrementAndGet() == 8) currentCoroutineContext().job.cancel()
                                            }
                                        }.join()
                                    }
                                }.onFailure(failure::set)
                            }
                        transport.join(10_000)
                        // Only a drain that finished inside the window proves the race was exercised.
                        check(!transport.isAlive) { "the transport did not finish draining inside the window" }
                        failure.get()?.let { throw it }
                        check(taken.get() == 8) { "drained ${taken.get()} of 8 events" }
                    }
                    0L
                })
            buffer.feed(session, feedScope)

            // Eight fill the buffer, the ninth is dropped, and the app goes quiet.
            val lines = capturingLog { repeat(9) { session.send(event(it)) } }

            assertTrue(drainedInTheGap)
            assertEquals(1, lines.count { it.startsWith("WARN") }, "got: $lines")
            assertEquals(1, lines.count { it.startsWith("INFO") }, "the drained buffer must be reported, got: $lines")

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
