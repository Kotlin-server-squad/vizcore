package com.jh.coroutinevisualizer.api

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VizcoreApiClientTest {
    private var backend: HttpServer? = null

    private fun start(handler: (String) -> Pair<Int, String>): String {
        val b = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        b.createContext("/api") { ex ->
            val (code, body) = handler(ex.requestURI.toString())
            val bytes = body.toByteArray()
            ex.responseHeaders.set("Content-Type", "application/json")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        b.start()
        backend = b
        return "http://127.0.0.1:${b.address.port}"
    }

    @AfterEach fun tearDown() {
        backend?.stop(0)
    }

    @Test fun `resolve returns sessionId on 200`() {
        val url = start { 200 to """{"sessionId":"s-1"}""" }
        assertEquals("s-1", VizcoreApiClient(url).resolve("corr-x"))
    }

    @Test fun `resolve returns null on 404`() {
        val url = start { 404 to """{"error":"not found"}""" }
        assertNull(VizcoreApiClient(url).resolve("corr-x"))
    }

    @Test fun `hierarchy parses array`() {
        val url = start { 200 to """[{"id":"c1","name":"request-1","scopeId":"sc","state":"RUNNING","children":[],"jobId":"j1"}]""" }
        val nodes = VizcoreApiClient(url).hierarchy("s-1")
        assertEquals(1, nodes.size)
        assertEquals("request-1", nodes[0].name)
        assertEquals("RUNNING", nodes[0].state)
    }

    @Test fun `hierarchy encodes a spacey sessionId as percent-20 path segments never plus`() {
        // Ktor's server decodes path segments with plusIsSpace=false: a form-encoded '+' stays a
        // literal '+', so 'demo+boot+jar-123' looks up the wrong session and 404s on every poll.
        var captured: String? = null
        val url =
            start { uri ->
                captured = uri
                200 to "[]"
            }
        VizcoreApiClient(url).hierarchy("demo boot jar-123")
        assertEquals("/api/sessions/demo%20boot%20jar-123/hierarchy", captured)
    }

    @Test fun `metrics parses leaks`() {
        val url =
            start {
                200 to
                    """{"active":2,"peak":5,"dispatcherUtilization":{"IO":1},"leaks":[{"coroutineId":"c9","aliveMs":12400}],"leakThresholdMs":10000}"""
            }
        val m = VizcoreApiClient(url).metrics("s-1")!!
        assertEquals(2, m.active)
        assertEquals("c9", m.leaks[0].coroutineId)
    }
}
