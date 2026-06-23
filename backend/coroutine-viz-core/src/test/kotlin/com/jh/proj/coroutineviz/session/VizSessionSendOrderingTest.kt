package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for WR-02/WR-12: seq finalization and store append must be
 * atomic in [VizSession.send].
 *
 * Without the send lock, thread A can construct seq=10, thread B construct
 * seq=11 and append it first — the store (and the live bus) then deliver
 * events out of seq order, which breaks the max-seq watermark deduplication
 * used by the SSE replay path (events arriving live with a seq below the
 * snapshot maximum are silently and permanently dropped for that client).
 */
class VizSessionSendOrderingTest {
    private fun event(
        session: VizSession,
        id: String,
    ): CoroutineCreated =
        CoroutineCreated(
            sessionId = session.sessionId,
            // Provisional seq, allocated at construction time exactly like the
            // EventContext factories do. send() re-stamps it when a concurrent
            // sender appended a higher seq first.
            seq = session.nextSeq(),
            tsNanos = System.nanoTime(),
            coroutineId = id,
            jobId = "job-$id",
            parentCoroutineId = null,
            scopeId = "scope-ordering-test",
            label = null,
        )

    @Test
    fun `concurrent sends produce store order equal to seq order`(): Unit =
        runBlocking {
            val session = VizSession("send-ordering-concurrent")
            try {
                val workers = 8
                val eventsPerWorker = 500
                val jobs =
                    (1..workers).map { worker ->
                        launch(Dispatchers.Default) {
                            repeat(eventsPerWorker) { i ->
                                // Construction (seq allocation) and send race across
                                // workers — exactly the interleaving from WR-02.
                                session.send(event(session, "w$worker-e$i"))
                            }
                        }
                    }
                jobs.joinAll()

                val seqs = session.store.all().map { it.seq }
                assertEquals(workers * eventsPerWorker, seqs.size, "all events must be stored")
                assertEquals(seqs.size, seqs.distinct().size, "seqs must be unique")
                assertEquals(
                    seqs.sorted(),
                    seqs,
                    "store order must equal seq order (monotonic seq in append order)",
                )
            } finally {
                session.close()
            }
        }

    @Test
    fun `sequential sends keep their construction-time seq`(): Unit =
        runBlocking {
            val session = VizSession("send-ordering-sequential")
            try {
                val first = event(session, "a")
                val firstConstructionSeq = first.seq
                session.send(first)
                assertEquals(
                    firstConstructionSeq,
                    first.seq,
                    "uncontended sends must not re-stamp the construction-time seq",
                )

                val second = event(session, "b")
                session.send(second)
                assertTrue(
                    second.seq > first.seq,
                    "seq must be strictly increasing across sends",
                )
            } finally {
                session.close()
            }
        }

    @Test
    fun `out-of-order construction is re-stamped to keep seq monotonic`(): Unit =
        runBlocking {
            val session = VizSession("send-ordering-restamp")
            try {
                // Construct two events: 'older' allocates the lower seq.
                val older = event(session, "older")
                val newer = event(session, "newer")

                // Send them in reverse allocation order (the WR-02 interleaving).
                session.send(newer)
                session.send(older)

                val seqs = session.store.all().map { it.seq }
                assertEquals(seqs.sorted(), seqs, "store order must equal seq order")
                assertTrue(
                    older.seq > newer.seq,
                    "the late event must be re-stamped above the already-appended seq",
                )
            } finally {
                session.close()
            }
        }
}
