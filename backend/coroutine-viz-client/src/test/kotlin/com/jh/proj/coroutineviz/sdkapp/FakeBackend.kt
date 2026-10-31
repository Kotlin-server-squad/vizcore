package com.jh.proj.coroutineviz.sdkapp

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import kotlinx.serialization.PolymorphicSerializer
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

internal data class Created(
    val label: String?,
    val fileName: String?,
)

/**
 * Session creation and the ingest WebSocket, served from a thread per connection. Plain sockets on
 * threads, so the fake adds no coroutines of its own to what DebugProbes see.
 *
 * Binds [port], or a free port when it is 0, so a test can bring the backend up on a port the
 * client is already trying.
 */
internal class FakeBackend(
    port: Int = 0,
    /** The id every session create answers with. */
    val sessionId: String = "own-1",
) : AutoCloseable {
    private val server =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
    val port: Int get() = server.localPort
    private val sessionRequests = CopyOnWriteArrayList<String>()

    /** The request lines of every `POST /api/sessions` this backend answered. */
    val sessionCreates: List<String> get() = sessionRequests.toList()
    private val events = ConcurrentHashMap<String, Created>()
    val created: List<Created> get() = events.values.toList()

    init {
        thread(isDaemon = true, name = "fake-backend") {
            while (true) {
                val socket =
                    try {
                        server.accept()
                    } catch (_: SocketException) {
                        return@thread
                    }
                thread(isDaemon = true, name = "fake-backend-conn") { socket.use(::handle) }
            }
        }
    }

    fun awaitLabel(label: String) {
        val deadline = System.currentTimeMillis() + 15_000
        while (created.none { it.label == label }) {
            check(System.currentTimeMillis() < deadline) { "$label never arrived; got ${created.map { it.label }}" }
            Thread.sleep(50)
        }
    }

    override fun close() = server.close()

    private fun handle(socket: Socket) {
        val input = socket.getInputStream().buffered()
        val out = socket.getOutputStream()
        val requestLine = input.readHttpLine() ?: return
        val headers =
            generateSequence { input.readHttpLine()?.takeIf { it.isNotEmpty() } }
                .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        when {
            requestLine.startsWith("POST /api/sessions ") || requestLine.startsWith("POST /api/sessions?") -> {
                input.readNBytes(headers["content-length"]?.toInt() ?: 0)
                sessionRequests += requestLine
                val body = """{"sessionId":"$sessionId","message":"ok"}"""
                out.write(
                    (
                        "HTTP/1.1 201 Created\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                    ).toByteArray(),
                )
            }
            requestLine.startsWith("GET /api/sessions/$sessionId/ingest") -> {
                val accept =
                    Base64.getEncoder().encodeToString(
                        MessageDigest
                            .getInstance("SHA-1")
                            .digest((headers.getValue("sec-websocket-key") + WEBSOCKET_GUID).toByteArray()),
                    )
                out.write(
                    (
                        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: $accept\r\n\r\n"
                    ).toByteArray(),
                )
                out.flush()
                readTextFrames(input) { text ->
                    val event = appJson.decodeFromString(PolymorphicSerializer(VizEvent::class), text)
                    if (event is CoroutineCreated) {
                        events[event.coroutineId] = Created(event.label, event.creationPoint?.fileName)
                    }
                }
            }
            else -> out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        }
        out.flush()
    }

    /** Client-to-server WebSocket frames (always masked), until a close frame or EOF. */
    private fun readTextFrames(
        input: InputStream,
        onText: (String) -> Unit,
    ) {
        val message = ByteArrayOutputStream()
        while (true) {
            val b0 = input.read()
            if (b0 < 0) return
            val b1 = input.read()
            var length = (b1 and 0x7f).toLong()
            if (length == 126L) {
                length = ((input.read() shl 8) or input.read()).toLong()
            } else if (length == 127L) {
                length = 0
                repeat(8) { length = (length shl 8) or input.read().toLong() }
            }
            val mask = if (b1 and 0x80 != 0) input.readNBytes(4) else null
            val payload = input.readNBytes(length.toInt())
            if (mask != null) payload.indices.forEach { payload[it] = (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
            when (b0 and 0x0f) {
                OPCODE_CLOSE -> return
                OPCODE_TEXT, OPCODE_CONTINUATION -> {
                    message.write(payload)
                    if (b0 and 0x80 != 0) {
                        onText(message.toString(Charsets.UTF_8))
                        message.reset()
                    }
                }
            }
        }
    }

    private fun InputStream.readHttpLine(): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = read()
            if (b < 0) return null
            if (b == '\n'.code) return line.toString(Charsets.US_ASCII).trimEnd('\r')
            line.write(b)
        }
    }

    private companion object {
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val OPCODE_CONTINUATION = 0x0
        const val OPCODE_TEXT = 0x1
        const val OPCODE_CLOSE = 0x8
    }
}
