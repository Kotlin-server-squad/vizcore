package com.jh.proj.coroutineviz.client

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.VizEvent
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.takeFrom
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.PolymorphicSerializer

/**
 * Open one authenticated client WebSocket to the backend ingest route and forward
 * every event held by the lifetime-scoped [buffer].
 *
 * Connects to `{backendUrl}/api/sessions/{sessionId}/ingest` with the JWT in the
 * `Authorization: Bearer` HEADER (never the URL — T-07-02). Inside the socket it
 * DRAINS the [OutboundBuffer] (NOT `session.bus` directly — that per-socket subscribe
 * is exactly the CR-01 bug that lost events outside the open window) and sends each
 * [VizEvent] as a `Frame.Text` serialized with the SHARED core [appJson] (Pitfall 3 —
 * wire format never drifts from the backend ingest / SSE / FE format).
 *
 * The drain runs until the connection drops or the calling scope is cancelled; on
 * either, the buffer and its retained events SURVIVE for the next socket. The caller
 * ([VizcoreClient]) owns the reconnect/backoff loop around this call and feeds the
 * buffer exactly once for the client lifetime.
 *
 * Path segments are appended via [appendPathSegments] (each segment percent-encoded)
 * so a legacy pre-slugify session id containing spaces cannot malform the HTTP
 * request line and get the socket dropped by the backend (Netty).
 *
 * Session recovery (#129): the backend refuses an ingest socket for a session it no
 * longer has by closing with `VIOLATED_POLICY` "Session not found", and answers the
 * REST lookup for it with `404`. Either signal is translated into a
 * [SessionGoneException] so the caller's reconnect loop can create a FRESH session
 * instead of retrying forever against an id that will never come back. Every other
 * failure (refused connect, dropped socket, timeout) is rethrown unchanged and is a
 * plain backoff-retry.
 */
suspend fun stream(
    httpClient: HttpClient,
    backendUrl: String,
    sessionId: String,
    token: String,
    buffer: OutboundBuffer,
) {
    // Ktor's client WebSockets plugin only performs the upgrade handshake for the ws/wss
    // scheme; an http/https URL is sent as a plain GET (NoTransformationFoundException). Convert
    // the REST backendUrl scheme to its WebSocket equivalent. A scheme-less value (e.g. "" in the
    // in-process round-trip test) is left untouched so the relative-URL path still works.
    val wsBackendUrl =
        when {
            backendUrl.startsWith("https://", ignoreCase = true) -> "wss://" + backendUrl.substring("https://".length)
            backendUrl.startsWith("http://", ignoreCase = true) -> "ws://" + backendUrl.substring("http://".length)
            else -> backendUrl
        }
    try {
        connectAndDrain(httpClient, wsBackendUrl, sessionId, token, buffer)
    } catch (gone: SessionGoneException) {
        throw gone
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        // The upgrade itself failed. A session the backend no longer has answers the
        // REST lookup with 404 — that is unrecoverable for this id, so surface it as
        // SessionGoneException. Anything else (backend down, DNS, timeout) is a
        // transient transport failure and must stay a plain retry.
        if (sessionIsGone(httpClient, backendUrl, sessionId, token)) {
            throw SessionGoneException("session $sessionId no longer exists on the backend (404)", failure)
        }
        throw failure
    }
}

/**
 * Thrown when the backend states the target session is gone — a `VIOLATED_POLICY`
 * "not found" close on the ingest socket, or a `404` on the session lookup. Signals
 * the reconnect loop to bootstrap a NEW session rather than retry this id.
 */
class SessionGoneException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Open the socket and drain the buffer into it until EITHER the buffer closes (the
 * client is stopping) OR the peer closes the connection.
 *
 * The two are raced deliberately: [OutboundBuffer.drain] suspends on an empty
 * channel, so a server that accepts the upgrade and immediately closes (exactly what
 * it does for an unknown session) would otherwise leave the drain suspended forever
 * and the reconnect loop would never see the close. Racing `closeReason` against the
 * drain job makes the close observable with no timeout or poll.
 */
private suspend fun connectAndDrain(
    httpClient: HttpClient,
    wsBackendUrl: String,
    sessionId: String,
    token: String,
    buffer: OutboundBuffer,
) {
    httpClient.webSocket(
        request = {
            // Build the ingest URL from ENCODED path segments: Ktor's takeFrom(String)
            // treats a raw string as pre-encoded and client-CIO writes the path verbatim
            // into the request line, so a space in a legacy id would malform it (RFC 7230)
            // and Netty would drop the socket. appendPathSegments percent-encodes each
            // segment (space -> %20), so even a spacey id yields a well-formed request.
            url {
                // A scheme-less value ("" in the in-process round-trip test) must stay a
                // relative URL; takeFrom("") is skipped so the default builder + segments
                // resolve against the test client's host.
                if (wsBackendUrl.isNotBlank()) {
                    takeFrom(wsBackendUrl)
                }
                appendPathSegments("api", "sessions", sessionId, "ingest")
            }
            header(HttpHeaders.Authorization, "Bearer $token")
        },
    ) {
        // supervisorScope, not plain launch: a send that fails because the socket
        // dropped mid-frame must NOT cancel this coroutine (that would surface as a
        // CancellationException and the reconnect loop treats cancellation as
        // "client stopping" and exits). Here the failure stays inside the drain and
        // is re-thrown as itself below.
        supervisorScope {
            val drain =
                async {
                    buffer.drain { event ->
                        send(Frame.Text(appJson.encodeToString(PolymorphicSerializer(VizEvent::class), event)))
                    }
                }
            val reason =
                select<CloseReason?> {
                    closeReason.onAwait { it }
                    drain.onJoin { null }
                }
            if (reason == null) {
                // The drain ended first: the buffer was closed (client stopping — a
                // clean end) or a send failed. await() re-throws the failure as itself
                // so the reconnect loop logs the real cause.
                drain.await()
                return@supervisorScope
            }
            drain.cancel()
            if (reason.isSessionGone()) {
                throw SessionGoneException("backend closed the ingest socket: ${reason.code} ${reason.message}")
            }
        }
    }
}

/**
 * Whether a close reason says THIS session is gone rather than that the connection
 * had a problem: the backend refuses an ingest handshake for an unknown (or
 * cross-tenant) session id with `VIOLATED_POLICY` "Session not found".
 */
private fun CloseReason.isSessionGone(): Boolean =
    knownReason == CloseReason.Codes.VIOLATED_POLICY && message.contains("not found", ignoreCase = true)

/**
 * Ask the REST API whether [sessionId] still exists. `404` means gone; ANY other
 * answer — including a transport failure, a 401 or a 5xx — is reported as "still
 * there" so a backend problem is never mistaken for a deleted session (which would
 * leak a brand-new session on every reconnect).
 */
@Suppress("TooGenericExceptionCaught") // a lookup that cannot complete must read as "not gone"
private suspend fun sessionIsGone(
    httpClient: HttpClient,
    backendUrl: String,
    sessionId: String,
    token: String,
): Boolean =
    try {
        val response =
            httpClient.get("$backendUrl/api/sessions/$sessionId") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        response.status == HttpStatusCode.NotFound
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    }
