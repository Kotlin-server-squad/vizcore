package com.jh.proj.coroutineviz.client

import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.session.source.InstrumentationSource
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #254: a client that [VizcoreClient.start] could not get a session for, because the backend was
 * unreachable, creates the session in the background and then streams into it. Driven on `runTest`
 * virtual time with a fake session create and a fake transport.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VizcoreClientPendingSessionTest {
    private class NoopSource : InstrumentationSource {
        override val sourceId = "noop"
        override val isRunning = false

        override fun start() = Unit

        override fun stop() = Unit
    }

    /** Zero-jitter backoff (Random fixed to 0.0) so delays are exact. */
    private object FixedZeroRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0

        override fun nextDouble(): Double = 0.0
    }

    @Test
    fun `a client without a session creates one in the background, backing off between failures`() =
        runTest {
            val createTimes = CopyOnWriteArrayList<Long>()
            val streamedIds = CopyOnWriteArrayList<String>()
            val failuresLeft = AtomicInteger(3)
            val scope = CoroutineScope(coroutineContext + Job(coroutineContext.job))

            val client =
                VizcoreClient(
                    httpClient = HttpClient(),
                    backendUrl = "",
                    sessionId = null,
                    token = "t",
                    session = VizSession(""),
                    source = NoopSource(),
                    scope = scope,
                    backoff = Backoff(baseMillis = 500L, capMillis = 30_000L, random = FixedZeroRandom),
                    appName = "pending-app",
                    transport = { id, lifetime ->
                        streamedIds += id
                        lifetime.opened()
                        awaitCancellation()
                    },
                    createInitialSession = {
                        createTimes += testScheduler.currentTime
                        if (failuresLeft.getAndDecrement() > 0) throw ConnectException("Connection refused")
                        "created-1"
                    },
                    clock = { testScheduler.currentTime },
                )
            client.start()
            assertNull(client.sessionIdOrNull)
            assertEquals("", client.currentSessionId)

            // Fails at 0, 500 and 1500; the fourth try, at 3500, succeeds.
            testScheduler.advanceTimeBy(3_600L)
            testScheduler.runCurrent()

            assertEquals(listOf(0L, 500L, 1_500L, 3_500L), createTimes.toList(), "session creates back off like reconnects")
            assertEquals("created-1", client.sessionIdOrNull)
            assertEquals("created-1", client.currentSessionId)
            assertEquals(listOf("created-1"), streamedIds.toList(), "nothing is streamed before the session exists")

            client.stop()
            testScheduler.advanceUntilIdle()
        }

    @Test
    fun `a background session create that gets no answer is abandoned and retried`() =
        runTest {
            // A forwarder in front of a backend that is down: it accepts the request and never answers.
            val createTimes = CopyOnWriteArrayList<Long>()
            val streamedIds = CopyOnWriteArrayList<String>()
            val scope = CoroutineScope(coroutineContext + Job(coroutineContext.job))
            val client =
                VizcoreClient(
                    httpClient = HttpClient(),
                    backendUrl = "",
                    sessionId = null,
                    token = "t",
                    session = VizSession(""),
                    source = NoopSource(),
                    scope = scope,
                    backoff = Backoff(baseMillis = 500L, capMillis = 30_000L, random = FixedZeroRandom),
                    appName = "pending-app",
                    transport = { id, lifetime ->
                        streamedIds += id
                        lifetime.opened()
                        awaitCancellation()
                    },
                    createInitialSession = {
                        createTimes += testScheduler.currentTime
                        if (createTimes.size == 1) awaitCancellation()
                        "created-2"
                    },
                    clock = { testScheduler.currentTime },
                )
            client.start()
            testScheduler.advanceTimeBy(10_600L)
            testScheduler.runCurrent()

            assertEquals(listOf(0L, 10_500L), createTimes.toList(), "the silent create is given up after 10 s")
            assertEquals("created-2", client.sessionIdOrNull)
            assertEquals(listOf("created-2"), streamedIds.toList())

            client.stop()
            testScheduler.advanceUntilIdle()
        }

    /** Records whether the client stopped it. */
    private class RecordingSource : InstrumentationSource {
        override val sourceId = "recording"
        override val isRunning = false

        @Volatile var stopped = false

        override fun start() = Unit

        override fun stop() {
            stopped = true
        }
    }

    private fun pendingClient(
        scope: CoroutineScope,
        clock: () -> Long,
        transport: suspend (String, SocketLifetime) -> Unit,
        createInitialSession: suspend () -> String,
        source: InstrumentationSource = NoopSource(),
    ) = VizcoreClient(
        httpClient = HttpClient(),
        backendUrl = "",
        sessionId = null,
        token = "t",
        session = VizSession(""),
        source = source,
        scope = scope,
        backoff = Backoff(baseMillis = 500L, capMillis = 30_000L, random = FixedZeroRandom),
        appName = "pending-app",
        transport = transport,
        createInitialSession = createInitialSession,
        clock = clock,
    )

    @Test
    fun `creating the session does not reset the backoff of a socket that closes at once`() =
        runTest {
            // #129: only a socket that stayed open resets the backoff. After three failed creates the
            // backoff is at attempt 3, and a backend that drops every socket must keep it growing.
            val connectTimes = CopyOnWriteArrayList<Long>()
            val failuresLeft = AtomicInteger(3)
            val scope = CoroutineScope(coroutineContext + Job(coroutineContext.job))
            val client =
                pendingClient(
                    scope = scope,
                    clock = { testScheduler.currentTime },
                    transport = { _, lifetime ->
                        connectTimes += testScheduler.currentTime
                        lifetime.opened()
                    },
                    createInitialSession = {
                        if (failuresLeft.getAndDecrement() > 0) throw ConnectException("Connection refused")
                        "created-1"
                    },
                )
            client.start()
            testScheduler.advanceTimeBy(7_600L)
            testScheduler.runCurrent()
            client.stop()
            testScheduler.advanceUntilIdle()

            // Created at 3500 (after 500 + 1000 + 2000), first socket at once, the next after 4000.
            assertEquals(listOf(3_500L, 7_500L), connectTimes.take(2), "the backoff keeps growing after the create")
        }

    @Test
    fun `a cancellation thrown by the session create itself is a failed attempt, not a stop`() =
        runTest {
            // An HTTP client can fail a request with a CancellationException of its own; only a
            // cancelled client may end the loop.
            val creates = AtomicInteger(0)
            val scope = CoroutineScope(coroutineContext + Job(coroutineContext.job))
            val client =
                pendingClient(
                    scope = scope,
                    clock = { testScheduler.currentTime },
                    transport = { _, lifetime ->
                        lifetime.opened()
                        awaitCancellation()
                    },
                    createInitialSession = {
                        if (creates.incrementAndGet() == 1) throw CancellationException("request cancelled by the engine")
                        "created-1"
                    },
                )
            client.start()
            testScheduler.advanceTimeBy(600L)
            testScheduler.runCurrent()

            assertEquals(2, creates.get(), "the loop must retry after the request's own cancellation")
            assertEquals("created-1", client.sessionIdOrNull)
            client.stop()
            testScheduler.advanceUntilIdle()
        }

    @Test
    fun `a runtime the SDK cannot run on stops the background session create`() =
        runTest {
            // A class the create needs is missing (first loaded only now, after the backend came up).
            // Retrying cannot help, so the loop stops instead of warning forever.
            val creates = AtomicInteger(0)
            val source = RecordingSource()
            val scope = CoroutineScope(coroutineContext + Job(coroutineContext.job))
            val client =
                pendingClient(
                    scope = scope,
                    clock = { testScheduler.currentTime },
                    transport = { _, _ -> error("no session, so no socket") },
                    createInitialSession = {
                        creates.incrementAndGet()
                        throw NoSuchMethodError("kotlinx.serialization.json.Json.parseToJsonElement")
                    },
                    source = source,
                )
            client.start()
            testScheduler.advanceTimeBy(120_000L)
            testScheduler.runCurrent()

            assertEquals(1, creates.get(), "no retries on an app runtime the SDK cannot run on")
            assertNull(client.sessionIdOrNull)
            assertTrue(source.stopped, "the client stops its source (and with it the probes)")
            // stop() cancels the scope last, after closing the buffer and the HTTP client.
            assertTrue(!scope.coroutineContext.job.isActive, "the client's scope is cancelled")
        }

    @Test
    fun `stop ends the background session create while the backend is still down`() =
        runTest {
            val creates = AtomicInteger(0)
            val scope = CoroutineScope(coroutineContext + Job(coroutineContext.job))
            val httpClient = HttpClient()
            val client =
                VizcoreClient(
                    httpClient = httpClient,
                    backendUrl = "",
                    sessionId = null,
                    token = "t",
                    session = VizSession(""),
                    source = NoopSource(),
                    scope = scope,
                    backoff = Backoff(baseMillis = 500L, capMillis = 30_000L, random = FixedZeroRandom),
                    appName = "pending-app",
                    transport = { _, _ -> error("no session, so no socket") },
                    createInitialSession = {
                        creates.incrementAndGet()
                        throw ConnectException("Connection refused")
                    },
                    clock = { testScheduler.currentTime },
                )
            client.start()
            testScheduler.advanceTimeBy(1_600L)
            testScheduler.runCurrent()
            client.stop()
            val afterStop = creates.get()
            testScheduler.advanceTimeBy(120_000L)
            testScheduler.runCurrent()

            assertEquals(3, afterStop, "creates at 0, 500 and 1500 before stop")
            assertEquals(afterStop, creates.get(), "no session create after stop()")
            assertNull(client.sessionIdOrNull)
            assertFalse(httpClient.coroutineContext.job.isActive, "stop() closes the HTTP client while still retrying")
        }
}
