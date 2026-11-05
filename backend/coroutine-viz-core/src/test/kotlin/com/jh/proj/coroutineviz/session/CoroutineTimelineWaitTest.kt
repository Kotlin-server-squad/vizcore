package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.WaitKind
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.models.TimelineEventSummary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** The per-coroutine timeline response carries what a recorded wait waited for (spec 2026-11-05, S-7). */
@Timeout(30)
class CoroutineTimelineWaitTest {
    @Test
    fun `a suspension row carries the wait's kind and resource`() {
        val session = VizSession("s")
        try {
            // The bus does not replay: wait until both projections subscribe (see CoroutineTimelineOpenPhaseTest).
            runBlocking { withTimeout(5.seconds) { session.eventBus.subscriptionCount.first { it >= 2 } } }
            session.send(CoroutineCreated("s", 1, 1_000, "c", "job-c", null, "io", "order-7", createdAtEpochMs = 1))
            session.send(CoroutineStarted("s", 2, 2_000, "c", "job-c", null, "io", "order-7"))
            session.send(
                CoroutineSuspended(
                    "s", 3, 3_000, "c", "job-c", null, "io", "order-7",
                    reason = "mutex", waitKind = WaitKind.MUTEX, resourceId = "mutex-4",
                ),
            )
            session.send(CoroutineSuspended("s", 4, 4_000, "c", "job-c", null, "io", "order-7", reason = "delay", durationMillis = 5))

            // The projection builds its node asynchronously: poll the timeline response itself.
            val deadline = System.currentTimeMillis() + 5_000
            var rows = emptyList<TimelineEventSummary>()
            while (System.currentTimeMillis() < deadline && rows.size < 2) {
                rows = session.projectionService.getCoroutineTimeline("c")?.events.orEmpty().filter { it.kind == "coroutine.suspended" }
                if (rows.size < 2) Thread.sleep(10)
            }

            assertEquals(2, rows.size)

            assertEquals(WaitKind.MUTEX, rows[0].waitKind)
            assertEquals("mutex-4", rows[0].resourceId)
            assertNull(rows[1].waitKind)
            assertNull(rows[1].resourceId)
        } finally {
            session.close()
        }
    }
}
