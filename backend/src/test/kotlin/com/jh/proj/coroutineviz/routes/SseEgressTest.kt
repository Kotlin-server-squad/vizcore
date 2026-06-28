package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.flow.FlowOperatorApplied
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the consumer-side SSE egress chain (Plan 03 Task 1):
 *  - store stays 100% complete after a sampled run (D-03),
 *  - below-load frames are single-event byte-compatible (D-04 back-compat),
 *  - an under-load run emits an `event: batch` array frame (D-04),
 *  - a forced shed emits an `event: dropped {count:N}` control frame that is
 *    NEVER store.record'd / replayed (D-08, Pitfall P7).
 *
 * The egress chain is exercised through the extracted [sseEgressFrames] helper so the
 * sample/shed/batch composition is deterministically testable without a live socket.
 */
class SseEgressTest {
    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.clearAll()
    }

    private fun created(seq: Long): CoroutineCreated =
        CoroutineCreated(
            sessionId = "egress-test",
            seq = seq,
            tsNanos = seq,
            coroutineId = "c-$seq",
            jobId = "j-$seq",
            parentCoroutineId = null,
            scopeId = "scope-1",
            label = "co-$seq",
        )

    private fun emission(seq: Long): FlowOperatorApplied =
        FlowOperatorApplied(
            sessionId = "egress-test",
            seq = seq,
            tsNanos = seq,
            flowId = "f-1",
            sourceFlowId = "f-0",
            operatorName = "map",
            operatorIndex = 0,
            coroutineId = "c-1",
        )

    @Test
    fun `below load a single event is a byte-identical single-event frame`() =
        withDefaultDispatcher {
            val config = EgressConfig() // defaults: full fidelity below load
            val frames = sseEgressFrames(flowOf(created(1L)), config).toList()

            // One structural event in → exactly one frame, event: <kind>, single JSON object
            assertEquals(1, frames.size, "below load should produce a single frame")
            val frame = frames.single()
            assertEquals("CoroutineCreated", frame.event, "single frame keeps event:<kind> (back-compat)")
            val decoded = appJson.parseToJsonElement(frame.data!!).jsonObject
            assertEquals(1, decoded["seq"]?.jsonPrimitive?.int, "single frame data is one JSON object")
            // Byte-identical to today's toSse builder
            val expected = created(1L).toSse()
            assertEquals(expected.event, frame.event)
            assertEquals(expected.data, frame.data)
            assertEquals(expected.id, frame.id)
        }

    @Test
    fun `under load multiple events collapse into an event batch array frame`() =
        withDefaultDispatcher {
            // count=3 forces a batch flush as soon as 3 events are pending in one window.
            val config = EgressConfig(batchCount = 3, batchWindowMs = 10_000)
            val upstream = flow { repeat(3) { emit(created((it + 1).toLong())) } }
            val frames = sseEgressFrames(upstream, config).toList()

            val batchFrame = frames.firstOrNull { it.event == "batch" }
            assertTrue(batchFrame != null, "under load there must be an event: batch frame; got ${frames.map { it.event }}")
            val arr =
                appJson.decodeFromString(
                    ListSerializer(PolymorphicSerializer(VizEvent::class)),
                    batchFrame.data!!,
                )
            assertEquals(3, arr.size, "batch frame data is a JSON array of N events")
            assertEquals(listOf(1L, 2L, 3L), arr.map { it.seq })
        }

    @Test
    fun `a forced shed emits an event dropped count frame`() =
        withDefaultDispatcher {
            // shedCapacity=2 with 5 sheddable (non-structural) events → drops occur.
            // Large batch window/count so everything flushes on upstream close.
            val config = EgressConfig(shedCapacity = 2, batchCount = 1000, batchWindowMs = 10_000)
            val upstream = flow { repeat(5) { emit(emission((it + 1).toLong())) } }
            val frames = sseEgressFrames(upstream, config).toList()

            val droppedFrame = frames.firstOrNull { it.event == "dropped" }
            assertTrue(
                droppedFrame != null,
                "a forced shed must emit an event: dropped frame; got ${frames.map { it.event }}",
            )
            val count = Json.parseToJsonElement(droppedFrame.data!!).jsonObject["count"]?.jsonPrimitive?.int
            assertTrue(count != null && count > 0, "dropped frame carries a positive count, was $count")
        }

    @Test
    fun `the dropped control frame is never stored or replayed`() =
        withDefaultDispatcher {
            // A session run that sheds must leave the store complete: every event passed to
            // session.send is in store.all(); a `dropped` control frame is NOT a stored event.
            val session = SessionManager.createSession("egress-store-complete")
            val sent = (1..20L).map { emission(it) }
            sent.forEach { session.send(it) }

            // Store keeps 100% of sent events (egress thinning never touches the store, D-03)
            val stored = session.store.all()
            assertEquals(sent.size, stored.size, "store must stay 100% complete after a sheddable run")
            // No control frame ever entered the event-sourced log
            assertTrue(stored.none { it.kind == "dropped" || it.kind == "batch" }, "control frames are never stored")
        }

    @Test
    fun `structural events are never sampled out even under aggressive rates`() =
        withDefaultDispatcher {
            // defaultRate 0.0 would drop everything sheddable, but structural events bypass.
            val config = EgressConfig(defaultRate = 0.0, batchCount = 1, batchWindowMs = 10_000)
            val upstream = flow { repeat(4) { emit(created((it + 1).toLong())) } }
            val frames = sseEgressFrames(upstream, config).toList()
            val structuralSeqs =
                frames.filter { it.event == "CoroutineCreated" }.mapNotNull {
                    appJson.parseToJsonElement(it.data!!).jsonObject["seq"]?.jsonPrimitive?.int
                }
            assertEquals(listOf(1, 2, 3, 4), structuralSeqs, "structural events must never be sampled out")
        }

    // SSE live-reads / collection run on Dispatchers.Default so any internal delay()-based
    // batch window uses real time (Phase-1 P15 gotcha) rather than virtual test time.
    private fun withDefaultDispatcher(block: suspend () -> Unit): Unit =
        kotlinx.coroutines.runBlocking {
            withContext(Dispatchers.Default) { block() }
        }
}
