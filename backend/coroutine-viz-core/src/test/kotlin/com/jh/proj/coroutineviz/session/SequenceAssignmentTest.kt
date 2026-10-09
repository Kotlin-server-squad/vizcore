package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #127: the server, not the client, assigns the seq of every ingested event.
 *
 * [VizSession.ingest] must discard whatever seq a remote client put on the wire
 * and assign `max(seqGenerator, lastSentSeq) + 1` under the send lock, sharing one
 * counter with in-process [VizSession.send]. Otherwise a restarted client (seq
 * starting at 1 again), a decreasing or duplicate seq, or a `Long.MAX_VALUE` seq
 * would make the session's order non-monotonic, and the max-seq watermark used by
 * SSE replay would silently hide events.
 */
class SequenceAssignmentTest {
    private val sessions = mutableListOf<VizSession>()

    @AfterEach
    fun tearDown() {
        sessions.forEach { it.close() }
        sessions.clear()
    }

    private fun session(id: String): VizSession = VizSession(id).also { sessions += it }

    private fun event(
        session: VizSession,
        id: String,
        seq: Long,
    ): CoroutineCreated =
        CoroutineCreated(
            sessionId = session.sessionId,
            seq = seq,
            tsNanos = System.nanoTime(),
            coroutineId = id,
            jobId = "job-$id",
            parentCoroutineId = null,
            scopeId = "scope-seq-test",
            label = null,
        )

    /** A remote event carrying the client's own [clientSeq]. */
    private fun remote(
        session: VizSession,
        id: String,
        clientSeq: Long,
    ): CoroutineCreated = event(session, id, clientSeq)

    /** A server-side event with a provisional seq from [VizSession.nextSeq]. */
    private fun local(
        session: VizSession,
        id: String,
    ): CoroutineCreated = event(session, id, session.nextSeq())

    private fun assertStrictlyIncreasing(
        seqs: List<Long>,
        what: String,
    ) {
        seqs.zipWithNext().forEach { (a, b) ->
            assertTrue(b > a, "$what: seq must be strictly increasing, found $a then $b")
        }
    }

    private fun storedIds(session: VizSession): List<String> =
        session.store.all().map { (it as CoroutineCreated).coroutineId }

    @Test
    fun `client reconnect restarting at seq 1 keeps all events with increasing seq`() {
        val session = session("seq-reconnect")
        (1L..100L).forEach { session.ingest(remote(session, "run1-$it", it)) }
        // A restarted client starts its own counter at 1 again.
        (1L..100L).forEach { session.ingest(remote(session, "run2-$it", it)) }

        val stored = session.store.all()
        assertEquals(200, stored.size, "both runs must be kept in full")
        assertEquals(
            (1L..100L).map { "run1-$it" } + (1L..100L).map { "run2-$it" },
            storedIds(session),
            "events must be stored in arrival order",
        )
        assertStrictlyIncreasing(stored.map { it.seq }, "reconnect")
        val watermarkAfterRun1 = stored[99].seq
        assertEquals(
            (1L..100L).map { "run2-$it" },
            session.store.since(watermarkAfterRun1).map { (it as CoroutineCreated).coroutineId },
            "the watermark after run 1 must not hide run 2",
        )
    }

    @Test
    fun `decreasing remote seq is kept in arrival order with server seq`() {
        val session = session("seq-decreasing")
        val clientSeqs = (50L downTo 1L).toList()
        clientSeqs.forEach { session.ingest(remote(session, "d-$it", it)) }

        val stored = session.store.all()
        assertEquals(clientSeqs.size, stored.size, "no decreasing-seq event may be dropped")
        assertEquals(clientSeqs.map { "d-$it" }, storedIds(session), "arrival order must be kept")
        assertEquals((1L..50L).toList(), stored.map { it.seq }, "seq is assigned by the server, densely")
    }

    @Test
    fun `duplicate remote seq values are all kept with distinct seq`() {
        val session = session("seq-duplicate")
        repeat(30) { i -> session.ingest(remote(session, "dup-$i", 7L)) }

        val seqs = session.store.all().map { it.seq }
        assertEquals(30, seqs.size, "every duplicate-seq event must be kept")
        assertEquals(30, seqs.distinct().size, "assigned seqs must be distinct")
        assertStrictlyIncreasing(seqs, "duplicate")
    }

    @Test
    fun `Long MAX_VALUE remote seq does not poison the session`(): Unit =
        runBlocking {
            val session = session("seq-max-value")
            val total = 20
            val before = session.bus.subscriptionCount.value
            val delivered =
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    session.bus.stream().take(total).toList()
                }
            withTimeout(5_000) { session.bus.subscriptionCount.first { it > before } }

            session.ingest(remote(session, "max-0", Long.MAX_VALUE))
            (1 until total).forEach { i ->
                val clientSeq = if (i % 2 == 0) Long.MAX_VALUE else i.toLong()
                session.ingest(remote(session, "max-$i", clientSeq))
            }

            val stored = session.store.all()
            assertEquals(total, stored.size, "events after a Long.MAX_VALUE seq must still be accepted")
            assertTrue(stored.all { it.seq in 1L..total.toLong() }, "no client seq may be kept, no overflow")
            assertStrictlyIncreasing(stored.map { it.seq }, "max-value")

            val live = withTimeout(5_000) { delivered.await() }
            assertEquals(
                (0 until total).map { "max-$it" },
                live.map { (it as CoroutineCreated).coroutineId },
                "every event must reach a live subscriber, in order",
            )
            assertStrictlyIncreasing(live.map { it.seq }, "max-value live")
        }

    @Test
    fun `ingest interleaved with server-side emission shares one counter`() {
        val session = session("seq-interleaved")
        val order = mutableListOf<String>()
        repeat(50) { i ->
            session.send(local(session, "local-$i"))
            order += "local-$i"
            // Remote seqs both far below and far above the server's counter.
            val clientSeq = if (i % 2 == 0) 1L else 1_000_000L + i
            session.ingest(remote(session, "remote-$i", clientSeq))
            order += "remote-$i"
        }

        val seqs = session.store.all().map { it.seq }
        assertEquals(order, storedIds(session), "store order must be emission order")
        assertEquals((1L..100L).toList(), seqs, "server and ingest seqs come from one dense counter")

        // A server event allocated after an ingest keeps its provisional seq,
        // because ingest advanced the shared generator.
        val next = local(session, "after")
        val provisional = next.seq
        session.send(next)
        assertEquals(provisional, next.seq)
        assertEquals(101L, next.seq)
    }

    @Test
    fun `concurrent ingest and server emission produce no duplicates`(): Unit =
        runBlocking {
            val session = session("seq-concurrent")
            val writers = 4
            val perWriter = 500
            val jobs =
                (1..writers).flatMap { w ->
                    listOf(
                        launch(Dispatchers.Default) {
                            repeat(perWriter) { i -> session.send(local(session, "l$w-$i")) }
                        },
                        launch(Dispatchers.Default) {
                            // Every remote writer replays the same client seqs (a fleet of
                            // restarted clients), including the extremes.
                            repeat(perWriter) { i ->
                                val clientSeq =
                                    when (i % 3) {
                                        0 -> i.toLong() + 1
                                        1 -> Long.MAX_VALUE
                                        else -> 1L
                                    }
                                session.ingest(remote(session, "r$w-$i", clientSeq))
                            }
                        },
                    )
                }
            jobs.joinAll()

            val stored = session.store.all()
            val seqs = stored.map { it.seq }
            assertEquals(2 * writers * perWriter, stored.size, "every event must be stored")
            assertEquals(seqs.size, seqs.distinct().size, "seqs must be unique")
            assertStrictlyIncreasing(seqs, "concurrent")
        }

    @Test
    fun `every event of a scenario is delivered through store replay and the live bus`(): Unit =
        runBlocking {
            val session = session("seq-readback")
            val total = 300
            val before = session.bus.subscriptionCount.value
            val delivered =
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    session.bus.stream().take(total).toList()
                }
            withTimeout(5_000) { session.bus.subscriptionCount.first { it > before } }

            // Scenario: server emission, a client run, a client restart, more server emission.
            val emitted = mutableListOf<String>()
            repeat(50) { i ->
                session.send(local(session, "s1-$i"))
                emitted += "s1-$i"
            }
            repeat(100) { i ->
                session.ingest(remote(session, "c1-$i", i.toLong() + 1))
                emitted += "c1-$i"
            }
            repeat(100) { i ->
                session.ingest(remote(session, "c2-$i", i.toLong() + 1))
                emitted += "c2-$i"
            }
            repeat(50) { i ->
                session.send(local(session, "s2-$i"))
                emitted += "s2-$i"
            }

            // Replay path: a client that has seen nothing reads everything after seq 0.
            val replay = session.store.since(0)
            assertEquals(emitted, replay.map { (it as CoroutineCreated).coroutineId })
            assertStrictlyIncreasing(replay.map { it.seq }, "replay")

            // Watermark resume: a client that saw the first half gets exactly the rest.
            val watermark = replay[total / 2 - 1].seq
            assertEquals(
                emitted.drop(total / 2),
                session.store.since(watermark).map { (it as CoroutineCreated).coroutineId },
                "resuming from a watermark must not skip or repeat events",
            )

            // Live path: the bus delivers the same events in the same order.
            val live: List<VizEvent> = withTimeout(5_000) { delivered.await() }
            assertEquals(emitted, live.map { (it as CoroutineCreated).coroutineId })
            assertEquals(replay.map { it.seq }, live.map { it.seq })
        }
}
