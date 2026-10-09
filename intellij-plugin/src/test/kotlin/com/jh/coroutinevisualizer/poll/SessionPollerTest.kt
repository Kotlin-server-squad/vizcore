package com.jh.coroutinevisualizer.poll

import com.jh.coroutinevisualizer.api.VizcoreApiClient
import com.jh.coroutinevisualizer.model.ProblemCategory
import com.jh.coroutinevisualizer.model.SessionModel
import com.jh.coroutinevisualizer.model.SuspensionTracker
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionPollerTest {
    private var backend: HttpServer? = null
    private val resolveCalls = AtomicInteger(0)

    private fun startBackend(
        resolveAfter: Int,
        nodeState: String = "RUNNING",
    ): String {
        resolveCalls.set(0)
        val b = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        b.createContext("/api/sessions/resolve") { ex ->
            val n = resolveCalls.incrementAndGet()
            val (code, body) = if (n >= resolveAfter) 200 to """{"sessionId":"s-1"}""" else 404 to """{"error":"x"}"""
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        b.createContext("/api/sessions/s-1/hierarchy") { ex ->
            val body =
                """[{"id":"a","name":"req","scopeId":"sc","state":"$nodeState","children":[],"jobId":"j"}]""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        b.createContext("/api/sessions/s-1/metrics") { ex ->
            val body = """{"active":1,"peak":1,"dispatcherUtilization":{"Default":1},"leaks":[],"leakThresholdMs":10000}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        b.start()
        backend = b
        return "http://127.0.0.1:${b.address.port}"
    }

    @AfterEach fun tearDown() {
        backend?.stop(0)
    }

    @Test fun `resolves then polls and delivers a model`() {
        val url = startBackend(resolveAfter = 2)
        val latch = CountDownLatch(1)
        val received = AtomicReference<SessionModel?>(null)
        val poller = SessionPoller(VizcoreApiClient(url), intervalMs = 30)
        poller.start("corr-x", onModel = {
            received.set(it)
            latch.countDown()
        }, onError = {})
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "expected a model within 5s")
            val m = assertNotNull(received.get())
            assertEquals(1, m.tiles.active)
            assertEquals(1, m.tiles.peak)
        } finally {
            poller.stop()
        }
    }

    @Test fun `freeze stops further deliveries`() {
        val url = startBackend(resolveAfter = 1)
        val count = AtomicInteger(0)
        val poller = SessionPoller(VizcoreApiClient(url), intervalMs = 30)
        poller.start("c", onModel = { count.incrementAndGet() }, onError = {})
        try {
            Thread.sleep(200)
            poller.freeze()
            val afterFreeze = count.get()
            Thread.sleep(200)
            assertTrue(count.get() <= afterFreeze + 1, "freeze should stop new deliveries (allowing at most one in-flight)")
        } finally {
            poller.stop()
        }
    }

    @Test fun `setInterval reschedules without dropping the session`() {
        val url = startBackend(resolveAfter = 1)
        val poller = SessionPoller(VizcoreApiClient(url), intervalMs = 30)
        val firstModel = CountDownLatch(1)
        val afterReschedule = CountDownLatch(2)
        poller.start("corr", onModel = {
            firstModel.countDown()
            afterReschedule.countDown()
        }, onError = {})
        try {
            assertTrue(firstModel.await(5, TimeUnit.SECONDS), "expected a first model")
            assertEquals("s-1", poller.currentSessionId())
            val resolvesBefore = resolveCalls.get()
            poller.setInterval(50)
            assertTrue(afterReschedule.await(5, TimeUnit.SECONDS), "expected another model after the reschedule")
            assertEquals("s-1", poller.currentSessionId(), "sessionId must be retained across the reschedule")
            assertEquals(resolvesBefore, resolveCalls.get(), "reschedule must NOT re-resolve the session (Pitfall 5)")
        } finally {
            poller.stop()
        }
    }

    @Test fun `long suspended ids flow into the delivered model`() {
        val url = startBackend(resolveAfter = 1, nodeState = "SUSPENDED")
        val latch = CountDownLatch(1)
        val received = AtomicReference<SessionModel?>(null)
        val poller =
            SessionPoller(
                VizcoreApiClient(url),
                intervalMs = 30,
                tracker = SuspensionTracker(thresholdNanos = 0),
            )
        poller.start("c", onModel = {
            received.set(it)
            latch.countDown()
        }, onError = {})
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "expected a model within 5s")
            val problems = assertNotNull(received.get()).problems
            assertTrue(
                problems.any { it.category == ProblemCategory.LONG_SUSPENDED && it.coroutineId == "a" },
                "expected a LONG_SUSPENDED problem for id a (D-10 wiring through tick)",
            )
        } finally {
            poller.stop()
        }
    }

    @Test fun `setInterval before start only records the interval`() {
        val url = startBackend(resolveAfter = 1)
        val poller = SessionPoller(VizcoreApiClient(url), intervalMs = 30)
        poller.setInterval(100)
        assertNull(poller.currentSessionId())
    }
}
