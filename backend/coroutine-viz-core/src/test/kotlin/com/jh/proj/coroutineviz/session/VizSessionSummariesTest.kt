package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.health.EventScript
import com.jh.proj.coroutineviz.health.SummariesUnavailableException
import com.jh.proj.coroutineviz.health.sec
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VizSessionSummariesTest {
    private fun script(sessionId: String): EventScript =
        EventScript(sessionId).apply {
            created("req", at = sec(1))
            created("a", at = sec(1), parent = "req")
            failed("a", at = sec(2))
        }

    @Test
    fun `sent events update the summaries`() {
        val session = VizSession("summaries-send", keepSummaries = true)
        script(session.sessionId).events.forEach(session::send)

        assertEquals(true, session.readSummaries { it.get("a")?.failed })
        assertEquals(3, session.readSummaries { it.lastEventSeq })
        session.close()
    }

    @Test
    fun `rehydrating from a store rebuilds the summaries`() {
        val store = EventStore()
        script("summaries-rehydrate").events.forEach(store::record)
        val session = VizSession("summaries-rehydrate", keepSummaries = true, eventStoreFactory = { store })

        session.rehydrateFromStore()

        assertEquals(true, session.readSummaries { it.get("a")?.failed })
        assertEquals("req", session.readSummaries { it.get("a")?.parentId })
        session.close()
    }

    @Test
    fun `a session without summaries keeps no table`() {
        val session = VizSession("summaries-off", keepSummaries = false)
        script(session.sessionId).events.forEach(session::send)

        assertFailsWith<IllegalStateException> { session.readSummaries { it.lastEventSeq } }
        session.close()
    }

    @Test
    fun `summaries are opt-in`() {
        val session = VizSession("summaries-default")

        assertFailsWith<IllegalStateException> { session.readSummaries { it.lastEventSeq } }
        session.close()
    }

    @Test
    fun `sessions made by the session manager keep summaries`() {
        val session = runBlocking { SessionManager.createSession("summaries-manager") }
        try {
            script(session.sessionId).events.forEach(session::send)

            assertEquals(true, session.readSummaries { it.get("a")?.failed })
        } finally {
            SessionManager.clearAll()
        }
    }

    /** Two creates share seq 1 (the DB has no unique (session_id, seq)). */
    private fun duplicateSeqStore(sessionId: String): EventStore {
        val script =
            EventScript(sessionId).apply {
                created("a", at = sec(1))
                created("b", at = sec(1))
                failed("a", at = sec(2))
                failed("b", at = sec(2))
            }
        script.events[1].seq = 1L
        return EventStore().also { store -> script.events.forEach(store::record) }
    }

    @Test
    fun `a duplicate seq in the stored log does not disable the summaries`() {
        val store = duplicateSeqStore("summaries-dup")
        val session = VizSession("summaries-dup", keepSummaries = true, eventStoreFactory = { store })

        session.rehydrateFromStore()

        assertEquals(setOf("a", "b"), session.snapshot.coroutines.keys)
        assertEquals(5L, session.nextSeq())
        assertEquals(true, session.readSummaries { it.get("a")?.failed })
        assertEquals(true, session.readSummaries { it.get("b")?.failed })
        session.close()
    }

    @Test
    fun `summaries keep updating after a rehydration`() {
        val store = EventStore()
        script("summaries-resume").events.forEach(store::record)
        val session = VizSession("summaries-resume", keepSummaries = true, eventStoreFactory = { store })
        session.rehydrateFromStore()

        EventScript(session.sessionId)
            .apply {
                created("c", at = sec(3))
                failed("c", at = sec(4))
            }.events
            .forEach(session::send)

        assertEquals(5L, session.readSummaries { it.lastEventSeq })
        assertEquals(true, session.readSummaries { it.get("c")?.failed })
        session.close()
    }

    @Test
    fun `a summary failure never breaks ingestion`() {
        val session = VizSession("summaries-failure", keepSummaries = true)
        var applied = 0
        session.summaryFailureForTest = { if (++applied == 2) error("boom") }
        val script =
            EventScript(session.sessionId).apply {
                created("a", at = sec(1))
                created("b", at = sec(2))
                created("c", at = sec(3))
                created("d", at = sec(4))
            }

        script.events.forEach(session::send)

        assertEquals(4, session.store.all().size)
        assertEquals(setOf("a", "b", "c", "d"), session.snapshot.coroutines.keys)
        assertFailsWith<SummariesUnavailableException> { session.readSummaries { it.lastEventSeq } }
        session.close()
    }

    @Test
    fun `reads during concurrent ingestion see a consistent, advancing table`() {
        val session = VizSession("summaries-concurrent", keepSummaries = true)
        val total = 2_000
        val script = EventScript(session.sessionId).apply { repeat(total) { created("c$it", at = sec(1)) } }
        val observed = mutableListOf<Long>()
        val producer = thread { script.events.forEach(session::send) }

        while (producer.isAlive) observed += session.readSummaries { it.lastEventSeq }
        producer.join()

        assertEquals(observed.sorted(), observed, "lastEventSeq never goes backwards")
        assertEquals(session.store.all().last().seq, session.readSummaries { it.lastEventSeq })
        assertEquals(total.toLong(), session.readSummaries { it.lastEventSeq })
        session.close()
    }
}
