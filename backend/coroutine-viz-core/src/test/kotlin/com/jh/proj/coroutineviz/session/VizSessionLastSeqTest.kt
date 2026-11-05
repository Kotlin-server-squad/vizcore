package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class VizSessionLastSeqTest {
    @Test
    fun `lastSeq is the seq of the last appended event`() {
        val session = VizSession("last-seq")
        assertEquals(0L, session.lastSeq)

        session.ingest(CoroutineStarted("last-seq", 41, 1, "c", "j", null, "s", "c"))
        session.ingest(CoroutineStarted("last-seq", 7, 2, "c", "j", null, "s", "c"))

        // ingest() re-stamps: the client's 41 and 7 are not kept.
        assertEquals(2L, session.lastSeq)
        session.close()
    }
}
