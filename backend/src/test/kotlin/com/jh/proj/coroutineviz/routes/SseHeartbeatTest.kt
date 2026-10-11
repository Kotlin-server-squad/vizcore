package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /api/sessions/{id}/stream` must keep sending a named `heartbeat` event for as long as
 * the stream is open, so a client can tell a quiet session from a dead connection (a local UAT
 * of PR #166 found a killed backend leaving the SPA showing "Connected").
 *
 * It has to be a NAMED event, not an SSE comment: browser `EventSource` drops comments, so the
 * SPA watchdog would never observe one. And it is egress-only — no `seq`, no SSE `id`, never
 * stored, so it can neither appear in `/events`/replay nor become a `Last-Event-ID` resume point.
 *
 * Every test here runs with `sse.heartbeatSeconds: 1` in the application config and asserts on
 * sub-3-second waits; with the built-in 15 s default none of them could pass, so these also
 * prove the route reads the config key.
 */
class SseHeartbeatTest {
    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.clearAll()
    }

    /** The module with the heartbeat interval shortened to 1 s (same idiom as RateLimitPolicyTest). */
    private fun ApplicationTestBuilder.oneSecondHeartbeats() {
        environment {
            config = MapApplicationConfig("sse.heartbeatSeconds" to "1")
        }
        application { module() }
    }

    private fun ApplicationTestBuilder.jsonClient() =
        createClient {
            install(ContentNegotiation) { json() }
        }

    private fun VizSession.emit(coroutineId: String) =
        send(
            CoroutineCreated(
                sessionId = sessionId,
                seq = nextSeq(),
                tsNanos = System.nanoTime(),
                coroutineId = coroutineId,
                jobId = "j-$coroutineId",
                parentCoroutineId = null,
                scopeId = "scope-sse-heartbeat",
                label = null,
            ),
        )

    private data class SseFrame(
        val event: String?,
        val data: String?,
        val id: String?,
    )

    /**
     * Read one SSE frame off the wire, or null at end of stream. Comment-only frames (the
     * `: connected` flush frame) are skipped — they carry no field, exactly as a browser
     * `EventSource` sees them.
     */
    private suspend fun ByteReadChannel.readFrame(): SseFrame? {
        var event: String? = null
        var id: String? = null
        val data = StringBuilder()
        var fields = 0
        while (true) {
            val line = readUTF8Line() ?: return null
            when {
                line.isBlank() ->
                    if (fields > 0) return SseFrame(event, data.toString().ifEmpty { null }, id)
                line.startsWith("event:") -> event = line.substringAfter(':').trim().also { fields++ }
                line.startsWith("data:") -> data.append(line.substringAfter(':').trim()).also { fields++ }
                line.startsWith("id:") -> id = line.substringAfter(':').trim().also { fields++ }
            }
        }
    }

    /**
     * Read frames until [enough] accepts the frames seen so far (or the stream ends), calling
     * [onFrame] after each one so a test can drive the session while the stream is being read.
     */
    private suspend fun ByteReadChannel.framesUntil(
        onFrame: (List<SseFrame>) -> Unit = {},
        enough: (List<SseFrame>) -> Boolean,
    ): List<SseFrame> {
        val frames = mutableListOf<SseFrame>()
        while (!enough(frames)) {
            frames += readFrame() ?: break
            onFrame(frames)
        }
        return frames
    }

    /** The `seq` values a data frame carries: one per event, none for a heartbeat. */
    private fun seqsOf(frame: SseFrame): List<Long> {
        val element = Json.parseToJsonElement(frame.data ?: return emptyList())
        // One event object, or a JSON array for an `event: batch` frame.
        val objects = if (element is JsonArray) element.map { it.jsonObject } else listOf(element.jsonObject)
        return objects.mapNotNull { it["seq"]?.jsonPrimitive?.long }
    }

    private fun tsOf(frame: SseFrame): Long =
        Json
            .parseToJsonElement(assertNotNull(frame.data, "a heartbeat must carry data"))
            .jsonObject["ts"]
            ?.jsonPrimitive
            ?.long
            ?: error("a heartbeat must carry an epoch-ms ts, got ${frame.data}")

    @Test
    fun `an idle stream keeps receiving heartbeats`() =
        testApplication {
            oneSecondHeartbeats()
            val client = jsonClient()
            val sessionId = SessionManager.createSession("hb-idle").sessionId

            // Real time: testApplication's dispatcher uses virtual time, which would fire the
            // timeout while the server streams on wall-clock time (SseStreamTest convention).
            withContext(Dispatchers.Default) {
                client.prepareGet("/api/sessions/$sessionId/stream").execute { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    val channel = response.bodyAsChannel()

                    val first = withTimeout(3_000) { channel.readFrame() }
                    assertNotNull(first, "an idle stream must produce a frame without any event")
                    assertEquals(
                        "heartbeat",
                        first.event,
                        "the only frame an idle stream can produce is a named heartbeat, got $first",
                    )
                    assertTrue(tsOf(first) > 0, "a heartbeat must carry epoch millis, got ${first.data}")

                    val second = withTimeout(3_000) { channel.readFrame() }
                    assertNotNull(second, "heartbeats must keep coming for as long as the stream is open")
                    assertEquals("heartbeat", second.event, "the second frame must be a heartbeat too, got $second")
                    assertTrue(
                        tsOf(second) >= tsOf(first),
                        "each heartbeat carries its own send time (${tsOf(first)} then ${tsOf(second)})",
                    )
                }
            }
        }

    @Test
    fun `a stream carrying events still receives heartbeats`() =
        testApplication {
            oneSecondHeartbeats()
            val client = jsonClient()
            val session = SessionManager.createSession("hb-with-events")
            session.emit("c-1")
            session.emit("c-2")

            var liveSent = false
            val frames =
                withContext(Dispatchers.Default) {
                    withTimeout(20_000) {
                        client.prepareGet("/api/sessions/${session.sessionId}/stream").execute { response ->
                            assertEquals(HttpStatusCode.OK, response.status)
                            response.bodyAsChannel().framesUntil(
                                onFrame = { seen ->
                                    // Both stored events have been replayed, so the live
                                    // subscription is attached: this one arrives live.
                                    if (!liveSent && seen.flatMap(::seqsOf).size == 2) {
                                        session.emit("c-live")
                                        liveSent = true
                                    }
                                },
                            ) { seen ->
                                seen.flatMap(::seqsOf).size >= 3 && seen.count { it.event == "heartbeat" } >= 2
                            }
                        }
                    }
                }

            assertEquals(
                listOf(1L, 2L, 3L),
                frames.flatMap(::seqsOf),
                "replay then live events must still be delivered in seq order alongside heartbeats",
            )
            val lastEvent = frames.indexOfLast { seqsOf(it).isNotEmpty() }
            assertTrue(
                frames.drop(lastEvent + 1).any { it.event == "heartbeat" },
                "a heartbeat must follow the last event frame, got ${frames.drop(lastEvent + 1)}",
            )
        }

    @Test
    fun `heartbeats carry no seq and are never stored`() =
        testApplication {
            oneSecondHeartbeats()
            val client = jsonClient()
            val session = SessionManager.createSession("hb-not-stored")
            session.emit("c-1")

            val frames =
                withContext(Dispatchers.Default) {
                    withTimeout(20_000) {
                        client.prepareGet("/api/sessions/${session.sessionId}/stream").execute { response ->
                            response.bodyAsChannel().framesUntil { seen ->
                                seen.count { it.event == "heartbeat" } >= 2
                            }
                        }
                    }
                }

            val heartbeats = frames.filter { it.event == "heartbeat" }
            assertEquals(2, heartbeats.size)
            heartbeats.forEach { frame ->
                val payload = Json.parseToJsonElement(assertNotNull(frame.data)).jsonObject
                assertEquals(setOf("ts"), payload.keys, "a heartbeat carries nothing but its ts, got $frame")
                assertEquals(emptyList(), seqsOf(frame), "a heartbeat must carry no seq")
                assertNull(frame.id, "a heartbeat must carry no SSE id — it is not a resume point")
            }

            // The store never saw them, so neither /events nor a replay can.
            val body = client.get("/api/sessions/${session.sessionId}/events").bodyAsText()
            val stored = Json.parseToJsonElement(body).jsonArray
            assertEquals(1, stored.size, "only the one real event may be stored, got $body")
            assertTrue(
                stored.all { it.jsonObject["seq"] != null },
                "every /events entry is a stored event with a seq, got $body",
            )
            assertTrue(
                stored.none { it.jsonObject["type"]?.jsonPrimitive?.content == "heartbeat" },
                "/events must contain no heartbeat entry, got $body",
            )
            assertEquals(1, session.store.all().size, "the heartbeat must never reach the store")
        }
}
