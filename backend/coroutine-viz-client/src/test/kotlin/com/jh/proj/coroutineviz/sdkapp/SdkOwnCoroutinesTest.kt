package com.jh.proj.coroutineviz.sdkapp

import com.jh.proj.coroutineviz.client.VizcoreClient
import com.jh.proj.coroutineviz.session.source.debugprobes.DebugProbesInstall
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The SDK leaves its own coroutines out of the app's session (#225): its client loops, the
 * DebugProbes poll loop, the session projections, and the coroutines Ktor runs for its
 * connection. The test lives outside the SDK's packages, as an app does.
 *
 * The fake backend ([FakeBackend]) is a plain socket server on threads, so it adds no coroutines of
 * its own, and DebugProbes are installed before the client exists, so the client's startup is
 * observed too.
 */
class SdkOwnCoroutinesTest {
    @Test
    fun `the session receives the app's coroutines and not the SDK's own`() {
        DebugProbesInstall.acquire()
        var server: FakeBackend? = null
        var client: VizcoreClient? = null
        val app = Job()
        var onSessionScope: Job? = null
        try {
            val backend = FakeBackend().also { server = it }
            val started = VizcoreClient.start(appName = "own", backendUrl = "http://localhost:${backend.port}", token = "t")
            client = started
            // Launched from a scope the app owns and from a scope the SDK hands out: both are app work.
            CoroutineScope(Dispatchers.Default + app).launch(CoroutineName("app-work")) { awaitCancellation() }
            onSessionScope = started.session.sessionScope.launch(CoroutineName("app-on-session-scope")) { awaitCancellation() }

            backend.awaitLabel("app-work")
            backend.awaitLabel("app-on-session-scope")
            // A few more polls, so anything the SDK started late would have arrived too.
            Thread.sleep(1_000)

            val sdkFiles =
                setOf(
                    "VizcoreClient.kt", "IngestTransport.kt", "OutboundBuffer.kt", "DebugProbesSource.kt",
                    "EventBus.kt", "ProjectionService.kt", "MetricsProjection.kt", "JobStatusMonitor.kt",
                )
            assertEquals(emptyList(), backend.created.filter { it.fileName in sdkFiles }, "coroutines the SDK launched")
            // Everything else Ktor runs for the SDK's connection is left out.
            val stillVisible = setOf("cio-from-nio-reader", "cio-to-nio-writer", "nonce-generator")
            val ktor =
                backend.created.mapNotNull { it.label }.filter {
                    it.startsWith("ws-") || it.startsWith("cio-") || it.startsWith("raw-ws") ||
                        it in setOf("selector", "ktor-cio-context", "call-context", "nonce-generator")
                }
            assertEquals(emptyList(), ktor.filter { it !in stillVisible }, "Ktor coroutines of the SDK's connection")
            assertTrue(backend.created.any { it.label == "app-work" })
        } finally {
            app.cancel()
            onSessionScope?.cancel()
            client?.stop()
            server?.close()
            DebugProbesInstall.release()
        }
    }
}
