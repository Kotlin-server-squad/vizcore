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
import kotlinx.serialization.PolymorphicSerializer
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * In-process round-trip + lifecycle tests for [VizcoreClient]. These use a SYNTHETIC
 * event pushed through the client's local session (NOT real DebugProbes), so they
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

    @Test
    fun `in-process round-trip - a forwarded event reaches the backend session`() =
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
            val client =
                VizcoreClient(
                    httpClient = testClient,
                    backendUrl = "",
                    sessionId = serverId,
                    token = "test-token",
                    session = localSession,
                    source = FakeSource(),
                    scope = scope,
                )
            client.start()

            val event =
                CoroutineCreated(
                    sessionId = serverId,
                    seq = 0,
                    tsNanos = 1L,
                    coroutineId = "c1",
                    jobId = "j1",
                    parentCoroutineId = null,
                    scopeId = "s1",
                    label = "test",
                )
            // Let the reconnect loop open the WS before we publish; the SharedFlow
            // bus has no replay, so a send before the collector subscribes is lost.
            delay(200)
            localSession.send(event)

            val received = pollFor { serverSession.store.all().firstOrNull() }
            assertEquals(serverId, received.sessionId, "event must carry the SERVER session id (Pitfall 1)")
            assertEquals("CoroutineCreated", received.kind)

            client.stop()
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
            val client =
                VizcoreClient(
                    httpClient = testClient,
                    backendUrl = "",
                    sessionId = serverId,
                    token = "t",
                    session = VizSession(sessionId = serverId),
                    source = source,
                    scope = scope,
                )
            client.start()
            assertTrue(source.isRunning, "source runs after start()")

            client.stop()
            assertFalse(source.isRunning, "source stopped after stop()")
            assertFalse(scopeJob.isActive, "client scope cancelled after stop()")
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
