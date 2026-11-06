package com.jh.proj.coroutineviz.client

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.PollWatermark
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.session.source.InstrumentationSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.serialization.PolymorphicSerializer
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `stop()` drains and closes with a final watermark (spec 2026-10-21, C-7; P-10). */
class VizcoreClientStopTest {
    private class FakeSource : InstrumentationSource {
        override val sourceId = "fake"
        private val started = AtomicBoolean(false)
        override val isRunning: Boolean get() = started.get()

        override fun start() = started.set(true)

        override fun stop() = started.set(false)
    }

    private fun event(n: Int): VizEvent = CoroutineCreated("s", n.toLong(), n.toLong(), "c$n", "j$n", null, "sc", "e$n")

    /** A client whose transport opens a socket that never takes anything; [opened] counts down when it is open. */
    private fun stalledClient(opened: CountDownLatch, scopeJob: Job, source: FakeSource = FakeSource()) =
        VizcoreClient(
            httpClient = HttpClient(),
            backendUrl = "",
            sessionId = "s",
            token = "t",
            session = VizSession("s"),
            source = source,
            scope = CoroutineScope(Dispatchers.Default + scopeJob),
            transport = { _, lifetime ->
                lifetime.opened()
                opened.countDown()
                awaitCancellation()
            },
        )

    @Test
    fun `stop delivers every captured event and closes with the final watermark last`() =
        testApplication {
            val received = CopyOnWriteArrayList<VizEvent>()
            val connected = AtomicBoolean(false)
            val closed = AtomicBoolean(false)
            application {
                install(ServerWebSockets)
                routing {
                    get("/api/sessions/s1/health") { call.respondText("{}") }
                    webSocket("/api/sessions/{id}/ingest") {
                        connected.set(true)
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                received += appJson.decodeFromString(PolymorphicSerializer(VizEvent::class), frame.readText())
                            }
                        }
                        closed.set(true)
                    }
                }
            }
            val local = VizSession("s1")
            val source = FakeSource()
            val buffer = OutboundBuffer()
            val client =
                VizcoreClient(
                    httpClient = createClient { install(WebSockets) },
                    backendUrl = "",
                    sessionId = "s1",
                    token = "t",
                    session = local,
                    source = source,
                    scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
                    buffer = buffer,
                )
            client.start()
            pollFor { if (source.isRunning && connected.get()) Unit else null }
            buffer.poll(local, 1_000, 1)
            repeat(500) { local.send(event(it)) }
            buffer.poll(local, 2_000, 2)

            val started = System.nanoTime()
            withContext(Dispatchers.IO) { client.stop() }
            assertTrue(System.nanoTime() - started < 5_000_000_000L, "within the deadline")

            pollFor { if (closed.get()) Unit else null }
            assertEquals(500, received.count { it !is PollWatermark }, "every event captured before stop() arrives")
            val last = received.last() as PollWatermark
            assertTrue(last.final, "the close message comes last")
            assertEquals(2_000, last.tsNanos, "it carries the newest poll")
            assertEquals(0, last.lostEvents)
        }

    @Test
    fun `stop without an open socket does not wait`() {
        val client =
            VizcoreClient(
                httpClient = HttpClient(),
                backendUrl = "",
                sessionId = "s",
                token = "t",
                session = VizSession("s"),
                source = FakeSource(),
                scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
                transport = { _, _ -> awaitCancellation() }, // never opens a socket
            )
        client.start()
        val started = System.nanoTime()
        client.stop()
        assertTrue(System.nanoTime() - started < 1_000_000_000L)
    }

    @Test
    fun `stop gives up after its deadline when the socket does not drain`() {
        val opened = CountDownLatch(1)
        val scopeJob = SupervisorJob()
        val client = stalledClient(opened, scopeJob)
        client.start()
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        val started = System.nanoTime()
        client.stop(300)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs in 250..2_000, "waited for the 300 ms deadline, took $tookMs ms")
        assertFalse(scopeJob.isActive)
    }

    @Test
    fun `an interrupt ends the wait, keeps the interrupt, and still releases everything`() {
        val opened = CountDownLatch(1)
        val scopeJob = SupervisorJob()
        val source = FakeSource()
        val client = stalledClient(opened, scopeJob, source)
        client.start()
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        var interruptKept = false
        val stopper =
            thread {
                client.stop(30_000)
                interruptKept = Thread.currentThread().isInterrupted
            }
        Thread.sleep(200)
        stopper.interrupt()
        stopper.join(5_000)
        assertFalse(stopper.isAlive, "stop() returned")
        assertTrue(interruptKept)
        assertFalse(scopeJob.isActive)
        val deadline = System.currentTimeMillis() + 5_000
        while (source.isRunning && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse(source.isRunning, "released on its own thread")
    }

    @Test
    fun `a source that takes long to stop does not hold stop past its deadline`() {
        val stopping = CountDownLatch(1)
        val slow =
            object : InstrumentationSource {
                override val sourceId = "slow"
                @Volatile override var isRunning = false

                override fun start() {
                    isRunning = true
                }

                override fun stop() {
                    stopping.countDown()
                    Thread.sleep(3_000) // e.g. DebugProbes retransforming classes
                    isRunning = false
                }
            }
        fun client() =
            VizcoreClient(
                httpClient = HttpClient(),
                backendUrl = "",
                sessionId = "s",
                token = "t",
                session = VizSession("s"),
                source = slow,
                scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
                transport = { _, _ -> awaitCancellation() },
            )
        for (timeout in listOf(0L, 300L)) {
            val c = client()
            c.start()
            val started = System.nanoTime()
            c.stop(timeout)
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(tookMs < timeout + 700, "stop($timeout) took $tookMs ms")
        }
        assertTrue(stopping.await(5, TimeUnit.SECONDS), "the source is still stopped, on its own thread")
    }

    @Test
    fun `a second stop does not wait for the first one's teardown`() {
        val stopping = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked =
            object : InstrumentationSource {
                override val sourceId = "blocked"
                @Volatile override var isRunning = false

                override fun start() {
                    isRunning = true
                }

                override fun stop() {
                    stopping.countDown()
                    release.await()
                    isRunning = false
                }
            }
        val client =
            VizcoreClient(
                httpClient = HttpClient(),
                backendUrl = "",
                sessionId = "s",
                token = "t",
                session = VizSession("s"),
                source = blocked,
                scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
                transport = { _, _ -> awaitCancellation() },
            )
        try {
            client.start()
            client.stop(0)
            assertTrue(stopping.await(5, TimeUnit.SECONDS), "the teardown is under way and blocked")
            val started = System.nanoTime()
            client.stop(0)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 500, "the second stop returns at once")
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `once the client is capturing, an event sent right away reaches the buffer`() {
        // The feed's dispatcher is held back, as on a busy machine: start() alone would return before the feed subscribed.
        val feedThread = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        feedThread.execute { release.await() }
        val local = VizSession("s")
        val buffer = OutboundBuffer()
        val client =
            VizcoreClient(
                httpClient = HttpClient(),
                backendUrl = "",
                sessionId = "s",
                token = "t",
                session = local,
                source = FakeSource(),
                scope = CoroutineScope(feedThread.asCoroutineDispatcher() + SupervisorJob()),
                buffer = buffer,
                transport = { _, _ -> awaitCancellation() },
            )
        try {
            client.start()
            thread { Thread.sleep(300); release.countDown() }
            assertTrue(client.awaitCapture(5_000))
            local.send(CoroutineCreated("s", 1, 1, "c1", "j1", null, "sc", "e1"))
            val deadline = System.currentTimeMillis() + 5_000
            while (buffer.offered < 1 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(1, buffer.offered)
        } finally {
            client.stop()
            feedThread.shutdownNow()
        }
    }

    @Test
    fun `start after stop does nothing`() {
        val source = FakeSource()
        val client =
            VizcoreClient(
                httpClient = HttpClient(),
                backendUrl = "",
                sessionId = "s",
                token = "t",
                session = VizSession("s"),
                source = source,
                scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
                transport = { _, _ -> awaitCancellation() },
            )
        client.start()
        client.stop()
        client.start()
        Thread.sleep(100)
        assertFalse(source.isRunning)
    }
}
