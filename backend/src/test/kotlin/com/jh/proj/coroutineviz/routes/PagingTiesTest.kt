package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.health.BoundSummaries
import com.jh.proj.coroutineviz.health.HealthInput
import com.jh.proj.coroutineviz.health.RetentionInfo
import com.jh.proj.coroutineviz.health.SessionKind
import com.jh.proj.coroutineviz.health.SummaryTable
import com.jh.proj.coroutineviz.health.WatermarkObservation
import com.jh.proj.coroutineviz.routes.HealthEvents.cancelled
import com.jh.proj.coroutineviz.routes.HealthEvents.created
import com.jh.proj.coroutineviz.routes.HealthEvents.failed
import com.jh.proj.coroutineviz.routes.HealthEvents.sec
import com.jh.proj.coroutineviz.routes.HealthEvents.started
import com.jh.proj.coroutineviz.routes.HealthEvents.suspended
import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.http.parametersOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Legacy database logs can repeat a seq, so list entries can tie on every key but their id (plan E-9). Paging every list
 * one entry at a time with the routes' own keys visits each entry exactly once, in the list's order.
 */
class PagingTiesTest {
    private val probe = VizSession("ties")

    @AfterEach
    fun tearDown() = probe.close()

    /** Every event gets one of two seqs: 5 for the first half, 9 for the rest. */
    private fun view(): SessionView {
        val id = "ties"
        val events = mutableListOf<VizEvent>()
        for (r in listOf("z", "a", "m")) {
            events += created(id, r, sec(1))
            events += started(id, r, sec(1))
            events += failed(id, r, sec(2))
        }
        events += created(id, "big", sec(1))
        for (c in listOf("y", "b", "x")) {
            events += created(id, c, sec(1), parent = "big")
            events += failed(id, c, sec(2), parent = "big")
        }
        for (c in listOf("k", "c")) {
            events += created(id, c, sec(1), parent = "big")
            events += cancelled(id, c, sec(3), parent = "big")
        }
        events += failed(id, "big", sec(4))
        for (r in listOf("q", "e", "w")) {
            events += created(id, r, sec(1))
            events += started(id, r, sec(1))
            events += suspended(id, r, sec(2))
        }
        val table = SummaryTable()
        events.forEachIndexed { i, e ->
            e.seq = if (i < events.size / 2) 5 else 9
            table.apply(e)
        }
        val input =
            HealthInput(
                summaries = table,
                kind = SessionKind.ATTACHED,
                watermark = WatermarkObservation(sec(100), 1, 1),
                leakThresholdMs = 1_000,
                longSuspendedThresholdNanos = 1,
                retention = RetentionInfo(5, sec(1), trimmed = false),
            )
        return SessionView(probe, events, ViewBinding.Current, 9, BoundSummaries.exact(table), input)
    }

    private fun <T> pageAll(
        all: List<T>,
        key: (T) -> Keyset.Key,
    ): List<T> {
        val seen = mutableListOf<T>()
        var cursor: String? = null
        do {
            val page = Keyset.page(all, key, cursor, limit = 1)
            seen += page.items
            cursor = page.nextCursor
        } while (cursor != null)
        return seen
    }

    @Test
    fun `every list pages one entry at a time without skipping or repeating`() {
        val view = view()
        val derived = view.derive()
        assertEquals(derived.incidents, pageAll(derived.incidents, ::incidentKey))
        assertEquals(derived.incidents.sortedBy(::incidentKey), derived.incidents, "the list is in key order")
        val members = derived.roundMembers("failed:round:big")!!
        assertEquals(6, members.size)
        assertEquals(members, pageAll(members, ::memberKey))
        assertEquals(members.sortedBy(::memberKey), members)
        for (longLived in listOf(true, false)) {
            val ages = if (longLived) derived.longLived else derived.longSuspended
            assertEquals(3, ages.size, "long-lived $longLived")
            assertEquals(ages, pageAll(ages, ageKey(view.input.summaries, longLived)))
            assertEquals(ages.sortedBy(ageKey(view.input.summaries, longLived)), ages)
        }
    }

    @Test
    fun `rounds page one at a time without skipping or repeating`() {
        val view = view()
        val all = listRounds(view, parametersOf("limit" to listOf("200"))).items
        val seen = mutableListOf<RoundEntry>()
        var cursor: String? = null
        do {
            val params = parametersOf(*listOfNotNull("limit" to listOf("1"), cursor?.let { "cursor" to listOf(it) }).toTypedArray())
            val page = listRounds(view, params)
            seen += page.items
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(all, seen)
        assertEquals(all.sortedBy(::roundKey), all)
    }
}
