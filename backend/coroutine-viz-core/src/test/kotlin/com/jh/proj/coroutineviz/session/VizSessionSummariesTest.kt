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
        val session = VizSession("summaries-send")
        script(session.sessionId).events.forEach(session::send)

        assertEquals(true, session.readSummaries { it.get("a")?.failed })
        assertEquals(3, session.readSummaries { it.lastEventSeq })
        session.close()
    }

    @Test
    fun `rehydrating from a store rebuilds the summaries`() {
        val store = EventStore()
        script("summaries-rehydrate").events.forEach(store::record)
        val session = VizSession("summaries-rehydrate", eventStoreFactory = { store })

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
}
