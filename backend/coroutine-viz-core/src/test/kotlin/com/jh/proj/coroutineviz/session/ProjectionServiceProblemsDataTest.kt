package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.models.CoroutineTimeline
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Non-mocked acceptance gate for the plugin problems/inspector data the projection must
 * surface on /hierarchy + /timeline (Phase 15 gap closure, plan 15-09).
 *
 * These tests drive the REAL [ProjectionService] via the synchronous, deterministic
 * `rebuildFrom` replay path (also the DB-rehydrate path) and assert the wire fields the
 * plugin's EXCEPTION / TIMING / JUMP pipelines key on.
 */
class ProjectionServiceProblemsDataTest {
    private var seq = 0L

    private fun created(
        session: VizSession,
        id: String,
        tsNanos: Long,
        creationPoint: SuspensionPoint? = null,
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
            creationPoint = creationPoint,
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

    private fun failed(
        session: VizSession,
        id: String,
        tsNanos: Long,
        exceptionType: String?,
        message: String?,
    ): CoroutineFailed =
        CoroutineFailed(
            sessionId = session.sessionId,
            seq = ++seq,
            tsNanos = tsNanos,
            coroutineId = id,
            jobId = "job-$id",
            parentCoroutineId = null,
            scopeId = "io",
            label = id,
            exceptionType = exceptionType,
            message = message,
            stackTrace = emptyList(),
        )

    private fun cancelled(
        session: VizSession,
        id: String,
        tsNanos: Long,
        cause: String?,
    ): CoroutineCancelled =
        CoroutineCancelled(
            sessionId = session.sessionId,
            seq = ++seq,
            tsNanos = tsNanos,
            coroutineId = id,
            jobId = "job-$id",
            parentCoroutineId = null,
            scopeId = "io",
            label = id,
            cause = cause,
        )

    private fun suspended(
        session: VizSession,
        id: String,
        tsNanos: Long,
        suspensionPoint: SuspensionPoint? = null,
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
            suspensionPoint = suspensionPoint,
        )

    private fun resumed(
        session: VizSession,
        id: String,
        tsNanos: Long,
    ): CoroutineResumed =
        CoroutineResumed(
            sessionId = session.sessionId,
            seq = ++seq,
            tsNanos = tsNanos,
            coroutineId = id,
            jobId = "job-$id",
            parentCoroutineId = null,
            scopeId = "io",
            label = id,
        )

    private fun completed(
        session: VizSession,
        id: String,
        tsNanos: Long,
    ): CoroutineCompleted =
        CoroutineCompleted(
            sessionId = session.sessionId,
            seq = ++seq,
            tsNanos = tsNanos,
            coroutineId = id,
            jobId = "job-$id",
            parentCoroutineId = null,
            scopeId = "io",
            label = id,
        )

    private fun node(
        session: VizSession,
        id: String,
    ) = session.projectionService.getHierarchyTree().single { it.id == id }

    /**
     * Live-drive: send [events] through the real bus/store the route reads from, then poll
     * until the projection has observed the last event (the collector runs async on
     * sessionScope). Returns the computed timeline. Mirrors [CoroutineTimelineSourceFramesTest].
     */
    private fun driveTimeline(
        session: VizSession,
        id: String,
        events: List<VizEvent>,
        expectedEventCount: Int,
    ): CoroutineTimeline {
        runBlocking {
            events.forEach { session.send(it) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                val t = session.projectionService.getCoroutineTimeline(id)
                if (t != null && t.events.size >= expectedEventCount) break
                Thread.sleep(10)
            }
        }
        return assertNotNull(
            session.projectionService.getCoroutineTimeline(id),
            "timeline must be non-null for a known coroutine",
        )
    }

    @Test
    fun `CoroutineFailed copies exceptionType and message onto the hierarchy node`() {
        val session = VizSession("proj-exception-copy")
        try {
            seq = 0
            val id = "req-boom"
            session.projectionService.rebuildFrom(
                listOf<VizEvent>(
                    created(session, id, 0),
                    started(session, id, 1),
                    failed(
                        session,
                        id,
                        2,
                        exceptionType = "java.lang.IllegalStateException",
                        message = "boom",
                    ),
                ),
            )

            val n = node(session, id)
            assertEquals("FAILED", n.state, "terminal state must be FAILED")
            assertEquals("java.lang.IllegalStateException", n.exceptionType)
            assertEquals("boom", n.exceptionMessage)
        } finally {
            session.close()
        }
    }

    @Test
    fun `CoroutineCancelled does NOT set exceptionType - cancellation is normal flow`() {
        val session = VizSession("proj-cancel-no-exception")
        try {
            seq = 0
            val id = "req-cancel"
            session.projectionService.rebuildFrom(
                listOf<VizEvent>(
                    created(session, id, 0),
                    started(session, id, 1),
                    cancelled(session, id, 2, cause = "JobCancellationException"),
                ),
            )

            val n = node(session, id)
            assertEquals("CANCELLED", n.state)
            assertNull(n.exceptionType, "cancellation must NOT populate exceptionType (plugin isRealException, D-09)")
            assertNull(n.exceptionMessage, "cancellation must NOT populate exceptionMessage")
        } finally {
            session.close()
        }
    }

    @Test
    fun `rebuildFrom replay preserves exception fields on the DB-rehydrate path`() {
        val session = VizSession("proj-exception-replay")
        try {
            seq = 0
            val id = "req-replay"
            val events =
                listOf<VizEvent>(
                    created(session, id, 0),
                    started(session, id, 1),
                    failed(session, id, 2, exceptionType = "java.io.IOException", message = "disk"),
                )
            // Replay twice: rebuildFrom clears + replays, so the second pass must reproduce
            // the identical enriched node (idempotent DB-rehydrate).
            session.projectionService.rebuildFrom(events)
            session.projectionService.rebuildFrom(events)

            val n = node(session, id)
            assertEquals("java.io.IOException", n.exceptionType)
            assertEquals("disk", n.exceptionMessage)
        } finally {
            session.close()
        }
    }

    @Test
    fun `durations are folded from same-clock started-suspended-resumed-completed pairs`() {
        val session = VizSession("proj-durations-terminal")
        try {
            seq = 0
            val id = "req-timed"
            val ms = 1_000_000L
            // started(0) -> suspended(100ms) -> resumed(300ms) -> completed(350ms)
            // active = (100-0) + (350-300) = 150ms ; suspended = (300-100) = 200ms
            val timeline =
                driveTimeline(
                    session,
                    id,
                    listOf(
                        created(session, id, 0),
                        started(session, id, 0),
                        suspended(session, id, 100 * ms),
                        resumed(session, id, 300 * ms),
                        completed(session, id, 350 * ms),
                    ),
                    // created + started + suspended (started/suspended are summarised; created too now)
                    expectedEventCount = 3,
                )

            assertEquals(150 * ms, timeline.activeDuration, "active = 150ms of running")
            assertEquals(200 * ms, timeline.suspendedDuration, "suspended = 200ms")
        } finally {
            session.close()
        }
    }

    @Test
    fun `a live coroutine accumulates only closed intervals - the open tail is excluded`() {
        val session = VizSession("proj-durations-live")
        try {
            seq = 0
            val id = "req-live"
            val ms = 1_000_000L
            // started(0) -> suspended(100ms) -> resumed(300ms), NO terminal event.
            // active = (100-0) = 100ms (the open ACTIVE tail after resume is NOT extrapolated).
            // suspended = (300-100) = 200ms.
            val timeline =
                driveTimeline(
                    session,
                    id,
                    listOf(
                        created(session, id, 0),
                        started(session, id, 0),
                        suspended(session, id, 100 * ms),
                        resumed(session, id, 300 * ms),
                    ),
                    expectedEventCount = 3,
                )

            assertEquals(100 * ms, timeline.activeDuration, "only the CLOSED active interval counts")
            assertEquals(200 * ms, timeline.suspendedDuration, "the one closed suspended interval")
        } finally {
            session.close()
        }
    }

    @Test
    fun `an evicted event list degrades both durations to null`() {
        val session = VizSession("proj-durations-evicted")
        try {
            seq = 0
            val id = "req-evicted"
            // rebuildFrom populates the projection node but NOT the store; getCoroutineTimeline
            // reads its raw events from the store, so this mirrors the 10k DROP_OLDEST eviction
            // where the node survives but the per-coroutine event list has emptied out.
            session.projectionService.rebuildFrom(
                listOf<VizEvent>(
                    created(session, id, 0),
                    started(session, id, 1),
                ),
            )

            val timeline =
                assertNotNull(session.projectionService.getCoroutineTimeline(id))
            assertNull(timeline.activeDuration, "no closed interval on an evicted list -> null (not 0)")
            assertNull(timeline.suspendedDuration, "no closed interval on an evicted list -> null (not 0)")
        } finally {
            session.close()
        }
    }

    @Test
    fun `timeline first summary is coroutine-created carrying the launch frame`() {
        val session = VizSession("proj-created-summary")
        try {
            seq = 0
            val id = "req-created"
            val launch =
                SuspensionPoint(
                    function = "handleRequest",
                    fileName = "DemoApplication.kt",
                    lineNumber = 42,
                    reason = "launch",
                )
            val timeline =
                driveTimeline(
                    session,
                    id,
                    listOf(
                        created(session, id, 0, creationPoint = launch),
                        started(session, id, 1),
                    ),
                    expectedEventCount = 2,
                )

            val first = timeline.events.first()
            assertEquals("coroutine.created", first.kind, "the earliest summary is coroutine.created")
            assertEquals(launch, first.suspensionPoint, "coroutine.created carries the launch frame")
        } finally {
            session.close()
        }
    }

    @Test
    fun `node carries durable creationPoint and lastSuspensionPoint source refs`() {
        val session = VizSession("proj-durable-refs")
        try {
            seq = 0
            val id = "req-refs"
            val launch = SuspensionPoint(function = "main", fileName = "App.kt", lineNumber = 10, reason = "launch")
            val suspend = SuspensionPoint(function = "fetch", fileName = "Repo.kt", lineNumber = 55, reason = "delay")
            session.projectionService.rebuildFrom(
                listOf<VizEvent>(
                    created(session, id, 0, creationPoint = launch),
                    started(session, id, 1),
                    suspended(session, id, 2, suspensionPoint = suspend),
                ),
            )

            val n = node(session, id)
            assertEquals(launch, n.creationPoint, "creationPoint (launch site) persists on the node")
            assertEquals(suspend, n.lastSuspensionPoint, "last suspension frame persists on the node")
        } finally {
            session.close()
        }
    }

    @Test
    fun `a later null suspensionPoint does not clear the last non-null lastSuspensionPoint`() {
        val session = VizSession("proj-refs-keep-last")
        try {
            seq = 0
            val id = "req-keep"
            val suspend = SuspensionPoint(function = "fetch", fileName = "Repo.kt", lineNumber = 55, reason = "delay")
            session.projectionService.rebuildFrom(
                listOf<VizEvent>(
                    created(session, id, 0),
                    started(session, id, 1),
                    suspended(session, id, 2, suspensionPoint = suspend),
                    resumed(session, id, 3),
                    // a second suspension with NO frame must NOT wipe the last good ref
                    suspended(session, id, 4, suspensionPoint = null),
                ),
            )

            val n = node(session, id)
            assertEquals(suspend, n.lastSuspensionPoint, "keep the last non-null suspension frame")
        } finally {
            session.close()
        }
    }

    @Test
    fun `durable source refs survive rebuildFrom replay`() {
        val session = VizSession("proj-refs-replay")
        try {
            seq = 0
            val id = "req-refs-replay"
            val launch = SuspensionPoint(function = "main", fileName = "App.kt", lineNumber = 10, reason = "launch")
            val suspend = SuspensionPoint(function = "fetch", fileName = "Repo.kt", lineNumber = 55, reason = "delay")
            val events =
                listOf<VizEvent>(
                    created(session, id, 0, creationPoint = launch),
                    started(session, id, 1),
                    suspended(session, id, 2, suspensionPoint = suspend),
                )
            session.projectionService.rebuildFrom(events)
            session.projectionService.rebuildFrom(events)

            val n = node(session, id)
            assertEquals(launch, n.creationPoint)
            assertEquals(suspend, n.lastSuspensionPoint)
        } finally {
            session.close()
        }
    }
}
