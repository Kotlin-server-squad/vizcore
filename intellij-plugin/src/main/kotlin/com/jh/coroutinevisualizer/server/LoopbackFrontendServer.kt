package com.jh.coroutinevisualizer.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI

/**
 * Loopback-only static-SPA + single-target `/api` reverse-proxy, built on the JDK
 * [com.sun.net.httpserver.HttpServer] (replaces the deleted io.ktor receiver, D-11).
 *
 * Lifecycle shape mirrors the deleted `PluginEventReceiver` (start/stop/dispose,
 * [Logger.getInstance]) but is implementation-swapped to the JDK server. No coroutines
 * are needed (the JDK server has its own executor) — and therefore no `GlobalScope`.
 *
 * Security invariants:
 *  - Binds [InetAddress.getLoopbackAddress] on an ephemeral port (0) ONLY — never `0.0.0.0`
 *    (T-13-07, V4/V6).
 *  - Any `/api` request is reverse-proxied to the SINGLE [backendUrl] passed at construction —
 *    never a request-supplied target (T-13-08, SSRF guard V5). The proxied URI path/query come
 *    from the request, but the scheme+host+port are fixed to the configured backend.
 *  - `/` serves the bundled SPA from the classpath under `/frontend`, falling back to
 *    `index.html` for client-side routes. Longest-path matching makes `/api` win over `/`.
 *
 * @param backendUrl the validated, user-configured backend URL (see BackendHealthCheck.validate)
 * @param resourceLoader resolves a classpath resource path to its bytes (injectable for tests);
 *   defaults to this class's classloader so the bundled `frontend/` resources are served in prod.
 */
@Suppress("TooManyFunctions") // static-serve + streaming reverse-proxy legitimately need small focused handlers
class LoopbackFrontendServer(
    private val backendUrl: String,
    private val resourceLoader: (String) -> ByteArray? = ::loadClasspathResource,
) : Disposable {
    private val logger = Logger.getInstance(LoopbackFrontendServer::class.java)
    private var server: HttpServer? = null

    val isRunning: Boolean get() = server != null

    /** The bound ephemeral port (for the JCEF tool window to build its URL). -1 until started. */
    val port: Int get() = server?.address?.port ?: -1

    /** The bound loopback address. `null` until started. */
    val boundAddress: InetAddress? get() = server?.address?.address

    fun start() {
        if (isRunning) return
        val httpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        // Longest-path match: "/api" wins over "/" for any /api/* request.
        httpServer.createContext("/api") { exchange -> handleApiProxy(exchange) }
        httpServer.createContext("/") { exchange -> handleStatic(exchange) }
        httpServer.start()
        server = httpServer
        logger.info("LoopbackFrontendServer started on ${httpServer.address} → proxying /api to $backendUrl")
    }

    fun stop() {
        server?.let {
            it.stop(0)
            logger.info("LoopbackFrontendServer stopped")
        }
        server = null
    }

    override fun dispose() {
        stop()
    }

    /** Reverse-proxy the exchange to the single configured backend (never a request-supplied host). */
    private fun handleApiProxy(exchange: HttpExchange) {
        var connection: HttpURLConnection? = null
        try {
            // Fixed scheme+host+port from backendUrl; only the path+query come from the request.
            val target = URI("$backendUrl${exchange.requestURI}").toURL()
            connection = openProxyConnection(target, exchange)
            val status = connection.responseCode
            val bodyStream = if (status >= HttpURLConnection.HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream
            // Forward the upstream response headers (Content-Type etc.) BEFORE committing the
            // response, then send with chunked transfer (length 0) and STREAM the body — so an
            // unbounded SSE response (/api/sessions/<id>/stream: no Content-Length, never EOFs)
            // is relayed event-by-event instead of buffered to completion, which would deadlock
            // the live view (CR-02). Length 0 == chunked per HttpServer.sendResponseHeaders.
            copyResponseHeaders(connection, exchange)
            exchange.sendResponseHeaders(status, 0L)
            streamBody(bodyStream, exchange)
        } catch (e: IOException) {
            logger.warn("Proxy to backend failed for ${exchange.requestURI}", e)
            respondPlain(exchange, HttpURLConnection.HTTP_BAD_GATEWAY, "backend proxy error")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Copy the upstream response headers to the client (CR-02 / WR-01 — the SPA needs the backend's
     * `Content-Type`, e.g. `application/json` and `text/event-stream`). The null map key is the HTTP
     * status line; `Content-Length`/`Transfer-Encoding` are dropped because we re-send chunked.
     */
    private fun copyResponseHeaders(
        connection: HttpURLConnection,
        exchange: HttpExchange,
    ) {
        connection.headerFields.forEach { (name, values) ->
            if (name != null &&
                !name.equals("Content-Length", ignoreCase = true) &&
                !name.equals("Transfer-Encoding", ignoreCase = true)
            ) {
                values.forEach { exchange.responseHeaders.add(name, it) }
            }
        }
    }

    /** Stream the upstream body to the client, flushing each chunk so SSE events arrive live (CR-02). */
    private fun streamBody(
        bodyStream: InputStream?,
        exchange: HttpExchange,
    ) {
        if (bodyStream == null) {
            exchange.responseBody.close()
            return
        }
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        bodyStream.use { upstream ->
            exchange.responseBody.use { out ->
                var read = upstream.read(buffer)
                while (read != -1) {
                    out.write(buffer, 0, read)
                    out.flush()
                    read = upstream.read(buffer)
                }
            }
        }
    }

    /** Open + configure the upstream connection (method, timeouts, forwarded headers, body). */
    private fun openProxyConnection(
        target: java.net.URL,
        exchange: HttpExchange,
    ): HttpURLConnection {
        val connection = target.openConnection() as HttpURLConnection
        connection.requestMethod = exchange.requestMethod
        connection.connectTimeout = PROXY_TIMEOUT_MS
        connection.readTimeout = PROXY_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        forwardHeaders(connection, exchange)
        if (METHODS_WITH_BODY.contains(exchange.requestMethod.uppercase())) {
            connection.doOutput = true
            forwardBody(connection, exchange)
        }
        return connection
    }

    /** Copy request headers to the upstream connection, dropping Host (set by the target). */
    private fun forwardHeaders(
        connection: HttpURLConnection,
        exchange: HttpExchange,
    ) {
        exchange.requestHeaders.forEach { (name, values) ->
            if (!name.equals("Host", ignoreCase = true)) {
                values.forEach { connection.addRequestProperty(name, it) }
            }
        }
    }

    /** Stream the request body to the upstream connection. */
    private fun forwardBody(
        connection: HttpURLConnection,
        exchange: HttpExchange,
    ) {
        connection.outputStream.use { out -> exchange.requestBody.use { it.copyTo(out) } }
    }

    /** Serve a bundled SPA resource, falling back to index.html for client-side routes. */
    private fun handleStatic(exchange: HttpExchange) {
        try {
            // Normalize + reject traversal: a raw path like `/../agent/coroutine-viz-agent.jar`
            // would otherwise escape /frontend via classloader normalization and leak other
            // classpath resources (CR-03). safeStaticPath strips `.`/`..` and rejects any escape.
            val safePath = safeStaticPath(exchange.requestURI.path)
            if (safePath == null) {
                respondPlain(exchange, HTTP_FORBIDDEN, "forbidden")
                return
            }
            // Serve the requested resource, or fall back to index.html for client-side SPA routes.
            val requested = resourceLoader("$FRONTEND_ROOT$safePath")
            val servedPath = if (requested != null) safePath else "/index.html"
            val bytes = requested ?: resourceLoader("$FRONTEND_ROOT/index.html")
            if (bytes == null) {
                respondPlain(exchange, HttpURLConnection.HTTP_NOT_FOUND, "not found")
            } else {
                // Content-Type by extension (CR-03): without it, Vite's `<script type="module">`
                // entry is refused by the browser's strict module-MIME check and the SPA won't boot.
                exchange.responseHeaders.set("Content-Type", contentTypeFor(servedPath))
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        } catch (e: IOException) {
            logger.warn("Static serve failed for ${exchange.requestURI}", e)
            respondPlain(exchange, HttpURLConnection.HTTP_INTERNAL_ERROR, "static serve error")
        }
    }

    /**
     * Map a request path to a safe, root-anchored static path, or `null` if it attempts traversal.
     * `/` and empty → `/index.html`. The path is URI-normalized (collapsing `.`/`..`); any result
     * that still contains `..` or is not absolute is rejected so it cannot escape `/frontend` once
     * prefixed (CR-03). Because the normalized path carries no `..`, `"$FRONTEND_ROOT$safePath"`
     * always stays under the bundled frontend root.
     */
    private fun safeStaticPath(rawPath: String): String? {
        if (rawPath.isEmpty() || rawPath == "/") return "/index.html"
        val normalized = URI(rawPath).normalize().path
        return if (normalized.contains("..") || !normalized.startsWith("/")) null else normalized
    }

    /** Resolve a `Content-Type` from a served path's extension (CR-03); octet-stream when unknown. */
    private fun contentTypeFor(path: String): String =
        CONTENT_TYPES[path.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    private fun respondPlain(
        exchange: HttpExchange,
        status: Int,
        message: String,
    ) {
        val body = message.toByteArray()
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    companion object {
        private const val FRONTEND_ROOT = "/frontend"
        private const val PROXY_TIMEOUT_MS = 30_000
        private const val STREAM_BUFFER_BYTES = 8_192

        /** HTTP 403 — `com.sun.net.httpserver` has no named constant for it. */
        private const val HTTP_FORBIDDEN = 403
        private val METHODS_WITH_BODY = setOf("POST", "PUT", "PATCH", "DELETE")

        /** Static `Content-Type` by file extension (CR-03). Vite emits ES-module `<script>` tags. */
        private val CONTENT_TYPES =
            mapOf(
                "html" to "text/html; charset=utf-8",
                "js" to "text/javascript; charset=utf-8",
                "mjs" to "text/javascript; charset=utf-8",
                "css" to "text/css; charset=utf-8",
                "json" to "application/json; charset=utf-8",
                "map" to "application/json; charset=utf-8",
                "svg" to "image/svg+xml",
                "png" to "image/png",
                "jpg" to "image/jpeg",
                "jpeg" to "image/jpeg",
                "gif" to "image/gif",
                "ico" to "image/x-icon",
                "webp" to "image/webp",
                "woff" to "font/woff",
                "woff2" to "font/woff2",
                "ttf" to "font/ttf",
                "txt" to "text/plain; charset=utf-8",
            )

        /** Default classpath resource loader: reads `resourcePath` from this bundle's classloader. */
        private fun loadClasspathResource(resourcePath: String): ByteArray? =
            LoopbackFrontendServer::class.java.getResourceAsStream(resourcePath)?.use { it.readBytes() }
    }
}
