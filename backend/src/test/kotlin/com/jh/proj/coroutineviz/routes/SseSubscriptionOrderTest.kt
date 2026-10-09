package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.session.EventBus
import com.jh.proj.coroutineviz.session.EventStore
import com.jh.proj.coroutineviz.session.EventStoreInterface
import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * #138: the SSE stream must register its live bus subscription BEFORE it snapshots the
 * store for replay, deliver every event emitted during setup exactly once (in replay or
 * live), and the bus must count the events it evicts for a lagging subscriber instead of
 * dropping them silently.
 */
class SseSubscriptionOrderTest {
    private val sessions = mutableListOf<VizSession>()

    /** Egress with full fidelity and room to spare, so nothing is sampled or shed. */
    private val fullFidelity = EgressConfig(adaptive = false, shedCapacity = 100_000)

    @AfterEach
    fun tearDown() {
        sessions.forEach { it.close() }
        sessions.clear()
    }

    private fun track(session: VizSession): VizSession = session.also { sessions += it }

    private fun event(
        sessionId: String,
        id: String,
        seq: Long,
    ): CoroutineCreated =
        CoroutineCreated(
            sessionId = sessionId,
            seq = seq,
            tsNanos = System.nanoTime(),
            coroutineId = id,
            jobId = "job-$id",
            parentCoroutineId = null,
            scopeId = "scope-sse-order-test",
            label = null,
        )

    private fun VizSession.emit(id: String) = send(event(sessionId, id, nextSeq()))

    /** Decode a frame into the events it carries (single-event or `event: batch`). */
    private fun eventsOf(frame: ServerSentEvent): List<VizEvent> {
        val data = frame.data ?: return emptyList()
        return when (frame.event) {
            "batch" -> appJson.decodeFromString(ListSerializer(PolymorphicSerializer(VizEvent::class)), data)
            "dropped" -> fail("no event may be shed in this test, got dropped frame: $data")
            else -> listOf(appJson.decodeFromString(PolymorphicSerializer(VizEvent::class), data))
        }
    }

    @Test
    fun `live subscription is registered before the replay snapshot is taken`(): Unit =
        runBlocking {
            val subscribersAtSnapshot = CopyOnWriteArrayList<Int>()
            val armed = AtomicBoolean(false)
            lateinit var session: VizSession
            session =
                track(
                    VizSession("sse-order-snapshot") { _ ->
                        val delegate = EventStore()
                        object : EventStoreInterface by delegate {
                            override fun all(): List<VizEvent> {
                                if (armed.get()) subscribersAtSnapshot += session.bus.subscriptionCount.value
                                return delegate.all()
                            }
                        }
                    },
                )
            repeat(3) { session.emit("pre-$it") }
            val baseline = session.bus.subscriptionCount.value
            armed.set(true)

            val replayed =
                withTimeout(10_000) {
                    sessionSseFrames(session, fullFidelity).take(3).toList()
                }

            assertEquals(3, replayed.size)
            assertEquals(
                listOf("pre-0", "pre-1", "pre-2"),
                replayed.flatMap(::eventsOf).map { (it as CoroutineCreated).coroutineId },
            )
            assertEquals(1, subscribersAtSnapshot.size, "the SSE stream snapshots the store exactly once")
            assertTrue(
                subscribersAtSnapshot.single() > baseline,
                "the SSE live subscriber must be registered before the snapshot " +
                    "(baseline=$baseline, at snapshot=${subscribersAtSnapshot.single()})",
            )
        }

    @Test
    fun `events emitted during SSE setup are delivered exactly once`(): Unit =
        runBlocking {
            val session = track(VizSession("sse-order-exactly-once"))
            val preExisting = 50
            val concurrent = 2_000
            repeat(preExisting) { session.emit("pre-$it") }

            val start = CompletableDeferred<Unit>()
            val received =
                async(Dispatchers.Default) {
                    start.await()
                    sessionSseFrames(session, fullFidelity)
                        .transformWhile { frame ->
                            val events = eventsOf(frame)
                            events.forEach { emit(it) }
                            events.none { (it as CoroutineCreated).coroutineId == "final" }
                        }.toList()
                }
            val emitter =
                launch(Dispatchers.Default) {
                    start.await()
                    repeat(concurrent) { session.emit("live-$it") }
                    session.emit("final")
                }
            start.complete(Unit)

            val events = withTimeout(30_000) { received.await() }
            emitter.join()

            val seqs = events.map { it.seq }
            val storedSeqs = session.store.all().map { it.seq }
            assertEquals(preExisting + concurrent + 1, storedSeqs.size)
            assertEquals(seqs.size, seqs.toSet().size, "an event was delivered twice")
            assertEquals(storedSeqs.toSet(), seqs.toSet(), "an event was lost between replay and live")
            seqs.zipWithNext().forEach { (a, b) ->
                assertTrue(b > a, "SSE delivery must be in seq order, found $a then $b")
            }
        }

    @Test
    fun `bus counts the events it evicts for a stalled subscriber`(): Unit =
        runBlocking {
            val bus = EventBus(capacity = 16)
            val evicted = AtomicLong()
            bus.onEvicted = { missed -> evicted.addAndGet(missed) }
            val total = 500L

            val subscribed = CompletableDeferred<Unit>()
            val stalled = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val received = AtomicLong()
            val collector =
                launch(Dispatchers.Default) {
                    bus
                        .stream(onSubscribed = { subscribed.complete(Unit) })
                        .transformWhile { event ->
                            received.incrementAndGet()
                            if (event.seq == 1L) {
                                stalled.complete(Unit)
                                release.await()
                            }
                            emit(event)
                            event.seq != total
                        }.collect {}
                }

            withTimeout(10_000) {
                subscribed.await()
                bus.send(event("bus", "e-1", 1))
                stalled.await()
                for (seq in 2..total) {
                    bus.send(event("bus", "e-$seq", seq))
                }
                release.complete(Unit)
                collector.join()
            }

            assertTrue(evicted.get() > 0, "a stalled subscriber behind a 16-event buffer must see evictions")
            assertEquals(total - received.get(), evicted.get(), "every event not received is counted as evicted")
        }

    @Test
    fun `a subscriber within the bus capacity reports no evictions`(): Unit =
        runBlocking {
            val total = 200
            val bus = EventBus(capacity = total)
            val evicted = AtomicLong()
            bus.onEvicted = { missed -> evicted.addAndGet(missed) }

            val subscribed = CompletableDeferred<Unit>()
            val received =
                async(Dispatchers.Default) {
                    bus.stream(onSubscribed = { subscribed.complete(Unit) }).take(total).toList()
                }
            val events =
                withTimeout(10_000) {
                    subscribed.await()
                    for (seq in 1..total) {
                        bus.send(event("bus", "k-$seq", seq.toLong()))
                    }
                    received.await()
                }

            assertEquals((1L..total).toList(), events.map { it.seq })
            assertEquals(0L, evicted.get())
        }
}
