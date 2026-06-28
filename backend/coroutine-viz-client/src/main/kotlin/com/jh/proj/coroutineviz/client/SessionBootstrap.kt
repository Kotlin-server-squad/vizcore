package com.jh.proj.coroutineviz.client

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Minimal lenient parser for the session-create response. We only need the
 * `sessionId` field; [Json] with `ignoreUnknownKeys` tolerates the backend's
 * extra `message` field (and any future additions) without coupling to the full
 * response schema.
 */
private val bootstrapJson = Json { ignoreUnknownKeys = true }

/**
 * Create a server session over the authenticated REST API and return the
 * server-assigned `sessionId`.
 *
 * Calls `POST {backendUrl}/api/sessions?name={appName}` with the JWT in the
 * `Authorization: Bearer` HEADER (never the URL — T-07-02). The backend responds
 * `201 Created` with `{"sessionId": "...", "message": "..."}`; we extract
 * `sessionId` so the caller can build a LOCAL `VizSession` carrying the SERVER id
 * (Pitfall 1 / T-07-03 — ingested events must carry the correct immutable id).
 *
 * When a [correlation] token is supplied it is forwarded as an OPTIONAL
 * `?correlation=` query param (CORR-01 / D-11) so the backend can record it against
 * the session it creates, letting a separate poller (e.g. the IDE/connect wizard)
 * converge on this same real session id. The token is an OPAQUE client-minted
 * string — the client does NOT know about `TenantContext` and adds no server/auth
 * dependency, keeping this module pure-Kotlin JVM-17 (D-12). Old callers that pass
 * nothing emit NO `correlation` param (wire-level back-compat). The non-secret token
 * rides the query string (T-09-05 accept); the JWT credential stays in the header.
 */
suspend fun createSession(
    httpClient: HttpClient,
    backendUrl: String,
    appName: String,
    token: String,
    correlation: String? = null,
): String {
    val response =
        httpClient.post("$backendUrl/api/sessions") {
            parameter("name", appName)
            correlation?.let { parameter("correlation", it) }
            header(HttpHeaders.Authorization, "Bearer $token")
        }
    val body = bootstrapJson.parseToJsonElement(response.bodyAsText()).jsonObject
    return body["sessionId"]?.jsonPrimitive?.content
        ?: error("Session-create response did not contain a sessionId: $body")
}
