package com.jh.proj.coroutineviz.sdkapp

import com.jh.proj.coroutineviz.client.VizcoreClient
import com.jh.proj.coroutineviz.session.source.debugprobes.DebugProbesInstall
import com.jh.proj.coroutineviz.session.source.debugprobes.VizcoreInternal
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.debug.CoroutineInfo
import kotlinx.coroutines.debug.DebugProbes
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.fail

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
                    "VizcoreClient.kt",
                    "IngestTransport.kt",
                    "OutboundBuffer.kt",
                    "DebugProbesSource.kt",
                    "EventBus.kt",
                    "ProjectionService.kt",
                    "MetricsProjection.kt",
                    "JobStatusMonitor.kt",
                )
            val leaked = backend.created.filter { it.fileName in sdkFiles }
            if (leaked.isNotEmpty()) {
                fail(
                    leakReport("coroutines the SDK launched", leaked, backend.created) { info ->
                        info.creationStackTrace.firstOrNull { it.fileName in sdkFiles }
                    },
                )
            }
            // Everything else Ktor runs for the SDK's connection is left out. The SDK's client times its requests
            // with the HttpTimeout plugin ("request-timeout", on the client's dispatcher), not with the engine's
            // GlobalScope timer ("cio-request-timeout"), so neither may show.
            val stillVisible = setOf("cio-from-nio-reader", "cio-to-nio-writer", "nonce-generator")
            val ktorNames = setOf("selector", "ktor-cio-context", "call-context", "request-timeout")
            val ktorPrefixes = listOf("ws-", "cio-", "raw-ws")
            val ktorLeaked =
                backend.created.filter { c ->
                    val label = c.label ?: return@filter false
                    label !in stillVisible && (label in ktorNames || ktorPrefixes.any { label.startsWith(it) })
                }
            val ktorLabels = ktorLeaked.mapNotNull { it.label }.toSet()
            if (ktorLeaked.isNotEmpty()) {
                fail(
                    leakReport("Ktor coroutines of the SDK's connection", ktorLeaked, backend.created) { info ->
                        info.creationStackTrace.firstOrNull().takeIf { info.context[CoroutineName]?.name in ktorLabels }
                    },
                )
            }
            assertTrue(backend.created.any { it.label == "app-work" })
        } finally {
            app.cancel()
            onSessionScope?.cancel()
            client?.stop()
            server?.close()
            DebugProbesInstall.release()
        }
    }

    /**
     * Says what leaked and whose it is: each coroutine as the session got it (name, creation frame, dispatcher, the
     * names up its parent chain), then the coroutines alive in this JVM that [frameOf] picks (it returns the frame to
     * show, or null to skip one), with their context: scope name, Job, dispatcher, and whether it carries
     * VizcoreInternal.
     */
    private fun leakReport(
        what: String,
        leaked: List<Created>,
        all: List<Created>,
        frameOf: (CoroutineInfo) -> StackTraceElement?,
    ): String {
        val byId = all.associateBy { it.coroutineId }
        val sent =
            leaked.joinToString("\n") { c ->
                val parents = generateSequence(c.parentCoroutineId?.let(byId::get)) { p -> p.parentCoroutineId?.let(byId::get) }
                "  ${c.label ?: "(no name)"} [${c.coroutineId}] created in ${c.function} (${c.fileName}:${c.lineNumber}), " +
                    "origin ${c.origin}, dispatcher ${c.scopeId}, parents ${parents.map { it.label ?: it.coroutineId }.toList()}"
            }
        val live =
            DebugProbes.dumpCoroutinesInfo().mapNotNull { info ->
                frameOf(info)?.let { frame ->
                    "  $frame, state ${info.state}, context ${info.context}, " +
                        "VizcoreInternal ${info.context[VizcoreInternal.Key] != null}, root job ${rootOf(info.job)}"
                }
            }
        return "$what reached the session:\n$sent\n" +
            "matching coroutines alive in this JVM:\n${live.joinToString("\n").ifEmpty { "  (none)" }}"
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun rootOf(job: Job?): Job? = job?.let { generateSequence(it) { j -> j.parent }.last() }
}
