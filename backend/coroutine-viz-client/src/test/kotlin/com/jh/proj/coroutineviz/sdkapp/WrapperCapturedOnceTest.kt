package com.jh.proj.coroutineviz.sdkapp

import com.jh.proj.coroutineviz.client.VizcoreClient
import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.session.source.debugprobes.DebugProbesInstall
import com.jh.proj.coroutineviz.wrappers.VizScope
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A coroutine an app launches through `VizScope` into the client's session is captured once, by its wrapper events, as
 * the app's; a coroutine its body launches with the body's context inherits the wrapper's element and is captured by
 * DebugProbes (spec "Origin", P-3).
 */
@Tag("fresh-jvm")
class WrapperCapturedOnceTest {
    @Test
    fun `vizLaunch and vizAsync are captured once and are the app's`() {
        DebugProbesInstall.acquire()
        var server: FakeBackend? = null
        var client: VizcoreClient? = null
        var scope: VizScope? = null
        try {
            val backend = FakeBackend().also { server = it }
            val started = VizcoreClient.start(appName = "once", backendUrl = "http://localhost:${backend.port}", token = "t")
            client = started
            val viz = VizScope(started.session).also { scope = it }
            runBlocking {
                viz.vizLaunch(label = "wrapped-launch", context = CoroutineName("wrapped-launch")) {
                    coroutineScope {
                        launch(CoroutineName("inheriting-child")) { awaitCancellation() }
                    }
                }
                viz.vizAsync(label = "wrapped-async", context = CoroutineName("wrapped-async")) { awaitCancellation() }
            }
            listOf("wrapped-launch", "wrapped-async", "inheriting-child").forEach(backend::awaitLabel)
            Thread.sleep(500) // several more polls: a dp- duplicate would have arrived by now

            val byLabel = backend.created.groupBy { it.label }
            for (label in listOf("wrapped-launch", "wrapped-async")) {
                val captured = byLabel.getValue(label)
                assertEquals(1, captured.size, "$label captured once: $captured")
                assertTrue(captured.single().coroutineId.startsWith("coroutine-"), "by its wrapper events")
                assertEquals(CoroutineOrigin.APP, captured.single().origin)
            }
            val child = byLabel.getValue("inheriting-child").single()
            assertTrue(child.coroutineId.startsWith("dp-"), "captured by DebugProbes: $child")
            val wrapperId = byLabel.getValue("wrapped-launch").single().coroutineId
            assertEquals(wrapperId, child.parentCoroutineId, "and keeps the wrapper as its parent")
        } finally {
            scope?.cancel()
            client?.stop()
            server?.close()
            DebugProbesInstall.release()
        }
    }
}
