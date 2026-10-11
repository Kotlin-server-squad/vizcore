package com.jh.proj.coroutineviz.client

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.session.source.InstrumentationSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.PolymorphicSerializer
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * #129, client half: the client must SURVIVE losing its server session, and must not
 * spin when the backend accepts and instantly drops every socket.
 *
 * Before this, [VizcoreClient] retried one fixed session id forever — once the backend
 * had evicted or forgotten it, every reconnect hit a session that could never come
 * back and the app streamed into nothing for the rest of its run. And because the
 * reconnect loop reset its attempt counter on any clean return, an
 * accepted-then-immediately-closed socket (exactly what a refusing route does) became
 * a 500 ms hot loop instead of a backoff.
 *
 * The recovery case drives the REAL [stream] transport against an in-process backend
 * (real WS I/O, like the round-trip test in [VizcoreClientTest]); the backoff cases
 * are deterministic — asserted on [Backoff] directly, or on `runTest` virtual time
 * with a fake transport.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VizcoreClientRecoveryTest {

    private class NoopSource : InstrumentationSource {
        override val sourceId = "noop"
        override val isRunning = false

        override fun start() = Unit

        override fun stop() = Unit
    }

    /** Source whose [isRunning] is the gate the round-trip tests poll on. */
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

    /** Zero-jitter backoff (Random fixed to 0.0) so delays are exact. */
    private object FixedZeroRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0

        override fun nextDouble(): Double = 0.0
    }

    private fun event(sessionId: String, n: Int): VizEvent =
        CoroutineCreated(
            sessionId = sessionId,
            seq = n.toLong(),
            tsNanos = n.toLong(),
            coroutineId = "c$n",
            jobId = "j$n",
            parentCoroutineId = null,
            scopeId = "s$n",
            label = "e$n",
        )

    /**
     * The backend's refusal shape: an ingest socket for any id other than the one
     * session the server currently has is accepted and then closed with
     * `VIOLATED_POLICY` "Session not found" — what the real route does for an unknown
     * (or evicted) session. The live id is published by [liveId] and only set once the
     * session-create route below has actually created a session.
     */
    private fun Route.recoveringIngestRoute(liveId: AtomicReference<String?>) {
        webSocket("/api/sessions/{id}/ingest") {
            val id = call.parameters["id"]!!
            val session = liveId.get()?.takeIf { it == id }?.let { SessionManager.getSession(it) }
            if (session == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Session not found"))
                return@webSocket
            }
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val decoded = appJson.decodeFromString(PolymorphicSerializer(VizEvent::class), frame.readText())
                    session.send(decoded)
                }
            }
        }
    }

    /** `POST /api/sessions` — the bootstrap route the client's recovery path calls. */
    private fun Route.createSessionRoute(liveId: AtomicReference<String?>, created: AtomicInteger) {
        post("/api/sessions") {
            val name = call.request.queryParameters["name"] ?: "recovery-app"
            val session = SessionManager.createSession(name)
            liveId.set(session.sessionId)
            created.incrementAndGet()
            call.respondText(
                """{"sessionId":"${session.sessionId}","message":"created"}""",
                ContentType.Application.Json,
                HttpStatusCode.Created,
            )
        }
    }

    @Test
    fun `a session the backend no longer has is replaced and buffered events resume on the new one`() =
        testApplication {
            val liveId = AtomicReference<String?>(null)
            val created = AtomicInteger(0)
            application {
                install(ServerWebSockets)
                routing {
                    recoveringIngestRoute(liveId)
                    createSessionRoute(liveId, created)
                }
            }

            // The id the client starts with: the backend has no such session (liveId is
            // still null), so EVERY ingest attempt for it is refused "Session not found".
            val goneId = "gone-session"
            val testClient = createClient { install(WebSockets) }
            val localSession = VizSession(sessionId = goneId)
            val scope = CoroutineScope(SupervisorJob())
            val buffer = OutboundBuffer()
            val feedGate = FakeSource()
            val attempts = AtomicInteger(0)

            // REAL transport, pointed at whatever id the loop currently holds — a recovery
            // only takes effect if the loop re-reads the id, so the lambda must use its
            // parameter and never a captured constant.
            val transport: suspend (String) -> Unit = { id ->
                attempts.incrementAndGet()
                stream(testClient, "", id, "test-token", buffer)
            }

            val client =
                VizcoreClient(
                    httpClient = testClient,
                    backendUrl = "",
                    sessionId = goneId,
                    token = "test-token",
                    session = localSession,
                    source = feedGate,
                    scope = scope,
                    backoff = Backoff(baseMillis = 50L, capMillis = 200L),
                    appName = "recovery-app",
                    buffer = buffer,
                    transport = transport,
                )
            client.start()

            // Await the feed's bus subscription (feed-then-source: the source only runs
            // once the feed is subscribed), so events emitted below are provably retained
            // by the lifetime buffer rather than lost to the replay=0 bus.
            pollFor { if (feedGate.isRunning) Unit else null }

            // The loop's first socket is refused "Session not found"; it then bootstraps a
            // replacement via POST /api/sessions and points the transport at that id.
            pollFor { if (client.currentSessionId != goneId) Unit else null }
            val replacementId = client.currentSessionId
            val serverSession = SessionManager.getSession(replacementId)!!

            // Emitted AFTER the refusal and BEFORE the replacement socket opens — the
            // recovery runs before the loop's backoff delay, so this is the disconnected
            // window with no drain attached. Emitting earlier would race the refused
            // socket's own drain, which may lose the ONE frame it had already dequeued
            // when the close cancelled it (OutboundBuffer's documented at-most-once
            // caveat) and make the count non-deterministic.
            val total = 20
            repeat(total) { localSession.send(event(goneId, it)) }

            pollFor { if (serverSession.store.all().size >= total) Unit else null }

            assertNotEquals(goneId, replacementId, "the client must stop streaming into the dead id")
            assertEquals(replacementId, liveId.get(), "the replacement is the session the backend created")
            assertEquals(
                total,
                serverSession.store.all().size,
                "every event buffered during the outage must arrive on the replacement session",
            )
            assertTrue(attempts.get() >= 2, "the refused attempt must have been followed by a reconnect")
            assertEquals(1, created.get(), "recovery must create exactly one replacement session")

            client.stop()
            scope.cancel()
        }

    @Test
    fun `backoff delays grow monotonically and cap at 30s`() {
        val backoff = Backoff(baseMillis = 500L, capMillis = 30_000L, random = FixedZeroRandom)

        val delays = (0..20).map { backoff.delayMillis(it) }

        delays.zipWithNext { previous, next ->
            assertTrue(next >= previous, "delay must never shrink as attempts grow: $previous then $next")
        }
        assertTrue(delays.all { it <= 30_000L }, "no delay may exceed the 30s cap")
        assertEquals(500L, delays.first(), "the first retry waits the base delay")
        assertEquals(30_000L, delays.last(), "repeated failures settle at the cap, they do not grow forever")
        assertEquals(30_000L, backoff.delayMillis(100), "the cap holds for an arbitrarily long outage")
    }

    @Test
    fun `an immediate clean close does not reset the backoff`() =
        runTest {
            // Transport returns at once on every attempt: the backend accepted the socket
            // and dropped it immediately. Nothing streamed, so nothing may reset the
            // attempt counter — the delays must keep doubling instead of pinning at 500ms.
            val callTimes = CopyOnWriteArrayList<Long>()
            val scope = CoroutineScope(coroutineContext + Job(coroutineContext.job))
            val transport: suspend (String) -> Unit = { callTimes += testScheduler.currentTime }

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
                    clock = { testScheduler.currentTime },
                )
            client.start()
            // 0 + 500 + 1000 + 2000 + 4000 = 7500 — enough virtual time for five attempts.
            testScheduler.advanceTimeBy(7_600L)
            testScheduler.runCurrent()
            client.stop()
            testScheduler.advanceUntilIdle()

            assertTrue(callTimes.size >= 5, "expected five connect attempts, got ${callTimes.size}")
            val gaps = callTimes.take(5).zipWithNext { previous, next -> next - previous }
            assertEquals(
                listOf(500L, 1_000L, 2_000L, 4_000L),
                gaps,
                "an immediate close must keep the backoff doubling, not reset it to the base delay",
            )
        }
}
