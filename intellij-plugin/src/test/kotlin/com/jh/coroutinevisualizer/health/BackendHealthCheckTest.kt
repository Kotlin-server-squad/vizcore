package com.jh.coroutinevisualizer.health

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Headless unit proofs for [BackendHealthCheck] (IDE-01, D-05) — logic only, no display.
 *
 * - [validate] rejects non-http(s) schemes and malformed URLs (SSRF guard, V5) and
 *   accepts the default `http://localhost:8080`.
 * - [BackendHealthCheck.check] returns [BackendHealthCheck.HealthStatus.Down] carrying the
 *   exact actionable [BackendHealthCheck.DOWN_MESSAGE] when pointed at an unused loopback
 *   port, and [BackendHealthCheck.HealthStatus.Up] against a tiny in-test server answering 200.
 */
class BackendHealthCheckTest {
    @Test
    fun `validate rejects a non-http scheme`() {
        val result = BackendHealthCheck.validate("file:///etc/passwd")
        assertIs<BackendHealthCheck.ValidationResult.Invalid>(result, "non-http(s) scheme must be rejected (SSRF guard)")
    }

    @Test
    fun `validate rejects a malformed URL`() {
        val result = BackendHealthCheck.validate("ht!tp://%%%not a url")
        assertIs<BackendHealthCheck.ValidationResult.Invalid>(result, "malformed URL must be rejected")
    }

    @Test
    fun `validate rejects an http URL with no host`() {
        val result = BackendHealthCheck.validate("http://")
        assertIs<BackendHealthCheck.ValidationResult.Invalid>(result, "http URL with no host must be rejected")
    }

    @Test
    fun `validate accepts the default backend URL`() {
        val result = BackendHealthCheck.validate("http://localhost:8080")
        assertIs<BackendHealthCheck.ValidationResult.Valid>(result, "the default backend URL must be accepted")
        assertTrue(BackendHealthCheck.isValid("https://example.com:8443/api"), "an https URL with host must be accepted")
    }

    @Test
    fun `check returns Down with the actionable message when the backend is unreachable`() {
        val port = freeLoopbackPort()
        val status = BackendHealthCheck.check("http://127.0.0.1:$port")
        val down = assertIs<BackendHealthCheck.HealthStatus.Down>(status, "an unbound port must yield Down")
        assertEquals(BackendHealthCheck.DOWN_MESSAGE, down.message, "the Down state must carry the exact actionable message constant")
    }

    @Test
    fun `check returns Down for an invalid URL without dialing`() {
        val status = BackendHealthCheck.check("file:///etc/passwd")
        assertIs<BackendHealthCheck.HealthStatus.Down>(status, "an invalid URL must be Down, never dialed")
    }

    @Test
    fun `check returns Up when the backend answers 200`() {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val body = "ok".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val status = BackendHealthCheck.check("http://127.0.0.1:${server.address.port}")
            assertIs<BackendHealthCheck.HealthStatus.Up>(status, "a server answering 200 must yield Up")
            assertTrue(BackendHealthCheck.isReachable("http://127.0.0.1:${server.address.port}"), "isReachable agrees with check()")
        } finally {
            server.stop(0)
        }
    }

    /** Bind then immediately release a loopback port, returning a number nothing is listening on. */
    private fun freeLoopbackPort(): Int = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
}
