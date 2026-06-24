package com.jh.proj.coroutineviz.client

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.session.source.InstrumentationSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.application.install
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.PolymorphicSerializer
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * In-process round-trip + lifecycle tests for [VizcoreClient]. These use SYNTHETIC
 * events pushed through the client's local session (NOT real DebugProbes), so they
 * stay deterministic and in the default gate (no @Tag("integration")).
 *
 * Round-trip / stop tests use the testApplication's own coroutine context (real WS
 * I/O); only [ReconnectTest] uses runTest virtual time (it drives a fake transport).
 */
class VizcoreClientTest {
    /** A controllable fake source so tests assert start/stop without real DebugProbes. */
    private class FakeSource : InstrumentationSource {
        override val sourceId = "fake"
        private val started = AtomicBoolean(false)
        override val isRunning: Boolean get() = started.get()

        override fun start() {
            started.set(true)
        }

        override fun stop() {
            started.set(false)
        }
    }

    /**
     * Register the same ingest route shape the backend exposes (Plan 07-02): resolve
     * the session from SessionManager (auth off) and publish each text frame via
     * session.send into the SERVER-resolved session.
     */
    private fun Route.testIngestRoute() {
        webSocket("/api/sessions/{id}/ingest") {
            val id = call.parameters["id"]!!
            val session = SessionManager.getSession(id)!!
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val event = appJson.decodeFromString(PolymorphicSerializer(VizEvent::class), frame.readText())
                    session.send(event)
                }
            }
        }
    }

    private fun event(serverId: String, n: Int): VizEvent =
        CoroutineCreated(
            sessionId = serverId,
            seq = n.toLong(),
            tsNanos = n.toLong(),
            coroutineId = "c$n",
            jobId = "j$n",
            parentCoroutineId = null,
            scopeId = "s$n",
            label = "e$n",
        )

    @Test
    fun `round-trip is lossless across the no-socket startup window AND a forced reconnect`() =
        testApplication {
            application {
                install(ServerWebSockets)
                routing { testIngestRoute() }
            }
            // Create the server session inside the in-process backend.
            val serverSession = SessionManager.createSession("round-trip-app")
            val serverId = serverSession.sessionId

            val testClient = createClient { install(WebSockets) }
            // Pitfall 1: the local session is built with the SERVER-assigned id.
            val localSession = VizSession(sessionId = serverId)
            val scope = CoroutineScope(SupervisorJob())
            val buffer = OutboundBuffer()

            // Transport seam: the FIRST attempt throws (simulating a failed connect /
            // dropped socket) so the loop reconnects with backoff; later attempts run
            // the real buffer-draining WS send loop. This exercises the reconnect window.
            val attempts = AtomicInteger(0)
            val transport: suspend () -> Unit = {
                if (attempts.getAndIncrement() == 0) {
                    throw IOException("forced first-attempt drop")
                }
                stream(testClient, "", serverId, "test-token", buffer)
            }

            val client =
                VizcoreClient(
                    httpClient = testClient,
                    backendUrl = "",
                    sessionId = serverId,
                    token = "test-token",
                    session = localSession,
                    source = FakeSource(),
                    scope = scope,
                    backoff = Backoff(baseMillis = 50L, capMillis = 200L),
                    buffer = buffer,
                    transport = transport,
                )
            client.start()

            // Deterministically await the feed's bus subscription (NO sleep): once the
            // feed is provably subscribed, every subsequent emit is captured by the
            // lifetime buffer even though no socket is connected yet. The session
            // eagerly subscribes its own ProjectionService collector, so we await
            // count >= 2 (ProjectionService + the client's feed) to guarantee the FEED
            // specifically is live before emitting — the bus is replay=0, so an emit
            // before the feed subscribes would not reach it.
            localSession.bus.subscriptionCount.first { it >= 2 }

            // (b) Emit a BATCH while the WS is NOT yet connected (no-socket window) —
            // and the very first transport attempt is forced to fail, so these events
            // sit in the buffer across the entire reconnect-backoff window.
            val firstBatch = 25
            repeat(firstBatch) { localSession.send(event(serverId, it)) }

            // (e) Emit MORE events across the reconnect window.
            val secondBatch = 25
            repeat(secondBatch) { localSession.send(event(serverId, firstBatch + it)) }

            val total = firstBatch + secondBatch

            // The backend session must receive ALL emitted events (zero loss across the
            // no-socket startup window AND the forced reconnect), each carrying the
            // SERVER id. Assertion is on COMPLETENESS, gated on the subscription await
            // above — never a delay(200) sleep.
            pollFor { if (serverSession.store.all().size >= total) Unit else null }
            val received = serverSession.store.all()
            assertEquals(total, received.size, "every emitted event must arrive (zero loss across reconnect)")
            assertTrue(
                received.all { it.sessionId == serverId },
                "every received event must carry the SERVER session id (Pitfall 1)",
            )
            assertTrue(attempts.get() >= 2, "the forced first-attempt drop must have triggered a reconnect")

            client.stop()
            scope.cancel()
        }

    @Test
    fun `zero overhead when disabled - never calling start installs nothing`() {
        val source = FakeSource()
        val scope = CoroutineScope(SupervisorJob())
        // Construct but never start().
        VizcoreClient(
            httpClient = HttpClient(),
            backendUrl = "",
            sessionId = "x",
            token = "t",
            session = VizSession("x"),
            source = source,
            scope = scope,
        )
        assertFalse(source.isRunning, "the source must not be started when start() is never called")
        scope.cancel()
    }

    @Test
    fun `stop stops the source and closes the WS - no leak`() =
        testApplication {
            application {
                install(ServerWebSockets)
                routing { testIngestRoute() }
            }
            val serverSession = SessionManager.createSession("stop-app")
            val serverId = serverSession.sessionId

            val testClient = createClient { install(WebSockets) }
            val source = FakeSource()
            val scopeJob = SupervisorJob()
            val scope = CoroutineScope(scopeJob)
            val localSession = VizSession(sessionId = serverId)
            // Inject our own buffer so we can assert it is closed after stop().
            val buffer = OutboundBuffer()
            val client =
                VizcoreClient(
                    httpClient = testClient,
                    backendUrl = "",
                    sessionId = serverId,
                    token = "t",
                    session = localSession,
                    source = source,
                    scope = scope,
                    buffer = buffer,
                )
            client.start()
            // Let the feed subscribe (ProjectionService + feed = 2) so the buffer is
            // live, then the feed-then-source ordering drives the source.
            localSession.bus.subscriptionCount.first { it >= 2 }
            pollFor { if (source.isRunning) Unit else null }
            assertTrue(source.isRunning, "source runs after start()")

            client.stop()
            assertFalse(source.isRunning, "source stopped after stop()")
            assertFalse(scopeJob.isActive, "client scope cancelled after stop()")
            // The buffer is closed on stop(): a fresh drain returns immediately
            // (channel closed-for-receive) rather than suspending forever — proving the
            // feed collector + any active drain terminate with no leak.
            withTimeout(1_000L) { buffer.drain { } }
        }
}

/** Poll a producer up to a bounded number of times until it yields a non-null value. */
internal suspend fun <T> pollFor(
    attempts: Int = 300,
    delayMs: Long = 10L,
    producer: () -> T?,
): T {
    repeat(attempts) {
        producer()?.let { return it }
        delay(delayMs)
    }
    error("Condition never satisfied after $attempts attempts")
}
