package com.jh.proj.coroutineviz.client

import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.session.source.InstrumentationSource
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reconnect/backoff tests (T-07-08). The [Backoff] math is asserted directly
 * (deterministic, no jitter); the loop behavior is driven under runTest virtual
 * time with a fake transport so no real network or wall-clock delay is involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectTest {
    private class NoopSource : InstrumentationSource {
        override val sourceId = "noop"
        override val isRunning = false

        override fun start() = Unit

        override fun stop() = Unit
    }

    /** Zero-jitter backoff (Random fixed to 0.0) for exact assertions. */
    private val noJitter = Backoff(baseMillis = 500L, capMillis = 30_000L, random = FixedZeroRandom)

    @Test
    fun `backoff is exponential from 500ms doubling`() {
        assertEquals(500L, noJitter.delayMillis(0))
        assertEquals(1_000L, noJitter.delayMillis(1))
        assertEquals(2_000L, noJitter.delayMillis(2))
        assertEquals(4_000L, noJitter.delayMillis(3))
        assertEquals(8_000L, noJitter.delayMillis(4))
    }

    @Test
    fun `backoff is capped at 30s`() {
        // 500 << 6 = 32000 > cap → capped to 30000; and far beyond.
        assertEquals(30_000L, noJitter.delayMillis(6))
        assertEquals(30_000L, noJitter.delayMillis(20))
        assertEquals(30_000L, noJitter.delayMillis(100))
    }

    @Test
    fun `jitter never pushes the delay above the cap`() {
        val maxJitter = Backoff(baseMillis = 500L, capMillis = 30_000L, random = FixedOneRandom)
        repeat(40) { attempt ->
            assertTrue(maxJitter.delayMillis(attempt) <= 30_000L, "delay must never exceed the 30s cap")
        }
    }

    @Test
    fun `reconnect with backoff after a dropped connection - virtual time`() =
        runTest {
            val attempts = AtomicInteger(0)
            val succeeded = AtomicBoolean(false)
            // Child job sharing the test dispatcher so client.stop()'s scope.cancel()
            // tears down only the client loop, not the surrounding test coroutine.
            val scope = CoroutineScope(coroutineContext + Job(coroutineContext.job))
            // Transport throws on the first two attempts (dropped), succeeds on the
            // third (clean return = socket closed), then we stop the loop.
            val transport: suspend () -> Unit = {
                when (attempts.getAndIncrement()) {
                    0, 1 -> throw java.io.IOException("dropped")
                    else -> succeeded.set(true)
                }
            }
            val client =
                VizcoreClient(
                    httpClient = HttpClient(),
                    backendUrl = "",
                    sessionId = "s",
                    token = "t",
                    session = VizSession("s"),
                    source = NoopSource(),
                    scope = scope,
                    backoff = Backoff(baseMillis = 500L, capMillis = 30_000L, random = FixedZeroRandom),
                    transport = transport,
                )
            client.start()
            // Advance virtual time past two backoff intervals (500ms + 1000ms).
            testScheduler.advanceTimeBy(2_000L)
            testScheduler.runCurrent()

            assertTrue(attempts.get() >= 3, "the loop retried after each drop and resumed")
            assertTrue(succeeded.get(), "streaming resumed after a successful reconnect")
            // Tear the loop down and drain so runTest sees no leaked coroutine.
            client.stop()
            testScheduler.advanceUntilIdle()
        }

    @Test
    fun `CancellationException is rethrown not retried`() =
        runTest {
            val attempts = AtomicInteger(0)
            val scope = CoroutineScope(coroutineContext + Job(coroutineContext.job))
            val transport: suspend () -> Unit = {
                attempts.incrementAndGet()
                throw CancellationException("cancelled")
            }
            val client =
                VizcoreClient(
                    httpClient = HttpClient(),
                    backendUrl = "",
                    sessionId = "s",
                    token = "t",
                    session = VizSession("s"),
                    source = NoopSource(),
                    scope = scope,
                    backoff = Backoff(random = FixedZeroRandom),
                    transport = transport,
                )
            client.start()
            testScheduler.advanceTimeBy(60_000L)
            testScheduler.runCurrent()

            assertEquals(1, attempts.get(), "a CancellationException must propagate, never trigger a retry")
            client.stop()
            testScheduler.advanceUntilIdle()
        }

    /** Random that always returns 0.0 — eliminates jitter for exact assertions. */
    private object FixedZeroRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0

        override fun nextDouble(): Double = 0.0
    }

    /** Random that always returns the max — exercises the jitter ceiling. */
    private object FixedOneRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0

        override fun nextDouble(): Double = 0.999999
    }
}
