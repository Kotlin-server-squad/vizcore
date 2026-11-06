package com.jh.proj.coroutineviz.sdkapp

import com.jh.proj.coroutineviz.client.VizcoreClient
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.session.source.InstrumentationSource
import com.jh.proj.coroutineviz.session.source.debugprobes.DebugProbesSource
import com.jh.proj.coroutineviz.session.source.debugprobes.VizcoreInternal
import io.ktor.client.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.debug.DebugProbes
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

/**
 * The SDK blocks the app's thread in three places, each in a `runBlocking` coroutine of its own: `start(...)` waits
 * for the session create and then for the client to take the session's events, and `stop()` waits for the last events
 * to go out. A DebugProbes poll that lands during a wait must leave that coroutine out of the session, like every
 * other coroutine the SDK runs (#225).
 *
 * Each test holds one wait open until an observing [DebugProbesSource] has completed two polls while the wait's
 * coroutine exists, so a poll is known to have seen it, then lets it finish and reads what the observer recorded.
 * In a JVM of its own, so no other test's client adds coroutines to what the probes see.
 */
@Tag("fresh-jvm")
class SdkBlockingWaitsTest {
    private val observed = VizSession("observer", keepSummaries = false)
    private val polls = AtomicInteger()
    private val observer =
        DebugProbesSource(session = observed, pollInterval = 10.milliseconds).apply {
            onPollCompleted = { _, _ -> polls.incrementAndGet() }
        }

    private class NoopSource : InstrumentationSource {
        override val sourceId = "noop"
        override val isRunning = false

        override fun start() = Unit

        override fun stop() = Unit
    }

    // Started first: DebugProbes only track coroutines created after they are installed.
    @BeforeEach
    fun observe() = observer.start()

    @AfterEach
    fun stopObserving() = observer.stop()

    @Test
    fun `stop's wait for the last events stays out of the session`() {
        val opened = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val client =
            VizcoreClient(
                httpClient = HttpClient(),
                backendUrl = "",
                sessionId = "waits",
                token = "t",
                session = VizSession("waits"),
                source = NoopSource(),
                scope = CoroutineScope(SupervisorJob() + VizcoreInternal),
                // An open socket that ends only when the test lets it: stop() waits for it.
                transport = { _, lifetime ->
                    lifetime.opened()
                    opened.countDown()
                    release.await()
                },
            )
        client.start()
        assertTrue(opened.await(10, TimeUnit.SECONDS), "the socket opened")
        val stopping = thread(name = "stopping") { client.stop(30_000) }
        holdUntilPolled("awaitDelivery") { release.complete(Unit) }
        stopping.join(10_000)
        assertNoSdkCoroutineRecorded()
    }

    @Test
    fun `start's wait for the client to take the session's events stays out of the session`() {
        // The client's loops run on one thread the test holds, so the client cannot take the events until it lets go.
        val executor = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        executor.execute { gate.await() }
        val client =
            VizcoreClient(
                httpClient = HttpClient(),
                backendUrl = "",
                sessionId = "waits",
                token = "t",
                session = VizSession("waits"),
                source = NoopSource(),
                scope = CoroutineScope(executor.asCoroutineDispatcher() + SupervisorJob() + VizcoreInternal),
                transport = { _, _ -> awaitCancellation() },
            )
        try {
            client.start()
            var captured = false
            val waiting = thread(name = "waiting") { captured = client.awaitCapture(30_000) }
            holdUntilPolled("awaitCapture") { gate.countDown() }
            waiting.join(10_000)
            assertTrue(captured, "the client took the session's events")
            assertNoSdkCoroutineRecorded()
        } finally {
            gate.countDown()
            client.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `start's wait for the session create stays out of the session`() {
        val answer = CountDownLatch(1)
        FakeBackend(beforeSessionCreate = { answer.await(10, TimeUnit.SECONDS) }).use { backend ->
            var client: VizcoreClient? = null
            val starting =
                thread(name = "starting") {
                    client = VizcoreClient.start(appName = "waits", backendUrl = "http://127.0.0.1:${backend.port}", token = "t")
                }
            try {
                holdUntilPolled("sessionAtStart") { answer.countDown() }
                starting.join(10_000)
                assertNoSdkCoroutineRecorded()
            } finally {
                answer.countDown()
                starting.join(10_000)
                client?.stop()
            }
        }
    }

    /** Waits until a coroutine created in the SDK's [function] exists and two polls completed, then calls [release]. */
    private fun holdUntilPolled(
        function: String,
        release: () -> Unit,
    ) {
        try {
            awaitTrue("a coroutine created in $function") {
                DebugProbes.dumpCoroutinesInfo().any { info ->
                    info.creationStackTrace.any { it.fileName == "VizcoreClient.kt" && it.methodName.startsWith(function) }
                }
            }
            val seen = polls.get()
            awaitTrue("two polls while $function waits") { polls.get() >= seen + 2 }
        } finally {
            release()
        }
    }

    private fun assertNoSdkCoroutineRecorded() {
        val leaked =
            observed.store
                .all()
                .filterIsInstance<CoroutineCreated>()
                .filter { it.creationPoint?.fileName in SDK_FILES }
        if (leaked.isNotEmpty()) {
            fail("coroutines the SDK launched reached the session: ${leaked.map { "${it.label} created in ${it.creationPoint}" }}")
        }
    }

    private fun awaitTrue(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    private companion object {
        val SDK_FILES =
            setOf(
                "VizcoreClient.kt",
                "IngestTransport.kt",
                "OutboundBuffer.kt",
                "SessionBootstrap.kt",
            )
    }
}
