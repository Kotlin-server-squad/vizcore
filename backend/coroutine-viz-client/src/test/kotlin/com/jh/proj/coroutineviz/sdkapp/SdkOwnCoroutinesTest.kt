package com.jh.proj.coroutineviz.sdkapp

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.client.VizcoreClient
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.session.source.debugprobes.DebugProbesInstall
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.serialization.PolymorphicSerializer
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The SDK leaves its own coroutines out of the app's session (#225): its client loops, the
 * DebugProbes poll loop, the session projections, and the coroutines Ktor runs for its
 * connection. The test lives outside the SDK's packages, as an app does.
 *
 * The fake backend is a plain socket server on threads, so it adds no coroutines of its own, and
 * DebugProbes are installed before the client exists, so the client's startup is observed too.
 */
class SdkOwnCoroutinesTest {
    @Test
    fun `the session receives the app's coroutines and not the SDK's own`() {
        DebugProbesInstall.acquire()
        var server: FakeBackend? = null
        var client: VizcoreClient? = null
        val app = Job()
        var onSessionScope: Job? = null
        try {
            val backend = FakeBackend().also { server = it }
            val started = VizcoreClient.start(appName = "own", backendUrl = "http://localhost:${backend.port}", token = "t")
            client = started
            // Launched from a scope the app owns and from a scope the SDK hands out: both are app work.
            CoroutineScope(Dispatchers.Default + app).launch(CoroutineName("app-work")) { awaitCancellation() }
            onSessionScope = started.session.sessionScope.launch(CoroutineName("app-on-session-scope")) { awaitCancellation() }

            backend.awaitLabel("app-work")
            backend.awaitLabel("app-on-session-scope")
            // A few more polls, so anything the SDK started late would have arrived too.
            Thread.sleep(1_000)

            val sdkFiles =
                setOf(
                    "VizcoreClient.kt", "IngestTransport.kt", "OutboundBuffer.kt", "DebugProbesSource.kt",
                    "EventBus.kt", "ProjectionService.kt", "MetricsProjection.kt", "JobStatusMonitor.kt",
                )
            assertEquals(emptyList(), backend.created.filter { it.fileName in sdkFiles }, "coroutines the SDK launched")
            // Everything else Ktor runs for the SDK's connection is left out.
            val stillVisible = setOf("cio-from-nio-reader", "cio-to-nio-writer", "nonce-generator")
            val ktor =
                backend.created.mapNotNull { it.label }.filter {
                    it.startsWith("ws-") || it.startsWith("cio-") || it.startsWith("raw-ws") ||
                        it in setOf("selector", "ktor-cio-context", "call-context", "nonce-generator")
                }
            assertEquals(emptyList(), ktor.filter { it !in stillVisible }, "Ktor coroutines of the SDK's connection")
            assertTrue(backend.created.any { it.label == "app-work" })
        } finally {
            app.cancel()
            onSessionScope?.cancel()
            client?.stop()
            server?.close()
            DebugProbesInstall.release()
        }
    }

    private data class Created(val label: String?, val fileName: String?)

    /** Session creation and the ingest WebSocket, served from a thread per connection. */
    private class FakeBackend : AutoCloseable {
        private val server = ServerSocket(0)
        val port: Int get() = server.localPort
        private val events = ConcurrentHashMap<String, Created>()
        val created: List<Created> get() = events.values.toList()

        init {
            thread(isDaemon = true, name = "fake-backend") {
                while (true) {
                    val socket =
                        try {
                            server.accept()
                        } catch (e: SocketException) {
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
                    val body = """{"sessionId":"own-1","message":"ok"}"""
                    out.write(
                        ("HTTP/1.1 201 Created\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray(),
                    )
                }
                requestLine.startsWith("GET /api/sessions/own-1/ingest") -> {
                    val accept =
                        Base64.getEncoder().encodeToString(
                            MessageDigest.getInstance("SHA-1")
                                .digest((headers.getValue("sec-websocket-key") + WEBSOCKET_GUID).toByteArray()),
                        )
                    out.write(
                        ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(),
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
}
