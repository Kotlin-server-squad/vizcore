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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.PolymorphicSerializer
import org.slf4j.LoggerFactory
import java.io.IOException

private val logger = LoggerFactory.getLogger("com.jh.proj.coroutineviz.client.IngestTransport")

/**
 * Open one authenticated client WebSocket to the backend ingest route and forward
 * every event held by the lifetime-scoped [buffer].
 *
 * Connects to `{backendUrl}/api/sessions/{sessionId}/ingest` with the token (API key or JWT) in the
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
) = stream(httpClient, backendUrl, sessionId, token, buffer, IngestTimeouts())

/**
 * [stream] with explicit [timeouts] (tests shorten them), recording in [lifetime] when
 * the socket opened and when it ended.
 */
internal suspend fun stream(
    httpClient: HttpClient,
    backendUrl: String,
    sessionId: String,
    token: String,
    buffer: OutboundBuffer,
    timeouts: IngestTimeouts,
    lifetime: SocketLifetime = SocketLifetime(System::currentTimeMillis),
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
        connectAndDrain(httpClient, wsBackendUrl, sessionId, token, buffer, timeouts, lifetime)
    } catch (gone: SessionGoneException) {
        throw gone
    } catch (silent: IngestHandshakeTimeoutException) {
        // No answer at all says nothing about the session, and a lookup through the same
        // silent forwarder would only stall the retry further.
        throw silent
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        // The upgrade itself failed. A session the backend no longer has answers the
        // REST lookup with 404 — that is unrecoverable for this id, so surface it as
        // SessionGoneException. Anything else (backend down, DNS, timeout) is a
        // transient transport failure and must stay a plain retry.
        if (sessionIsGone(httpClient, backendUrl, sessionId, token, timeouts.handshakeMillis)) {
            throw SessionGoneException("session $sessionId no longer exists on the backend (404)", failure)
        }
        throw failure
    }
}

/**
 * How long the ingest socket waits on a peer that has stopped talking.
 *
 * [handshakeMillis] bounds the WebSocket upgrade. [pingIntervalMillis] and
 * [pingTimeoutMillis] make the client ping an open socket and give it up when no pong
 * comes back, matching the backend's own 15 s / 30 s.
 */
internal data class IngestTimeouts(
    val handshakeMillis: Long = 10_000L,
    val pingIntervalMillis: Long = 15_000L,
    val pingTimeoutMillis: Long = 30_000L,
)

/**
 * When one connection attempt's socket opened and ended, as read by the reconnect loop to
 * decide whether it was a healthy connection. The time spent before the upgrade, or in a
 * session lookup after the socket failed, is not socket time.
 */
internal class SocketLifetime(
    private val clock: () -> Long,
) {
    @Volatile private var openedAt = UNSET

    @Volatile private var endedAt = UNSET

    fun opened() {
        openedAt = clock()
    }

    fun ended() {
        if (openedAt != UNSET && endedAt == UNSET) endedAt = clock()
    }

    /** Whether the socket opened and stayed open for at least [millis]. */
    fun lastedAtLeast(millis: Long): Boolean {
        val opened = openedAt
        if (opened == UNSET) return false
        val ended = endedAt.takeIf { it != UNSET } ?: clock()
        return ended - opened >= millis
    }

    private companion object {
        const val UNSET = -1L
    }
}

/**
 * Thrown when the WebSocket upgrade gets no answer within [IngestTimeouts.handshakeMillis].
 * Says nothing about the session, so the reconnect loop just backs off and retries.
 */
class IngestHandshakeTimeoutException(
    message: String,
) : Exception(message)

/**
 * Thrown when a probe ping cannot even be queued on the socket within
 * [IngestTimeouts.pingTimeoutMillis]: the peer has stopped reading and the socket's queue
 * is full, which also keeps Ktor's own ping timeout from closing it.
 */
class IngestSendStalledException(
    message: String,
) : Exception(message)

/** How a socket ended, as seen from inside the webSocket {} block. */
private sealed interface SocketEnd {
    /** The buffer was closed: the client is stopping. */
    object Stopped : SocketEnd

    /** A send or the probe failed. */
    class Failed(
        val cause: Exception,
    ) : SocketEnd

    /** The peer ended the socket: a close frame, or none (a ping timeout). */
    class PeerEnded(
        val reason: CloseReason?,
    ) : SocketEnd
}

/** How a finished drain or probe ended the socket; a failure is kept as itself. */
private suspend fun Deferred<Unit>.toSocketEnd(): SocketEnd =
    try {
        await()
        SocketEnd.Stopped
    } catch (cancellation: CancellationException) {
        // Our own cancellation propagates. Anything else is the socket failing (Ktor's
        // writer can fail a send with a CancellationException), wrapped so it can never
        // read as "client stopping" to the reconnect loop.
        currentCoroutineContext().ensureActive()
        SocketEnd.Failed(IOException("ingest socket failed", cancellation))
    } catch (failure: Exception) {
        SocketEnd.Failed(failure)
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
 *
 * A peer that stops talking is the one case that needs timeouts. A TCP forwarder in
 * front of the backend (Colima's port forwarder, a load balancer, a proxy) can accept
 * the connection while the backend behind it is down and never answer the upgrade, so
 * the upgrade gets [IngestTimeouts.handshakeMillis] before it is abandoned with
 * [IngestHandshakeTimeoutException]. Once upgraded, the client pings, so a socket the
 * forwarder has left half-open ends after [IngestTimeouts.pingTimeoutMillis] instead of
 * holding the reconnect loop forever. A peer that stops reading once the socket's queue is
 * full would also block the ping's own close, so a probe ping must be queued within the
 * ping timeout too ([IngestSendStalledException]).
 */
private suspend fun connectAndDrain(
    httpClient: HttpClient,
    wsBackendUrl: String,
    sessionId: String,
    token: String,
    buffer: OutboundBuffer,
    timeouts: IngestTimeouts,
    lifetime: SocketLifetime,
) = coroutineScope {
    // Failing from a child cancels the pending upgrade (closing its connection) and makes
    // coroutineScope rethrow the timeout itself, not a CancellationException.
    val handshakeDeadline =
        launch {
            delay(timeouts.handshakeMillis)
            throw IngestHandshakeTimeoutException(
                "no WebSocket upgrade from the backend within ${timeouts.handshakeMillis} ms",
            )
        }
    val ended = CompletableDeferred<SocketEnd>()
    val socketCall =
        launch {
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
                handshakeDeadline.cancel()
                lifetime.opened()
                pingIntervalMillis = timeouts.pingIntervalMillis
                timeoutMillis = timeouts.pingTimeoutMillis
                // supervisorScope, not plain launch: a send that fails because the socket
                // dropped mid-frame must NOT cancel this coroutine (that would surface as a
                // CancellationException and the reconnect loop treats cancellation as
                // "client stopping" and exits). Here the failure stays inside the drain and
                // is reported as itself below.
                supervisorScope {
                    val drain =
                        async {
                            buffer.drain { event ->
                                send(Frame.Text(appJson.encodeToString(PolymorphicSerializer(VizEvent::class), event)))
                            }
                        }
                    // Ktor's ping can fail to report a peer that stopped reading once the
                    // socket's queue is full: its close waits in that same queue, whether the
                    // drain is blocked mid-send or idle after a burst. So a ping of our own has
                    // to make it into the queue in time. withTimeoutOrNull, not withTimeout: its
                    // TimeoutCancellationException would read as "client stopping" to the
                    // reconnect loop.
                    val probe =
                        async {
                            while (true) {
                                delay(timeouts.pingIntervalMillis)
                                withTimeoutOrNull(timeouts.pingTimeoutMillis) { send(Frame.Ping(ByteArray(0))) }
                                    ?: throw IngestSendStalledException(
                                        "the backend took no data for ${timeouts.pingTimeoutMillis} ms",
                                    )
                            }
                        }
                    // A null result, not a null reason, marks the drain or probe finishing:
                    // closeReason itself can complete with null.
                    val end =
                        select<SocketEnd?> {
                            closeReason.onAwait { SocketEnd.PeerEnded(it) }
                            drain.onJoin { null }
                            probe.onJoin { null }
                        } ?: (if (drain.isCompleted) drain else probe).toSocketEnd()
                    drain.cancel()
                    probe.cancel()
                    ended.complete(end)
                }
            }
        }
    // Once the socket has ended, webSocket {} still waits for the connection to wind down,
    // which a silent peer never does. Cancelling the call closes it; the cancellation
    // stays inside socketCall. A clean stop gets a ping timeout to finish its close.
    val end =
        try {
            select<SocketEnd?> {
                ended.onAwait { it }
                socketCall.onJoin { null }
            }
        } finally {
            lifetime.ended()
        }
    when (end) {
        // The call ended without the socket reporting how: an abnormal end, not a stop. If
        // this scope is already failing (the handshake deadline), let that failure through.
        null -> {
            ensureActive()
            throw IOException("ingest socket ended without a close or a stop")
        }
        SocketEnd.Stopped -> withTimeoutOrNull(timeouts.pingTimeoutMillis) { socketCall.join() } ?: socketCall.cancel()
        is SocketEnd.Failed -> {
            socketCall.cancel()
            throw end.cause
        }
        is SocketEnd.PeerEnded -> {
            socketCall.cancel()
            val reason = end.reason
            if (reason?.isSessionGone() == true) {
                throw SessionGoneException("backend closed the ingest socket: ${reason.code} ${reason.message}")
            }
            logger.info("Ingest socket closed ({}); reconnecting", reason ?: "no close frame")
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
 * answer — including a transport failure, a 401, a 5xx or none within [timeoutMillis] —
 * is reported as "still there" so a backend problem is never mistaken for a deleted
 * session (which would leak a brand-new session on every reconnect).
 */
private suspend fun sessionIsGone(
    httpClient: HttpClient,
    backendUrl: String,
    sessionId: String,
    token: String,
    timeoutMillis: Long,
): Boolean =
    // Bounded: a forwarder that swallows the upgrade may swallow this request too, and the
    // caller's client need not have a request timeout of its own.
    withTimeoutOrNull(timeoutMillis) { lookupIsNotFound(httpClient, backendUrl, sessionId, token) } ?: false

@Suppress("TooGenericExceptionCaught") // a lookup that cannot complete must read as "not gone"
private suspend fun lookupIsNotFound(
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
