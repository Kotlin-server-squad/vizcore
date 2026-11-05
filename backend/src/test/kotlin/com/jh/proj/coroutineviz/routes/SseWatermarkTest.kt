package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.health.WatermarkObservation
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.routes.HealthEvents.sec
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.websocket.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SseWatermarkTest {
    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
        IngestStates.clear()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.clearAll()
        IngestStates.clear()
    }

    /** Reads lines until one equals `event: [name]`; returns the next `data:` value, or null at end of stream. */
    private suspend fun ByteReadChannel.dataOfNext(name: String): String? {
        var inEvent = false
        var line = readUTF8Line()
        while (line != null && !(inEvent && line.startsWith("data:"))) {
            if (line.trim() == "event: $name") inEvent = true
            line = readUTF8Line()
        }
        return line?.substringAfter(':')?.trim()
    }

    @Test
    fun `a watermark from the app reaches the session's stream as a watermark frame`() =
        testApplication {
            environment { config = MapApplicationConfig("sse.heartbeatSeconds" to "1") }
            application { module() }
            val ws = createClient { install(WebSockets) }
            val sessionId = ws.createSessionNamed("sse-wm")

            withContext(Dispatchers.Default) {
                createClient {}.prepareGet("/api/sessions/$sessionId/stream").execute { response ->
                    val channel = response.bodyAsChannel()
                    // The stream is attached once its first heartbeat arrives.
                    withTimeout(3_000) { channel.dataOfNext("heartbeat") }

                    ws.webSocket("/api/sessions/$sessionId/ingest") {
                        send(Frame.Text(HealthEvents.watermark(sessionId, at = sec(4)).frameText()))
                        val data = assertNotNull(withTimeout(5_000) { channel.dataOfNext("watermark") })
                        val observation = appJson.decodeFromString(WatermarkObservation.serializer(), data)
                        assertEquals(sec(4), observation.appNanos)
                        assertEquals(1_792_000_000_000, observation.appEpochMs)
                    }
                }
            }
        }
}
