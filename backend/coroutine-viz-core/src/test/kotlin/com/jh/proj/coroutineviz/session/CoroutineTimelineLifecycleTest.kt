package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineBodyCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
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

/**
 * The per-coroutine timeline response lists every lifecycle event: resumes, the end of the body and the terminal
 * event too, not only creation, start and suspensions (spec 2026-11-05, Part 10).
 */
@Timeout(30)
class CoroutineTimelineLifecycleTest {
    private fun created(
        id: String,
        seq: Long,
    ) = CoroutineCreated("s", seq, seq * 1_000, id, "job-$id", null, "io", id, createdAtEpochMs = 1)

    private fun started(
        id: String,
        seq: Long,
    ) = CoroutineStarted("s", seq, seq * 1_000, id, "job-$id", null, "io", id)

    /** The timeline's rows once the projection has seen [count] of them (it builds asynchronously). */
    private fun rows(
        session: VizSession,
        id: String,
        count: Int,
    ): List<TimelineEventSummary> {
        val deadline = System.currentTimeMillis() + 5_000
        var rows = emptyList<TimelineEventSummary>()
        while (System.currentTimeMillis() < deadline && rows.size < count) {
            rows = session.projectionService.getCoroutineTimeline(id)?.events.orEmpty()
            if (rows.size < count) Thread.sleep(10)
        }
        return rows
    }

    private fun withSession(block: (VizSession, (VizEvent) -> Unit) -> Unit) {
        val session = VizSession("s")
        try {
            // The bus does not replay: wait until both projections subscribe (see CoroutineTimelineOpenPhaseTest).
            runBlocking { withTimeout(5.seconds) { session.eventBus.subscriptionCount.first { it >= 2 } } }
            block(session) { session.send(it) }
        } finally {
            session.close()
        }
    }

    @Test
    fun `resumes, the end of the body and completion are listed in seq order`() =
        withSession { session, send ->
            send(created("c", 1))
            send(started("c", 2))
            send(CoroutineSuspended("s", 3, 3_000, "c", "job-c", null, "io", "c", reason = "delay", durationMillis = 5))
            send(CoroutineResumed("s", 4, 4_000, "c", "job-c", null, "io", "c"))
            send(CoroutineBodyCompleted("s", 5, 5_000, "c", "job-c", null, "io", "c"))
            send(CoroutineCompleted("s", 6, 6_000, "c", "job-c", null, "io", "c"))

            val rows = rows(session, "c", 6)

            assertEquals(
                listOf(
                    "coroutine.created",
                    "coroutine.started",
                    "coroutine.suspended",
                    "coroutine.resumed",
                    "coroutine.body-completed",
                    "coroutine.completed",
                ),
                rows.map { it.kind },
            )
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), rows.map { it.seq })
            assertEquals(listOf(4_000L, 5_000L, 6_000L), rows.drop(3).map { it.tsNanos })
        }

    @Test
    fun `waiting for its children is not active time`() =
        withSession { session, send ->
            send(created("p", 1))
            send(started("p", 2))
            send(CoroutineBodyCompleted("s", 3, 5_000, "p", "job-p", null, "io", "p"))
            rows(session, "p", 3)

            // While it waits for its children no phase is open: its Active time stopped at the end of its body.
            val waiting = session.projectionService.getCoroutineTimeline("p")!!
            assertEquals(3_000L, waiting.activeDuration)
            assertNull(waiting.openPhase)
            assertNull(waiting.openPhaseSinceNanos)

            send(CoroutineCompleted("s", 4, 9_000, "p", "job-p", null, "io", "p"))
            rows(session, "p", 4)
            val done = session.projectionService.getCoroutineTimeline("p")!!
            assertEquals(3_000L, done.activeDuration)
            assertNull(done.suspendedDuration)
        }

    @Test
    fun `a cancellation carries its cause and a failure its exception type`() =
        withSession { session, send ->
            send(created("x", 1))
            send(started("x", 2))
            send(CoroutineCancelled("s", 3, 3_000, "x", "job-x", null, "io", "x", cause = "parent failed"))
            send(created("f", 4))
            send(started("f", 5))
            send(
                CoroutineFailed(
                    "s", 6, 6_000, "f", "job-f", null, "io", "f",
                    exceptionType = "java.lang.IllegalStateException", message = "out of stock", stackTrace = emptyList(),
                ),
            )
            send(created("m", 7))
            send(CoroutineFailed("s", 8, 8_000, "m", "job-m", null, "io", "m", exceptionType = null, message = "boom", stackTrace = emptyList()))

            val cancelled = rows(session, "x", 3).last()
            assertEquals("coroutine.cancelled", cancelled.kind)
            assertEquals("parent failed", cancelled.reason)

            val failed = rows(session, "f", 3).last()
            assertEquals("coroutine.failed", failed.kind)
            assertEquals("java.lang.IllegalStateException", failed.reason)

            // Without an exception type, its message is the reason.
            assertEquals("boom", rows(session, "m", 2).last().reason)
        }
}
