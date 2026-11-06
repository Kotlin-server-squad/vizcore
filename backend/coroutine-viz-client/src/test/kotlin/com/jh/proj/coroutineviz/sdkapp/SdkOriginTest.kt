package com.jh.proj.coroutineviz.sdkapp

import com.jh.proj.coroutineviz.client.VizcoreClient
import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.session.source.debugprobes.DebugProbesInstall
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Origin as an app sees it (spec "Origin", P-1), in a JVM of its own with DebugProbes installed before Ktor runs, so the
 * SDK's leftovers are created — and captured — by this client: Ktor's process-wide nonce generator is VIZCORE (created
 * by the SDK's connection), the socket reader and writer stay APP (no SDK frame on their creation stack), and the app's
 * coroutine is APP. Lives outside the SDK's packages, as an app does.
 */
@Tag("fresh-jvm")
class SdkOriginTest {
    @Test
    fun `the app's coroutines are APP, the SDK's leftovers as verified`() {
        DebugProbesInstall.acquire()
        var server: FakeBackend? = null
        var client: VizcoreClient? = null
        val app = Job()
        try {
            val backend = FakeBackend().also { server = it }
            val started = VizcoreClient.start(appName = "origin", backendUrl = "http://localhost:${backend.port}", token = "t")
            client = started
            CoroutineScope(Dispatchers.Default + app).launch(CoroutineName("app-work")) { awaitCancellation() }
            listOf("app-work", "nonce-generator", "cio-from-nio-reader", "cio-to-nio-writer").forEach(backend::awaitLabel)
            backend.awaitKind("PollWatermark")

            val byLabel = backend.created.groupBy { it.label }
            assertEquals(CoroutineOrigin.APP, byLabel.getValue("app-work").single().origin)
            assertEquals(setOf(CoroutineOrigin.VIZCORE), byLabel.getValue("nonce-generator").map { it.origin }.toSet())
            assertEquals(setOf(CoroutineOrigin.APP), byLabel.getValue("cio-from-nio-reader").map { it.origin }.toSet())
            assertEquals(setOf(CoroutineOrigin.APP), byLabel.getValue("cio-to-nio-writer").map { it.origin }.toSet())
            assertTrue(backend.created.none { it.origin == null }, "every DebugProbes coroutine says its origin")
        } finally {
            app.cancel()
            client?.stop()
            server?.close()
            DebugProbesInstall.release()
        }
    }
}
