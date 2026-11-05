package com.jh.proj.coroutineviz.health

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SummaryViewFactsTest {
    @Test
    fun `the table remembers the first seq it saw and the earliest app time`() {
        val script = EventScript()
        script.created("a", at = sec(5))
        // Appends are not in app-time order: a later event can carry an earlier time.
        script.thread("a", at = sec(3), name = "main")
        val table = script.table()

        assertEquals(1L, table.firstEventSeq)
        assertEquals(sec(3), table.earliestEventAppNanos)
        assertEquals(sec(5), table.latestEventAppNanos)
    }

    @Test
    fun `an empty table has no first seq and no earliest time`() {
        val table = SummaryTable()

        assertEquals(0L, table.firstEventSeq)
        assertNull(table.earliestEventAppNanos)
    }

    @Test
    fun `a snapshot keeps what the table held and does not follow it`() {
        val script = EventScript()
        script.created("a", at = sec(1))
        val table = script.table()
        val copy = table.snapshot()

        script.created("b", at = sec(2))
        table.apply(script.events.last())

        assertEquals(listOf("a"), copy.all.map { it.id })
        assertNull(copy.get("b"))
        assertEquals(1L, copy.lastEventSeq)
        assertEquals(1L, copy.firstEventSeq)
        assertEquals(sec(1), copy.latestEventAppNanos)
        assertEquals(sec(1), copy.earliestEventAppNanos)
        assertEquals(0L, copy.untrackedCount)
        assertEquals(2L, table.lastEventSeq)
    }
}
