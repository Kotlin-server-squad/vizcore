package com.jh.coroutinevisualizer.health

import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException

/**
 * Validates the user-configured backend URL and probes its reachability.
 *
 * Only the single user-configured URL is ever used as a health-check / proxy
 * target — never a request-supplied one (SSRF guard, V5). [validate] rejects
 * malformed URLs and non-http(s) schemes BEFORE any network call is made, so a
 * `file:`, `gopher:`, or otherwise crafted URL can never reach [isReachable].
 *
 * Pure JDK (`HttpURLConnection`) — no new dependency.
 */
object BackendHealthCheck {
    /** The exact, asserted, actionable message surfaced when the backend is down (D-05). */
    const val DOWN_MESSAGE: String =
        "backend not reachable — start it with `docker compose up`"

    private const val PROBE_TIMEOUT_MS = 2_000

    /** Outcome of [validate]. */
    sealed interface ValidationResult {
        data class Valid(
            val url: String,
        ) : ValidationResult

        data class Invalid(
            val reason: String,
        ) : ValidationResult
    }

    /** Outcome of [check]. */
    sealed interface HealthStatus {
        data object Up : HealthStatus

        data class Down(
            val message: String,
        ) : HealthStatus
    }

    /**
     * Validate [url] as an http(s) absolute URL with a host (SSRF guard, V5).
     * Returns [ValidationResult.Invalid] for malformed input or non-http(s) schemes.
     */
    fun validate(url: String): ValidationResult {
        val trimmed = url.trim()
        val uri = parseUri(trimmed) ?: return ValidationResult.Invalid("malformed backend URL: $url")
        val scheme = uri.scheme?.lowercase()
        return when {
            trimmed.isEmpty() ->
                ValidationResult.Invalid("backend URL must not be empty")
            scheme != "http" && scheme != "https" ->
                ValidationResult.Invalid("backend URL must use http or https (was: ${uri.scheme})")
            uri.host.isNullOrBlank() ->
                ValidationResult.Invalid("backend URL must include a host: $trimmed")
            else -> ValidationResult.Valid(trimmed)
        }
    }

    private fun parseUri(value: String): URI? =
        try {
            URI(value)
        } catch (_: URISyntaxException) {
            null
        }

    /** True when [validate] accepts [url]. Convenience for callers that only need a boolean. */
    fun isValid(url: String): Boolean = validate(url) is ValidationResult.Valid

    /**
     * Validate then probe [url]. Returns [HealthStatus.Down] (with [DOWN_MESSAGE] for an
     * unreachable host, or the validation reason for a malformed URL) or [HealthStatus.Up].
     * Only the validated, user-configured URL is ever dialed.
     */
    fun check(url: String): HealthStatus =
        when (val validation = validate(url)) {
            is ValidationResult.Invalid -> HealthStatus.Down(validation.reason)
            is ValidationResult.Valid -> if (isReachable(validation.url)) HealthStatus.Up else HealthStatus.Down(DOWN_MESSAGE)
        }

    /**
     * Open a short-timeout GET to [url] and report whether it answered an HTTP status.
     * Assumes [url] was already validated. Any I/O failure (connection refused, timeout,
     * DNS) is treated as "not reachable".
     */
    fun isReachable(url: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection =
                (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = PROBE_TIMEOUT_MS
                    readTimeout = PROBE_TIMEOUT_MS
                    instanceFollowRedirects = false
                }
            // Any answered status code (even 4xx/5xx) proves the host is up and listening.
            connection.responseCode > 0
        } catch (_: java.io.IOException) {
            false
        } finally {
            connection?.disconnect()
        }
    }
}
