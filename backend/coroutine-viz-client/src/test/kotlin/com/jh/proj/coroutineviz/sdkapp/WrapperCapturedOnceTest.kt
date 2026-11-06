package com.jh.proj.coroutineviz.sdkapp

import com.jh.proj.coroutineviz.client.VizcoreClient
import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.events.SuspensionProvenance
import com.jh.proj.coroutineviz.session.source.debugprobes.DebugProbesInstall
import com.jh.proj.coroutineviz.wrappers.VizScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
                // A plain delay() suspends without a wrapper event: DebugProbes must say so, or it reads as running.
                viz.vizLaunch(label = "wrapped-delay", context = CoroutineName("wrapped-delay")) { delay(60_000) }
                // The body returns at once; its Job waits for the child, which DebugProbes see without the wrapper.
                viz.vizLaunch(label = "short-body", context = CoroutineName("short-body")) {
                    CoroutineScope(currentCoroutineContext()).launch(CoroutineName("outliving-child")) { awaitCancellation() }
                }
            }
            listOf("wrapped-launch", "wrapped-async", "inheriting-child", "wrapped-delay", "short-body", "outliving-child")
                .forEach(backend::awaitLabel)
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

            val shortBody = byLabel.getValue("short-body").single().coroutineId
            assertEquals(shortBody, byLabel.getValue("outliving-child").single().parentCoroutineId, "its parent outlives its body")

            val delayed = byLabel.getValue("wrapped-delay").single()
            assertEquals(1, byLabel.getValue("wrapped-delay").size)
            val deadline = System.currentTimeMillis() + 15_000
            while (backend.suspended.none { it.coroutineId == delayed.coroutineId } && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            val suspension = backend.suspended.firstOrNull { it.coroutineId == delayed.coroutineId }
            assertTrue(suspension != null, "the wrapper coroutine's sampled suspension arrives under its own id")
            assertEquals(SuspensionProvenance.REAL_FRAME, suspension.provenance)
        } finally {
            scope?.cancel()
            client?.stop()
            server?.close()
            DebugProbesInstall.release()
        }
    }
}
