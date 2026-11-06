package com.jh.proj.coroutineviz.sdkapp

import com.jh.proj.coroutineviz.client.VizcoreClient
import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.session.source.debugprobes.DebugProbesInstall
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.debug.DebugProbes
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Without creation stacks (another party installed DebugProbes first without them, #217) a coroutine's origin is
 * UNKNOWN (P-2). DebugProbes read the flag at each coroutine's creation, so turning it off after the install is that case.
 */
@Tag("fresh-jvm")
class NoCreationStacksTest {
    @Test
    fun `a coroutine created without a creation stack is unknown`() {
        DebugProbesInstall.acquire()
        var server: FakeBackend? = null
        var client: VizcoreClient? = null
        val app = Job()
        try {
            val backend = FakeBackend().also { server = it }
            client = VizcoreClient.start(appName = "no-stacks", backendUrl = "http://localhost:${backend.port}", token = "t")
            DebugProbes.enableCreationStackTraces = false
            CoroutineScope(Dispatchers.Default + app).launch(CoroutineName("stackless")) { awaitCancellation() }
            backend.awaitLabel("stackless")
            assertEquals(CoroutineOrigin.UNKNOWN, backend.created.single { it.label == "stackless" }.origin)
        } finally {
            DebugProbes.enableCreationStackTraces = true
            app.cancel()
            client?.stop()
            server?.close()
            DebugProbesInstall.release()
        }
    }
}
