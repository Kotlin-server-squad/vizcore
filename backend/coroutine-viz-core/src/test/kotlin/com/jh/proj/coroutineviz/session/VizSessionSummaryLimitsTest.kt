package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.health.EventScript
import com.jh.proj.coroutineviz.health.SummaryLimits
import com.jh.proj.coroutineviz.health.SummaryTable
import com.jh.proj.coroutineviz.health.sec
import com.jh.proj.coroutineviz.health.toState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** A session's summaries honour the limits it was built with, live and rehydrated (spec C-2, C-4). */
class VizSessionSummaryLimitsTest {
    private val small = SummaryLimits(fullSummaryTarget = 3, trackedCapacity = 3)
    private val sessions = mutableListOf<VizSession>()

    @AfterEach
    fun tearDown() {
        sessions.forEach { it.close() }
        SessionManager.configure(maxEventsPerSession = 100_000)
        SessionManager.clearAll()
    }

    private fun fourCoroutines(): EventScript =
        EventScript("limits").apply {
            for (i in 1..4) {
                created("c$i", at = sec(i.toLong()))
                started("c$i", at = sec(i.toLong()))
            }
        }

    @Test
    fun `a live session counts the coroutine beyond its capacity as untracked`() {
        val session = VizSession("limits", keepSummaries = true, summaryLimits = small).also { sessions += it }
        fourCoroutines().events.forEach(session::send)
        assertEquals(1L, session.readSummaries { it.untrackedCount })
        assertEquals(3, session.readSummaries { it.all.size })
        assertEquals(small, session.summaryLimits)
    }

    @Test
    fun `a session rehydrated from a checkpoint goes on with its own limits`() {
        val events = fourCoroutines().events
        val seed = SummaryTable(small).also { t -> events.take(4).forEach(t::apply) }.toState()
        val session = VizSession("limits", keepSummaries = true, summaryLimits = small).also { sessions += it }
        session.rehydrateFrom(events, seed)
        assertEquals(1L, session.readSummaries { it.untrackedCount })
    }

    @Test
    fun `sessions the manager creates use the configured limits, and configuring again without them restores the defaults`() {
        SessionManager.configure(maxEventsPerSession = 100_000, summaryLimits = small)
        val session = runBlocking { SessionManager.createSession("limits") }
        fourCoroutines().events.forEach(session::send)
        assertEquals(1L, session.readSummaries { it.untrackedCount })

        SessionManager.configure(maxEventsPerSession = 100_000)
        assertEquals(SummaryLimits.DEFAULT, runBlocking { SessionManager.createSession("defaults") }.summaryLimits)
    }

    @Test
    fun `a session built without limits keeps the defaults`() {
        assertEquals(SummaryLimits.DEFAULT, VizSession("defaults", keepSummaries = true).also { sessions += it }.summaryLimits)
    }
}
