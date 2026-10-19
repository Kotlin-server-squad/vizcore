package com.jh.proj.coroutineviz.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The ingest socket must not wait forever on a peer that stops talking.
 *
 * A TCP forwarder in front of the backend (Colima's port forwarder, a load balancer,
 * a proxy) can accept the client's connection while the backend behind it is down and
 * then never answer. Without a handshake deadline the WebSocket upgrade waits forever,
 * the reconnect loop never gets another turn, and the app streams into nothing after a
 * backend restart. The same forwarder can leave an established socket half-open, which
 * only a client-side ping notices.
 *
 * Every case runs the REAL CIO client against a raw socket peer, so the timeouts are
 * real ones (kept short).
 */
class IngestTransportTimeoutTest {

    private val shortTimeouts =
        IngestTimeouts(handshakeMillis = 300, pingIntervalMillis = 200, pingTimeoutMillis = 400)

    // A roomier handshake deadline where the test is about what happens after the upgrade,
    // so a slow CI machine cannot end the attempt early for the wrong reason. It also bounds
    // the session lookup a stalled socket makes, which the silent peer never answers, so it
    // must stay well inside the tests' own deadlines.
    private val pingTimeouts = IngestTimeouts(handshakeMillis = 500, pingIntervalMillis = 100, pingTimeoutMillis = 200)

    @Test
    fun `an upgrade the peer never answers is abandoned and its connection closed`() =
        runBlocking {
            RawPeer(answerUpgrade = false).use { peer ->
                HttpClient(CIO) { install(WebSockets) }.use { client ->
                    val lifetime = SocketLifetime(System::currentTimeMillis)
                    withTimeout(10.seconds) {
                        assertFailsWith<IngestHandshakeTimeoutException> {
                            stream(client, peer.url, "s-1", "t", OutboundBuffer(), shortTimeouts, lifetime)
                        }
                    }
                    assertFalse(lifetime.lastedAtLeast(0), "no socket opened, so the attempt must not count as one")
                    // While the client is still open: closing it would close a leaked connection too.
                    assertTrue(peer.awaitFirstConnectionClosed(), "the abandoned upgrade's connection must be closed")
                }
            }
        }

    @Test
    fun `an upgraded socket whose peer goes silent ends instead of waiting forever`() =
        runBlocking {
            RawPeer(answerUpgrade = true).use { peer ->
                HttpClient(CIO) { install(WebSockets) }.use { client ->
                    val lifetime = SocketLifetime(System::currentTimeMillis)
                    withTimeout(10.seconds) {
                        // The ping goes unanswered, the socket counts as closed by the peer, and
                        // stream() returns so the reconnect loop can try again.
                        stream(client, peer.url, "s-1", "t", OutboundBuffer(), pingTimeouts, lifetime)
                    }
                    assertTrue(lifetime.lastedAtLeast(0), "the upgraded socket must be reported open")
                    assertTrue(peer.upgraded, "the socket must have been upgraded before it went silent")
                    // While the client is still open: closing it would close a leaked connection too.
                    assertTrue(peer.readUpgradedUntilClosed() != null, "the ended socket's connection must be closed")
                }
            }
        }

    @Test
    fun `a socket whose peer stops reading mid-burst ends`() =
        runBlocking {
            RawPeer(answerUpgrade = true).use { peer ->
                HttpClient(CIO) { install(WebSockets) }.use { client ->
                    // Far more than the socket buffers hold, so the drain blocks mid-send with the
                    // socket's queue full, the case where the ping's own close has to get through.
                    withBufferOf(events = 10_000, payloadBytes = 2_000) { buffer ->
                        withTimeout(10.seconds) { streamUntilEnded(client, peer.url, buffer) }
                    }
                    assertTrue(peer.upgraded, "the socket must have been upgraded before it stalled")
                    // While the client is still open: closing it would close a leaked connection too.
                    val received = assertNotNull(peer.readUpgradedUntilClosed(), "the socket's connection must be closed")
                    assertTrue(received < 10_000L * 2_000, "the burst must have backed up, got all $received bytes")
                    assertTrue(peer.laterConnectionsClosed(), "a session lookup's connection must be closed")
                }
            }
        }

    @Test
    fun `a socket whose peer stops reading ends whatever size the last burst was`() =
        runBlocking {
            // Some burst size fills the socket's queue exactly and leaves the drain idle, with
            // no send in progress and the ping's close behind a full queue. Where that size lies
            // depends on Ktor's queue and the OS socket buffers, so measure how many events the
            // client hands off before its sends block, then sweep around it. Each size also runs with the buffer closed after the burst (the client
            // stopping): the drain finishes and the close is left waiting on a peer that never
            // answers it.
            val payloadBytes = 32_000
            // Calibration only succeeds on a run the probe ended, so it is also the proof that
            // the probe path runs. Which check ends each swept run is a race (Ktor's ping close
            // or the probe), so the sweep itself asserts only that every run ends cleanly.
            val first = calibrate(payloadBytes)
            val stuck = mutableListOf<String>()
            val leaked = mutableListOf<String>()
            val lookupCounted = mutableListOf<String>()
            for (events in first..first + 20) {
                for (stopping in listOf(false, true)) {
                    val run = if (stopping) "$events (stopping)" else "$events"
                    val result = burst(events, payloadBytes, stopping)
                    if (!result.ended) stuck += run
                    if (!result.allClosed) leaked += run
                    if (!result.lifetimeExcludesLookup) lookupCounted += run
                }
            }
            assertTrue(stuck.isEmpty(), "stream() never ended after bursts of $stuck events")
            assertTrue(leaked.isEmpty(), "a connection stayed open after bursts of $leaked events")
            assertTrue(lookupCounted.isEmpty(), "the session lookup counted as socket time after bursts of $lookupCounted")
        }

    /**
     * The first burst size to sweep: 10 below how many events of [payloadBytes] the client
     * can hand off before its sends block (its queue and the socket buffers are full).
     * Bursts double until one does not get handed off whole, and only a run the probe ended
     * counts: that proves the sends were blocked, rather than cut short by the ping.
     */
    private suspend fun calibrate(payloadBytes: Int): Int {
        var events = 320
        while (true) {
            repeat(5) {
                val result = burst(events, payloadBytes, stopping = false)
                if (result.sent == events.toLong()) return@repeat
                if (result.stalled) return (result.sent.toInt() - 10).coerceAtLeast(1)
            }
            check(events < 5_120) { "no burst up to $events events of $payloadBytes bytes blocked the client's sends" }
            events *= 2
        }
    }

    private class BurstResult(
        val ended: Boolean,
        val stalled: Boolean,
        /** Whether the socket's recorded lifetime left out the time stream() spent after it. */
        val lifetimeExcludesLookup: Boolean,
        /** How many events the drain handed off before the socket ended. */
        val sent: Long,
        /** Bytes that reached the peer on the upgraded connection; null if it never closed. */
        val received: Long?,
        /** Whether every connection the client opened (the socket, any session lookup) closed. */
        val allClosed: Boolean,
    )

    /**
     * Stream a burst of [events] to a peer that never reads; report whether stream() ended
     * and how many bytes reached the peer before the client closed the connection (null if
     * it never did).
     */
    private suspend fun burst(
        events: Int,
        payloadBytes: Int,
        stopping: Boolean,
    ): BurstResult =
        RawPeer(answerUpgrade = true).use { peer ->
            HttpClient(CIO) { install(WebSockets) }.use { client ->
                var outcome: Boolean? = null
                var sent = 0L
                var lifetimeExcludesLookup = true
                withBufferOf(events = events, payloadBytes = payloadBytes) { buffer ->
                    if (stopping) buffer.close()
                    val lifetime = SocketLifetime(System::currentTimeMillis)
                    val startedAt = System.currentTimeMillis()
                    outcome = withTimeoutOrNull(5.seconds) { streamUntilEnded(client, peer.url, buffer, lifetime) }
                    sent = buffer.sent
                    if (outcome == true) {
                        // A stall is followed by a session lookup the silent peer never answers,
                        // which runs out its 500 ms deadline: none of that is socket time.
                        lifetimeExcludesLookup = !lifetime.lastedAtLeast(System.currentTimeMillis() - startedAt - 250)
                    }
                }
                // While the client is still open: closing it would close a leaked connection too.
                val received = peer.readUpgradedUntilClosed()
                BurstResult(
                    ended = outcome != null,
                    stalled = outcome == true,
                    lifetimeExcludesLookup = lifetimeExcludesLookup,
                    sent = sent,
                    received = received,
                    allClosed = received != null && peer.laterConnectionsClosed(),
                )
            }
        }

    /**
     * Stream until the socket ends; true when the probe found the queue full. Which check
     * ends a reader-less socket first is a race: Ktor's ping close, when it gets through the
     * full queue (a normal return, or an IOException for a send it cut off), or the probe
     * that cannot queue its ping. Each hands control back to the reconnect loop.
     */
    private suspend fun streamUntilEnded(
        client: HttpClient,
        url: String,
        buffer: OutboundBuffer,
        lifetime: SocketLifetime = SocketLifetime(System::currentTimeMillis),
    ): Boolean =
        try {
            stream(client, url, "s-1", "t", buffer, pingTimeouts, lifetime)
            false
        } catch (_: IngestSendStalledException) {
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }

    /** Run [block] with an [OutboundBuffer] already holding [events] events of [payloadBytes]. */
    private suspend fun withBufferOf(
        events: Int,
        payloadBytes: Int,
        block: suspend (OutboundBuffer) -> Unit,
    ) {
        val session = VizSession("s-1")
        val buffer = OutboundBuffer()
        val feedScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            buffer.feed(session, feedScope)
            val padding = "x".repeat(payloadBytes)
            repeat(events) { n ->
                session.send(
                    CoroutineCreated(
                        sessionId = "s-1",
                        seq = n.toLong(),
                        tsNanos = n.toLong(),
                        coroutineId = "c$n",
                        jobId = "j$n",
                        parentCoroutineId = null,
                        scopeId = "s$n",
                        label = padding,
                    ),
                )
            }
            // The feed is asynchronous: wait until the whole burst is in the buffer, so a test
            // that closes it next still sends every event.
            withTimeout(10.seconds) {
                while (buffer.offered < events) delay(5)
            }
            block(buffer)
        } finally {
            feedScope.cancel()
        }
    }

    /**
     * A peer that accepts connections and then goes silent. With [answerUpgrade] it first
     * completes the WebSocket upgrade, then never reads or writes again. Any later
     * connection (a session lookup after a failed socket) is never answered either, as a
     * forwarder with nothing behind it would.
     */
    private class RawPeer(
        private val answerUpgrade: Boolean,
    ) : Closeable {
        // IPv4 explicitly: the loopback address can be ::1, and the URL below names 127.0.0.1.
        private val loopback = InetAddress.getByName("127.0.0.1")
        private val server =
            ServerSocket().apply {
                // A small receive window, so a burst backs up into the client's queue quickly.
                receiveBufferSize = 4_096
                bind(InetSocketAddress(loopback, 0), 50)
            }
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val firstClosed = java.util.concurrent.CountDownLatch(1)

        @Volatile
        var upgraded = false
            private set
        val url = "http://127.0.0.1:${server.localPort}"

        init {
            thread(isDaemon = true, name = "raw-peer-accept") {
                var first = true
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    sockets += socket
                    val isFirst = first
                    first = false
                    thread(isDaemon = true, name = "raw-peer-conn") { serve(socket, isFirst) }
                }
            }
        }

        private fun serve(
            socket: Socket,
            isFirst: Boolean,
        ) {
            runCatching {
                val input = socket.getInputStream()
                val head = readHead(input)
                if (!isFirst) return
                if (answerUpgrade) {
                    val key = Regex("(?im)^Sec-WebSocket-Key:\\s*(\\S+)").find(head)!!.groupValues[1]
                    val accept =
                        Base64.getEncoder().encodeToString(
                            MessageDigest
                                .getInstance("SHA-1")
                                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
                        )
                    socket.getOutputStream().write(
                        (
                            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                                "Sec-WebSocket-Accept: $accept\r\n\r\n"
                        ).toByteArray(),
                    )
                    upgraded = true
                    return // silent from here on: never reads, never writes
                }
                // Never answer; wait for the client to give up and close.
                while (input.read() != -1) Unit
                firstClosed.countDown()
            }
        }

        private fun readHead(input: java.io.InputStream): String {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b == -1) break
                head.append(b.toChar())
            }
            return head.toString()
        }

        fun awaitFirstConnectionClosed(): Boolean = firstClosed.await(5, TimeUnit.SECONDS)

        /**
         * Read the upgraded connection to its end and return how many bytes the client got
         * through, or null when the client never closed it.
         */
        fun readUpgradedUntilClosed(): Long? = readUntilClosed(sockets.first())

        /** Whether every connection after the first (session lookups) has been closed. */
        fun laterConnectionsClosed(): Boolean =
            // Accepts are FIFO: once a marker connection made now has been accepted, every
            // connection the client opened before it is in [sockets].
            Socket(loopback, server.localPort).use { marker ->
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (sockets.none { it.port == marker.localPort }) {
                    if (System.nanoTime() > deadline) return false
                    Thread.sleep(5)
                }
                sockets.drop(1).filter { it.port != marker.localPort }.all { readUntilClosed(it) != null }
            }

        private fun readUntilClosed(socket: Socket): Long? {
            socket.soTimeout = 5_000
            val input = socket.getInputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            return try {
                while (true) {
                    val n = input.read(chunk)
                    if (n == -1) break
                    total += n
                }
                total
            } catch (_: java.net.SocketTimeoutException) {
                null
            } catch (_: java.net.SocketException) {
                total // reset by the client: closed too
            }
        }

        override fun close() {
            server.close()
            sockets.forEach { runCatching { it.close() } }
        }
    }
}
