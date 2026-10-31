package com.jh.proj.coroutineviz.sdkapp

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.jh.proj.coroutineviz.client.VizcoreClient
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #254: an app that calls [VizcoreClient.start] during its own startup must start even when the
 * vizcore backend is down, and must show up in vizcore once the backend comes up.
 *
 * Driven like an app: the public `start(...)`, a real connection to a port nothing listens on,
 * then a backend ([FakeBackend]) brought up on that same port while the app keeps running.
 */
class BackendDownAtStartTest {
    @Test
    fun `start returns while the backend is down and the app is streamed once it comes up`() {
        // A port that nothing listens on: every connection to it is refused.
        val port = ServerSocket(0).use { it.localPort }
        val backendUrl = "http://127.0.0.1:$port"
        val warnings = ListAppender<ILoggingEvent>().apply { start() }
        val clientLogger = LoggerFactory.getLogger(VizcoreClient::class.java) as Logger
        clientLogger.addAppender(warnings)
        var client: VizcoreClient? = null
        var backend: FakeBackend? = null
        val app = Job()
        try {
            // Before #254 this threw IllegalStateException("Session-create failed after 5 attempts ...").
            val started =
                VizcoreClient.start(appName = "down-at-start", backendUrl = backendUrl, token = "t", correlation = "corr-254")
            client = started

            assertNull(started.sessionIdOrNull, "no session can exist while the backend is down")
            assertEquals("", started.currentSessionId, "currentSessionId is empty until a session exists")
            val warning = warnings.list.firstOrNull { it.level == Level.WARN }
            assertTrue(warning != null, "start(...) must log a WARN; logged: ${warnings.list.map { it.formattedMessage }}")
            assertTrue(backendUrl in warning.formattedMessage, "the WARN names the backend: ${warning.formattedMessage}")
            assertTrue("Connection refused" in warning.formattedMessage, "the WARN gives the cause: ${warning.formattedMessage}")

            // The app's own work, started AND finished while vizcore is still down: it can only reach
            // the backend through the events the client buffered during the outage.
            CoroutineScope(Dispatchers.Default + app).launch(CoroutineName("app-work")) { awaitCancellation() }
            val work = awaitLocal(started) { it is CoroutineCreated && it.label == "app-work" } as CoroutineCreated
            app.cancel()
            awaitLocal(started) {
                (it is CoroutineCompleted && it.coroutineId == work.coroutineId) ||
                    (it is CoroutineCancelled && it.coroutineId == work.coroutineId)
            }

            val up = FakeBackend(port = port, sessionId = "down-at-start-1").also { backend = it }
            up.awaitLabel("app-work")

            assertEquals("down-at-start-1", started.sessionIdOrNull)
            assertEquals("down-at-start-1", started.currentSessionId)
            val create = up.sessionCreates.single()
            assertTrue("correlation=corr-254" in create, "the background create keeps the correlation: $create")
        } finally {
            app.cancel()
            client?.stop()
            backend?.close()
            clientLogger.detachAppender(warnings)
        }
    }

    /** Waits until the client's local session has recorded an event matching [match], and returns it. */
    private fun awaitLocal(
        client: VizcoreClient,
        match: (VizEvent) -> Boolean,
    ): VizEvent {
        val deadline = System.currentTimeMillis() + 15_000
        while (true) {
            val found =
                client.session.store
                    .all()
                    .firstOrNull(match)
            if (found != null) return found
            check(System.currentTimeMillis() < deadline) { "no such event in the local session" }
            Thread.sleep(50)
        }
    }
}
