package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Non-mocked acceptance gate for the per-coroutine source-frame projection (RCO-06, D-05a).
 *
 * The 08.2 lesson was that mocked tests gave false confidence; this test drives the REAL
 * [VizSession] + real [ProjectionService] over a session containing a real [CoroutineSuspended]
 * carrying a real `SuspensionPoint(fileName, lineNumber)` and asserts the returned timeline
 * `events` surface a concrete `file:line` frame — not a stub, not just non-empty.
 *
 * Mirrors the live demo frame (a `SpringVizcoreDemoApplication.kt:<line>` suspension).
 */
class CoroutineTimelineSourceFramesTest {
    private var seq = 0L

    private fun created(
        session: VizSession,
        id: String,
        tsNanos: Long,
    ): CoroutineCreated =
        CoroutineCreated(
            sessionId = session.sessionId,
            seq = ++seq,
            tsNanos = tsNanos,
            coroutineId = id,
            jobId = "job-$id",
            parentCoroutineId = null,
            scopeId = "io",
            label = id,
        )

    private fun started(
        session: VizSession,
        id: String,
        tsNanos: Long,
    ): CoroutineStarted =
        CoroutineStarted(
            sessionId = session.sessionId,
            seq = ++seq,
            tsNanos = tsNanos,
            coroutineId = id,
            jobId = "job-$id",
            parentCoroutineId = null,
            scopeId = "io",
            label = id,
        )

    private fun suspended(
        session: VizSession,
        id: String,
        tsNanos: Long,
    ): CoroutineSuspended =
        CoroutineSuspended(
            sessionId = session.sessionId,
            seq = ++seq,
            tsNanos = tsNanos,
            coroutineId = id,
            jobId = "job-$id",
            parentCoroutineId = null,
            scopeId = "io",
            label = id,
            reason = "delay",
            suspensionPoint =
                SuspensionPoint(
                    function = "handleRequest",
                    fileName = "SpringVizcoreDemoApplication.kt",
                    lineNumber = 75,
                    reason = "delay",
                ),
        )

    @Test
    fun `projection returns a real file line suspension frame for a coroutine`() {
        val session = VizSession("timeline-source-frames")
        try {
            val id = "request-2"
            // Drive the real coroutine lifecycle Created -> Started -> Suspended. The projection
            // registers the coroutine on CoroutineCreated (the `coroutines[id] ?: return null`
            // guard), mirroring the live demo path.
            val events =
                listOf(
                    created(session, id, tsNanos = 0),
                    started(session, id, tsNanos = 1),
                    suspended(session, id, tsNanos = 2),
                )

            // Live drive: send through the real bus/store the route reads from, then poll
            // until the projection observes the events (collector runs async on sessionScope).
            runBlocking {
                events.forEach { session.send(it) }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (System.nanoTime() < deadline) {
                    val observed = session.projectionService.getCoroutineTimeline(id)?.events
                    if (observed != null && observed.isNotEmpty()) break
                    Thread.sleep(10)
                }
            }

            val timeline = session.projectionService.getCoroutineTimeline(id)
            assertNotNull(timeline, "timeline must be non-null for a known coroutine")

            // Load-bearing assertion (D-05a): a REAL file:line, not a stub.
            val frame =
                timeline.events
                    .filter { it.kind == "coroutine.suspended" }
                    .mapNotNull { it.suspensionPoint }
                    .single()
            assertEquals("SpringVizcoreDemoApplication.kt", frame.fileName)
            assertNotNull(frame.lineNumber, "suspension frame must carry a line number")
            assertTrue(frame.lineNumber!! > 0, "must carry a real line number, not a stub")

            // A coroutine.started row is present (kind only, no source frame in v1 — D-04).
            assertTrue(
                timeline.events.any { it.kind == "coroutine.started" },
                "a coroutine.started row must be present",
            )
        } finally {
            session.close()
        }
    }
}
