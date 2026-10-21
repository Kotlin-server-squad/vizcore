package com.jh.proj.coroutineviz.client

import com.jh.proj.coroutineviz.session.source.debugprobes.DebugProbesInstall
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
    fun `start rejects a poll interval below the minimum before touching the network`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                VizcoreClient.start(appName = "a", backendUrl = "http://localhost:1", token = "t", pollIntervalMillis = 1)
            }
        assertTrue("at least ${VizcoreClient.MIN_POLL_INTERVAL_MILLIS}" in failure.message.orEmpty())
    }
}
