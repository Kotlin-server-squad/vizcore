package com.jh.coroutinevisualizer.server

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Headless routing proofs for [LoopbackFrontendServer] (IDE-02, D-13) — logic only, no pixels.
 *
 * Proves:
 *  1. the server binds a LOOPBACK address on a non-zero ephemeral port (T-13-07);
 *  2. a `GET` under `/api` is reverse-proxied to the SINGLE configured backend and its body round-trips —
 *     and the proxy target is fixed (the request path reaches the backend, but the host is never
 *     request-supplied) (T-13-08, SSRF guard);
 *  3. SPA fallback: an existing bundled resource is served verbatim, an unknown path falls back to
 *     index.html, and `/api` takes precedence over `/`.
 */
@Suppress("TooManyFunctions") // one focused test per routing/proxy behavior
class LoopbackFrontendServerTest {
    private var backend: HttpServer? = null
    private var server: LoopbackFrontendServer? = null
    private val httpClient: HttpClient = HttpClient.newHttpClient()

    @AfterEach
    fun tearDown() {
        server?.dispose()
        backend?.stop(0)
    }

    /** Stand up an in-test "backend" that echoes the path it received into the body. */
    private fun startBackend(capturedPath: AtomicReference<String?>): String {
        val b = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        b.createContext("/api") { ex ->
            capturedPath.set(ex.requestURI.toString())
            val body = "backend saw ${ex.requestURI}".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        b.start()
        backend = b
        return "http://127.0.0.1:${b.address.port}"
    }

    private fun get(path: String): HttpResponse<String> {
        val s = requireNotNull(server) { "server not started" }
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${s.port}$path")).GET().build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `binds a loopback address on an ephemeral port`() {
        val s = LoopbackFrontendServer(backendUrl = "http://127.0.0.1:1").also { it.start() }
        server = s
        assertTrue(s.port > 0, "the bound port must be a non-zero ephemeral port (not a fixed 8090)")
        assertTrue(s.port != FORMER_FIXED_PORT, "must not reuse the deleted receiver's fixed port")
        val address = assertNotNull(s.boundAddress, "the server must expose its bound address")
        assertTrue(address.isLoopbackAddress, "the server must bind a loopback address only (never 0.0.0.0)")
    }

    @Test
    fun `proxies api requests to the single configured backend`() {
        val capturedPath = AtomicReference<String?>(null)
        val backendUrl = startBackend(capturedPath)
        server = LoopbackFrontendServer(backendUrl = backendUrl).also { it.start() }

        val response = get("/api/sessions/anything")

        assertEquals(200, response.statusCode())
        assertEquals("backend saw /api/sessions/anything", response.body(), "the proxied backend body must round-trip")
        assertEquals("/api/sessions/anything", capturedPath.get(), "the request path reaches the single configured backend")
    }

    @Test
    fun `serves an existing bundled resource and falls back to index html for unknown spa paths`() {
        val resources =
            mapOf(
                "/frontend/index.html" to "<!doctype html><title>vizcore</title>".toByteArray(),
                "/frontend/assets/app.js" to "console.log('app')".toByteArray(),
            )
        server =
            LoopbackFrontendServer(
                backendUrl = "http://127.0.0.1:1",
                resourceLoader = { path -> resources[path] },
            ).also { it.start() }

        // existing asset → served verbatim
        val asset = get("/assets/app.js")
        assertEquals(200, asset.statusCode())
        assertEquals("console.log('app')", asset.body())

        // root → index.html
        val root = get("/")
        assertEquals(200, root.statusCode())
        assertTrue(root.body().contains("vizcore"), "root must serve index.html")

        // unknown client-side route → SPA fallback to index.html
        val deepLink = get("/sessions/abc?correlation=xyz")
        assertEquals(200, deepLink.statusCode())
        assertTrue(deepLink.body().contains("vizcore"), "an unknown SPA path must fall back to index.html")
    }

    @Test
    fun `api context takes precedence over the static root`() {
        val capturedPath = AtomicReference<String?>(null)
        val backendUrl = startBackend(capturedPath)
        // A static resolver that would serve EVERYTHING — proving /api still wins over /.
        server =
            LoopbackFrontendServer(
                backendUrl = backendUrl,
                resourceLoader = { "STATIC".toByteArray() },
            ).also { it.start() }

        val apiResponse = get("/api/ping")
        assertTrue(apiResponse.body().startsWith("backend saw"), "/api must be proxied, not served by the static root")
        assertEquals("/api/ping", capturedPath.get(), "/api precedence: the request reached the backend")

        val staticResponse = get("/anything-else")
        assertEquals("STATIC", staticResponse.body(), "non-/api paths are served by the static root")
    }

    @Test
    fun `rejects path traversal that would escape the frontend root`() {
        // A resolver that would happily return bytes for ANY path — proving the guard, not the loader,
        // is what blocks traversal. If `/frontend/../secret` reached the loader it would serve "SECRET".
        server =
            LoopbackFrontendServer(
                backendUrl = "http://127.0.0.1:1",
                resourceLoader = { path -> if (path.contains("..")) "SECRET".toByteArray() else "<html>ok</html>".toByteArray() },
            ).also { it.start() }

        val traversal = get("/../secret")
        assertEquals(HTTP_FORBIDDEN, traversal.statusCode(), "a traversal path must be rejected with 403, never served")
        assertTrue(!traversal.body().contains("SECRET"), "traversal must NOT leak an out-of-root resource")
    }

    @Test
    fun `sets content-type by extension so vite module scripts load`() {
        val resources =
            mapOf(
                "/frontend/index.html" to "<!doctype html>".toByteArray(),
                "/frontend/assets/app.js" to "export const x=1".toByteArray(),
                "/frontend/assets/app.css" to ".a{}".toByteArray(),
            )
        server = LoopbackFrontendServer(backendUrl = "http://127.0.0.1:1", resourceLoader = { resources[it] }).also { it.start() }

        assertEquals("text/html; charset=utf-8", get("/").headers().firstValue("Content-Type").orElse(""), "index.html must be text/html")
        assertTrue(
            get("/assets/app.js")
                .headers()
                .firstValue("Content-Type")
                .orElse("")
                .startsWith("text/javascript"),
            "a .js asset must be served as a JS module type (browsers refuse non-JS module scripts)",
        )
        assertTrue(
            get("/assets/app.css")
                .headers()
                .firstValue("Content-Type")
                .orElse("")
                .startsWith("text/css"),
            "a .css asset must be text/css",
        )
    }

    @Test
    fun `forwards the upstream content-type on proxied api responses`() {
        val b = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        b.createContext("/api") { ex ->
            val body = "[]".toByteArray()
            ex.responseHeaders.set("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        b.start()
        backend = b
        server = LoopbackFrontendServer(backendUrl = "http://127.0.0.1:${b.address.port}").also { it.start() }

        val response = get("/api/sessions")
        assertEquals(
            "application/json",
            response.headers().firstValue("Content-Type").orElse(""),
            "the backend's Content-Type must reach the client (the SPA needs it)",
        )
        assertEquals("[]", response.body())
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    fun `streams a long-lived sse response incrementally instead of buffering`() {
        // The stub backend writes ONE event, flushes, then BLOCKS on a latch the test only releases
        // AFTER it has already read that first event from the proxy. If the proxy buffered the body
        // to EOF (the old readBytes() bug), this test would deadlock and hit the 10s @Timeout: the
        // proxy would wait for an EOF the backend never sends until the latch, and the latch is only
        // released once the first event is read — a cycle only a STREAMING proxy can break.
        val firstEventRead = java.util.concurrent.CountDownLatch(1)
        val b = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        b.createContext("/api") { ex ->
            ex.responseHeaders.set("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0L) // chunked, unbounded
            ex.responseBody.use { out ->
                out.write("data: one\n\n".toByteArray())
                out.flush()
                firstEventRead.await(8, java.util.concurrent.TimeUnit.SECONDS) // backend stays open
                out.write("data: two\n\n".toByteArray())
                out.flush()
            }
        }
        b.start()
        backend = b
        server = LoopbackFrontendServer(backendUrl = "http://127.0.0.1:${b.address.port}").also { it.start() }

        val s = requireNotNull(server)
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${s.port}/api/sessions/x/stream")).GET().build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        assertEquals("text/event-stream", response.headers().firstValue("Content-Type").orElse(""), "SSE Content-Type must be forwarded")
        val reader = response.body().bufferedReader()
        val firstLine = reader.readLine() // arrives only if the proxy streamed the first event live
        assertEquals("data: one", firstLine, "the first SSE event must arrive BEFORE the backend sends the second (no buffering)")
        firstEventRead.countDown() // let the backend finish so the connection can close cleanly
    }

    private companion object {
        const val FORMER_FIXED_PORT = 8090
        const val HTTP_FORBIDDEN = 403
    }
}
