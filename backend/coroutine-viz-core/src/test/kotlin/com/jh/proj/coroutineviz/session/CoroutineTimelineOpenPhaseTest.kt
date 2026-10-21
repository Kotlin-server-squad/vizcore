package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.models.CoroutineTimeline
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A coroutine's timeline says which phase it has been in since its last recorded transition, and
 * carries the clocks a client needs to count that phase up to now (#218). A coroutine blocked
 * after its last resume (a blocking call on a shared thread) records no further event, so
 * without this the inspector could only show the closed intervals.
 */
class CoroutineTimelineOpenPhaseTest {
    private var seq = 0L

    private fun send(
        session: VizSession,
        events: List<VizEvent>,
    ) {
        // The bus does not replay: wait until both projections (ProjectionService and
        // MetricsProjection) subscribe, or CoroutineCreated can be lost to the projection.
        runBlocking { session.eventBus.subscriptionCount.first { it >= 2 } }
        events.forEach { session.send(it) }
    }

    private fun created(
        id: String,
        ts: Long,
        epochMs: Long,
    ) = CoroutineCreated("s", ++seq, ts, id, "job-$id", null, "io", id, createdAtEpochMs = epochMs)

    private fun started(
        id: String,
        ts: Long,
    ) = CoroutineStarted("s", ++seq, ts, id, "job-$id", null, "io", id)

    private fun suspended(
        id: String,
        ts: Long,
    ) = CoroutineSuspended("s", ++seq, ts, id, "job-$id", null, "io", id, reason = "save")

    private fun resumed(
        id: String,
        ts: Long,
    ) = CoroutineResumed("s", ++seq, ts, id, "job-$id", null, "io", id)

    private fun completed(
        id: String,
        ts: Long,
    ) = CoroutineCompleted("s", ++seq, ts, id, "job-$id", null, "io", id)

    private fun timelineOf(
        session: VizSession,
        id: String,
        expectEvents: Int,
    ): CoroutineTimeline {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val timeline = session.projectionService.getCoroutineTimeline(id)
            if (timeline != null && session.getCoroutineTimeline(id, newestFirst = false).size >= expectEvents) return timeline
            Thread.sleep(10)
        }
        return assertNotNull(session.projectionService.getCoroutineTimeline(id))
    }

    @Test
    fun `a coroutine running since its last resume reports an open ACTIVE phase and both creation clocks`() {
        val session = VizSession("s")
        try {
            send(session, listOf(created("c", 1_000, 1_700_000_000_000), started("c", 2_000), suspended("c", 5_000), resumed("c", 9_000)))
            val before = System.currentTimeMillis()
            val timeline = timelineOf(session, "c", expectEvents = 4)

            assertEquals("ACTIVE", timeline.openPhase)
            assertEquals(9_000, timeline.openPhaseSinceNanos)
            assertEquals(1_000, timeline.createdAtNanos)
            assertEquals(1_700_000_000_000, timeline.createdAtEpochMs)
            assert(timeline.observedAtEpochMs!! >= before) { "observedAtEpochMs is the backend's wall clock" }
            // Closed intervals only, as before: 3_000 active (2_000..5_000), 4_000 suspended (5_000..9_000).
            assertEquals(3_000, timeline.activeDuration)
            assertEquals(4_000, timeline.suspendedDuration)
        } finally {
            session.close()
        }
    }

    @Test
    fun `a suspended coroutine reports an open SUSPENDED phase`() {
        val session = VizSession("s")
        try {
            send(session, listOf(created("c", 1_000, 1), started("c", 2_000), suspended("c", 5_000)))
            val timeline = timelineOf(session, "c", expectEvents = 3)
            assertEquals("SUSPENDED", timeline.openPhase)
            assertEquals(5_000, timeline.openPhaseSinceNanos)
        } finally {
            session.close()
        }
    }

    @Test
    fun `a finished coroutine has no open phase`() {
        val session = VizSession("s")
        try {
            send(session, listOf(created("c", 1_000, 1), started("c", 2_000), completed("c", 3_000)))
            val timeline = timelineOf(session, "c", expectEvents = 3)
            assertNull(timeline.openPhase)
            assertNull(timeline.openPhaseSinceNanos)
        } finally {
            session.close()
        }
    }

    @Test
    fun `an unset creation wall time is reported as unknown, not as 1970`() {
        val session = VizSession("s")
        try {
            send(session, listOf(created("c", 1_000, epochMs = 0), started("c", 2_000)))
            assertNull(timelineOf(session, "c", expectEvents = 2).createdAtEpochMs)
        } finally {
            session.close()
        }
    }
}
