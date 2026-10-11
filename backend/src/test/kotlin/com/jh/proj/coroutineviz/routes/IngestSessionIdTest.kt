package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.sse.ServerSentEvent
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every event that arrives through `/api/sessions/{id}/ingest` must be stored, replayed
 * and streamed under the SERVER-resolved session's id, whatever `sessionId` the frame
 * body carried (T-07-03). A local UAT of the previous behaviour found all 210 ingested
 * events still carrying the client's value: the publish targeted the right session, but
 * the event object kept the wire id, so the stored/replayed payload disagreed with the
 * session it lived in and a frame could label its events with another session's id.
 *
 * The frames here claim (a) another existing session and (b) a session that does not
 * exist; both must land in the target session with the target's id, and the session named
 * in the body must receive nothing. Seq assignment (#127) is unchanged — the server still
 * numbers the events.
 */
class IngestSessionIdTest {
    /** Egress with full fidelity and room to spare, so no SSE frame is sampled or shed. */
    private val fullFidelity = EgressConfig(adaptive = false, shedCapacity = 100_000)

    /** Keep-alive frames are SseHeartbeatTest's concern; keep them out of these frames. */
    private val noHeartbeat = 10 * 60 * 1000L

    private val ghostSessionId = "no-such-session-0000"

    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.clearAll()
    }

    private fun ApplicationTestBuilder.wsClient() =
        createClient {
            install(ContentNegotiation) { json(appJson) }
            install(WebSockets)
        }

    /** An event whose body claims [sessionId] — the value the server must overwrite. */
    private fun event(
        sessionId: String,
        coroutineId: String,
    ): CoroutineCreated =
        CoroutineCreated(
            sessionId = sessionId,
            seq = 1L,
            tsNanos = 0L,
            coroutineId = coroutineId,
            jobId = "j-$coroutineId",
            parentCoroutineId = null,
            scopeId = "scope-ingest-session-id",
            label = "claims $sessionId",
        )

    private fun frameText(e: VizEvent): String = appJson.encodeToString(PolymorphicSerializer(VizEvent::class), e)

    private suspend fun createSession(
        client: HttpClient,
        name: String,
    ): String {
        val resp = client.post("/api/sessions?name=$name")
        assertEquals(HttpStatusCode.Created, resp.status)
        return Json
            .parseToJsonElement(resp.bodyAsText())
            .jsonObject["sessionId"]!!
            .jsonPrimitive.content
    }

    private suspend fun events(
        client: HttpClient,
        sessionId: String,
    ): JsonArray {
        val resp = client.get("/api/sessions/$sessionId/events")
        assertEquals(HttpStatusCode.OK, resp.status)
        return Json.parseToJsonElement(resp.bodyAsText()).jsonArray
    }

    /**
     * Read /events for [sessionId] until [expected] events are present. The ingest publish
     * is asynchronous relative to the client closing the WebSocket, so a single read can
     * race ahead of the server consuming the frame (same polling idiom as `IngestRoutesTest`).
     */
    private suspend fun eventsWhenSettled(
        client: HttpClient,
        sessionId: String,
        expected: Int,
    ): JsonArray {
        var arr = JsonArray(emptyList())
        repeat(100) {
            arr = events(client, sessionId)
            if (arr.size >= expected) return arr
            delay(20)
        }
        return arr
    }

    private fun JsonObject.sessionId(): String? = this["sessionId"]?.jsonPrimitive?.content

    private fun JsonObject.coroutineId(): String? = this["coroutineId"]?.jsonPrimitive?.content

    /** Decode an SSE frame into the events it carries (single-event or `event: batch`). */
    private fun eventsOf(frame: ServerSentEvent): List<VizEvent> {
        val data = frame.data ?: return emptyList()
        return when (frame.event) {
            "batch" -> appJson.decodeFromString(ListSerializer(PolymorphicSerializer(VizEvent::class)), data)
            "dropped" -> fail("no event may be shed in this test, got dropped frame: $data")
            else -> listOf(appJson.decodeFromString(PolymorphicSerializer(VizEvent::class), data))
        }
    }

    /** Send both forged frames on one ingest socket opened against [targetSessionId]. */
    private suspend fun ingestForgedFrames(
        client: HttpClient,
        targetSessionId: String,
        otherSessionId: String,
    ) {
        client.webSocket("/api/sessions/$targetSessionId/ingest") {
            send(Frame.Text(frameText(event(otherSessionId, "c-claims-other"))))
            send(Frame.Text(frameText(event(ghostSessionId, "c-claims-ghost"))))
        }
    }

    @Test
    fun `events ingested with a foreign sessionId are stored under the target session`() =
        testApplication {
            application { module() }
            val client = wsClient()

            val target = createSession(client, "ingest-target")
            val other = createSession(client, "ingest-other")

            ingestForgedFrames(client, targetSessionId = target, otherSessionId = other)

            val stored = eventsWhenSettled(client, target, expected = 2)
            assertEquals(2, stored.size, "both forged frames must land in the target session")
            assertEquals(
                listOf("c-claims-other", "c-claims-ghost"),
                stored.map { it.jsonObject.coroutineId() },
            )
            stored.forEach { e ->
                assertEquals(
                    target,
                    e.jsonObject.sessionId(),
                    "a stored event must carry the server-resolved session id, not the frame's: ${e.jsonObject}",
                )
            }
            // The id in the body never selects a destination: the session it named is untouched.
            assertEquals(0, events(client, other).size, "the session named in the frame body must receive nothing")

            // Seq assignment (#127) is unchanged: the server still numbers the events.
            val seqs =
                stored.map {
                    it.jsonObject["seq"]!!
                        .jsonPrimitive.content
                        .toLong()
                }
            assertTrue(seqs.zipWithNext().all { (a, b) -> b > a }, "server-assigned seq must be strictly increasing: $seqs")
        }

    @Test
    fun `SSE replay and live frames carry the server-resolved session id`() =
        testApplication {
            application { module() }
            val client = wsClient()

            val target = createSession(client, "ingest-sse-target")
            val other = createSession(client, "ingest-sse-other")

            // Replay half: both forged frames are already stored when the stream opens.
            ingestForgedFrames(client, targetSessionId = target, otherSessionId = other)
            eventsWhenSettled(client, target, expected = 2)

            val session = SessionManager.getSession(target) ?: fail("session $target must exist")
            val replaySeen = CompletableDeferred<Unit>()
            val delivered =
                coroutineScope {
                    val collected =
                        async(Dispatchers.Default) {
                            sessionSseFrames(session, fullFidelity, heartbeatMillis = noHeartbeat)
                                .transformWhile { frame ->
                                    val events = eventsOf(frame)
                                    events.forEach { emit(it) }
                                    if (events.isNotEmpty()) replaySeen.complete(Unit)
                                    // The live frame is the last one this test needs.
                                    events.none { (it as CoroutineCreated).coroutineId == "c-live" }
                                }.toList()
                        }

                    // sessionSseFrames subscribes to the bus BEFORE it snapshots the store (#138), so
                    // a replayed frame proves the live subscription is already registered: anything
                    // ingested from here on arrives live, not in the replay snapshot.
                    withTimeout(SSE_TIMEOUT_MS) { replaySeen.await() }

                    // Live half: one more forged frame while the SSE subscriber is attached.
                    client.webSocket("/api/sessions/$target/ingest") {
                        send(Frame.Text(frameText(event(other, "c-live"))))
                    }

                    withTimeout(SSE_TIMEOUT_MS) { collected.await() }
                }

            // Exactly-once, never strict seq order, on SSE (live delivery is lifecycle-first).
            assertEquals(
                setOf("c-claims-other", "c-claims-ghost", "c-live"),
                delivered.map { (it as CoroutineCreated).coroutineId }.toSet(),
                "replay and live frames together must deliver every ingested event",
            )
            assertEquals(delivered.size, delivered.map { it.seq }.toSet().size, "an event was delivered twice")
            delivered.forEach { e ->
                assertEquals(
                    target,
                    e.sessionId,
                    "an SSE-delivered event must carry the server-resolved session id, not the frame's: $e",
                )
            }
            assertEquals(0, events(client, other).size, "the session named in the frame body must receive nothing")
        }

    @Test
    fun `the SSE frame id of an ingested event is prefixed with the server-resolved session`() =
        testApplication {
            application { module() }
            val client = wsClient()

            val target = createSession(client, "ingest-sse-id-target")
            val other = createSession(client, "ingest-sse-id-other")

            ingestForgedFrames(client, targetSessionId = target, otherSessionId = other)
            eventsWhenSettled(client, target, expected = 2)

            val session = SessionManager.getSession(target) ?: fail("session $target must exist")
            val frames =
                withTimeout(SSE_TIMEOUT_MS) {
                    withContext(Dispatchers.Default) {
                        var seen = 0
                        sessionSseFrames(session, fullFidelity, heartbeatMillis = noHeartbeat)
                            .transformWhile { frame ->
                                if (frame.data != null) {
                                    emit(frame)
                                    seen += eventsOf(frame).size
                                }
                                seen < 2
                            }.toList()
                    }
                }

            // `VizEvent.toSse` builds the SSE id as "$sessionId-$seq" from the event itself, so a
            // stale wire id would reappear in the resume id the frontend reconnects with.
            frames.forEach { frame ->
                assertTrue(
                    frame.id?.startsWith("$target-") == true,
                    "the SSE id must be prefixed with the server-resolved session, got ${frame.id}",
                )
            }
        }

    private companion object {
        /** Generous: these waits only fire when the SSE path is genuinely broken. */
        const val SSE_TIMEOUT_MS = 30_000L
    }
}
