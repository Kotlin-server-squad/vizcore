package com.jh.proj.coroutineviz.client

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.jh.proj.coroutineviz.session.source.debugprobes.DebugProbesInstall
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The real [VizcoreClient.start] factory, driven in-process. DebugProbes only track
 * coroutines created after installation, and the client starts polling asynchronously,
 * so the factory must have installed the probes by the time it returns: otherwise work
 * an app launches straight after `start(...)` is never seen (the root README example
 * captured nothing).
 */
class VizcoreClientStartTest {
    @Test
    fun `start returns with DebugProbes already installed and stop releases them`() =
        testApplication {
            routing {
                post("/api/sessions") {
                    call.respondText(
                        """{"sessionId":"start-test-1","message":"ok"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.Created,
                    )
                }
            }
            val httpClient = createClient { install(WebSockets) }
            val before = DebugProbesInstall.count()

            // The factory blocks on session creation, so call it off the test host's thread.
            val client =
                withContext(Dispatchers.IO) {
                    VizcoreClient.start(
                        appName = "start-test",
                        backendUrl = "",
                        token = "t",
                        correlation = null,
                        httpClient = httpClient,
                    )
                }
            // Checked immediately, with no waiting: the install must not be left to the
            // asynchronous source start.
            assertEquals(before + 1, DebugProbesInstall.count(), "probes must be installed when start(...) returns")

            client.stop()
            assertEquals(before, DebugProbesInstall.count(), "stop() must release the probe reference")
        }

    @Test
    fun `start returns without a session when the backend never answers the session create`() =
        testApplication {
            // A forwarder or proxy in front of a backend that is down: the request is accepted and
            // never answered (#254).
            routing {
                post("/api/sessions") { awaitCancellation() }
            }
            val httpClient = createClient { install(WebSockets) }
            val warnings = ListAppender<ILoggingEvent>().apply { start() }
            val clientLogger = LoggerFactory.getLogger(VizcoreClient::class.java) as Logger
            clientLogger.addAppender(warnings)
            try {
                val startedAt = System.nanoTime()
                val client =
                    withContext(Dispatchers.IO) {
                        VizcoreClient.start(
                            appName = "silent",
                            backendUrl = "",
                            token = "t",
                            correlation = null,
                            httpClient = httpClient,
                            sessionTimeout = 300.milliseconds,
                        )
                    }
                val tookMillis = (System.nanoTime() - startedAt) / 1_000_000
                try {
                    assertNull(client.sessionIdOrNull)
                    assertTrue(tookMillis < 5_000, "start(...) must give up on a silent backend, took $tookMillis ms")
                    val warning = warnings.list.single { it.level == Level.WARN }
                    assertTrue("no answer within 300 ms" in warning.formattedMessage, warning.formattedMessage)
                } finally {
                    client.stop()
                }
            } finally {
                clientLogger.detachAppender(warnings)
            }
        }

    @Test
    fun `start closes the HTTP client and releases the probes when it fails`() {
        // A start(...) that throws hands no client to the caller, so nothing could ever close what it
        // opened. An interrupted calling thread is one way it still throws after the HTTP client exists.
        val httpClient = HttpClient()
        val before = DebugProbesInstall.count()
        val failure = AtomicReference<Throwable?>()
        val caller =
            thread {
                Thread.currentThread().interrupt()
                val result =
                    runCatching {
                        VizcoreClient.start(
                            appName = "interrupted",
                            backendUrl = "http://127.0.0.1:1",
                            token = "t",
                            correlation = null,
                            httpClient = httpClient,
                        )
                    }
                failure.set(result.exceptionOrNull())
                result.getOrNull()?.stop()
            }
        caller.join(10_000)

        assertTrue(failure.get() is InterruptedException, "expected an InterruptedException, got ${failure.get()}")
        assertFalse(httpClient.coroutineContext.job.isActive, "the HTTP client must be closed")
        assertEquals(before, DebugProbesInstall.count(), "no probe reference may be left behind")
    }

    @Test
    fun `start rejects a poll interval below the minimum before touching the network`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                VizcoreClient.start(appName = "a", backendUrl = "http://localhost:1", token = "t", pollIntervalMillis = 1)
            }
        assertTrue("at least ${VizcoreClient.MIN_POLL_INTERVAL_MILLIS}" in failure.message.orEmpty())
    }
}
