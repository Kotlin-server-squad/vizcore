package com.jh.coroutinevisualizer.api

import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Native Kotlin consumer of the backend /api (replaces the deleted browser/loopback path). [resolve]
 * returns null on any non-200 (404 = correlation not bound yet — caller keeps polling); the others
 * return empty/null on non-200 and propagate transport exceptions to the caller (the polling service
 * catches + retries).
 */
class VizcoreApiClient(
    private val baseUrl: String,
    private val token: String = "",
    private val http: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun request(path: String): HttpRequest {
        val builder =
            HttpRequest.newBuilder(URI("$baseUrl/api$path")).timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS)).GET()
        if (token.isNotEmpty()) builder.header("Authorization", "Bearer $token")
        return builder.build()
    }

    private fun send(path: String): HttpResponse<String> = http.send(request(path), HttpResponse.BodyHandlers.ofString())

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    fun resolve(correlation: String): String? {
        val response = send("/sessions/resolve?correlation=${enc(correlation)}")
        return if (response.statusCode() == HTTP_OK) {
            json.decodeFromString<ResolveDto>(response.body()).sessionId
        } else {
            null
        }
    }

    /**
     * The live coroutine tree for [sessionId]. THROWS on a non-200 so the caller can keep the last
     * good tree instead of mistaking a transient failure (timeout, 404 during a poll) for an
     * empty session and wiping the view.
     */
    fun hierarchy(sessionId: String): List<HierarchyNodeDto> {
        val response = send("/sessions/${enc(sessionId)}/hierarchy")
        check(response.statusCode() == HTTP_OK) { "hierarchy HTTP ${response.statusCode()} for $sessionId" }
        return json.decodeFromString(response.body())
    }

    fun metrics(sessionId: String): MetricsDto? {
        val response = send("/sessions/${enc(sessionId)}/metrics")
        return if (response.statusCode() == HTTP_OK) json.decodeFromString(response.body()) else null
    }

    fun timeline(
        sessionId: String,
        coroutineId: String,
    ): TimelineDto? {
        val response = send("/sessions/${enc(sessionId)}/coroutines/${enc(coroutineId)}/timeline")
        return if (response.statusCode() == HTTP_OK) json.decodeFromString(response.body()) else null
    }

    private companion object {
        private const val HTTP_OK = 200
        private const val CONNECT_TIMEOUT_SECONDS = 3L
        private const val REQUEST_TIMEOUT_SECONDS = 5L
    }
}
