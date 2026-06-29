package com.jh.coroutinevisualizer.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
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
            val body = bodyStream?.use { it.readBytes() } ?: ByteArray(0)
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1L else body.size.toLong())
            exchange.responseBody.use { if (body.isNotEmpty()) it.write(body) }
        } catch (e: IOException) {
            logger.warn("Proxy to backend failed for ${exchange.requestURI}", e)
            respondPlain(exchange, HttpURLConnection.HTTP_BAD_GATEWAY, "backend proxy error")
        } finally {
            connection?.disconnect()
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
            val path = exchange.requestURI.path.let { if (it == "/" || it.isEmpty()) "/index.html" else it }
            val bytes = resourceLoader("$FRONTEND_ROOT$path") ?: resourceLoader("$FRONTEND_ROOT/index.html")
            if (bytes == null) {
                respondPlain(exchange, HttpURLConnection.HTTP_NOT_FOUND, "not found")
            } else {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        } catch (e: IOException) {
            logger.warn("Static serve failed for ${exchange.requestURI}", e)
            respondPlain(exchange, HttpURLConnection.HTTP_INTERNAL_ERROR, "static serve error")
        }
    }

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
        private val METHODS_WITH_BODY = setOf("POST", "PUT", "PATCH", "DELETE")

        /** Default classpath resource loader: reads `resourcePath` from this bundle's classloader. */
        private fun loadClasspathResource(resourcePath: String): ByteArray? =
            LoopbackFrontendServer::class.java.getResourceAsStream(resourcePath)?.use { it.readBytes() }
    }
}
