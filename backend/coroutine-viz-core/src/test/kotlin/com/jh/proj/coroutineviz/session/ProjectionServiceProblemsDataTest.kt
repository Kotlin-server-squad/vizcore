package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
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

    private fun node(
        session: VizSession,
        id: String,
    ) = session.projectionService.getHierarchyTree().single { it.id == id }

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
}
