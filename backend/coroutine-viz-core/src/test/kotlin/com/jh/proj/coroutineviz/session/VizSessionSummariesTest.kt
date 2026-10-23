package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.health.EventScript
import com.jh.proj.coroutineviz.health.sec
import org.junit.jupiter.api.Test
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
}
