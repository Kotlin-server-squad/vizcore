package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * #150 / #138: the event bus has replay = 0, so a read model only sees events sent AFTER
 * its collector subscribed. The projections subscribe from their constructors; if that
 * subscription is still pending when the session is handed out, the first events are lost
 * for good — the root cause of the flaky `rebuildFrom is replay-consistent with live
 * streaming` test. A session must be fully subscribed by the time its constructor returns.
 */
class BusSubscriptionRaceTest {
    private fun created(session: VizSession): CoroutineCreated =
        CoroutineCreated(
            sessionId = session.sessionId,
            seq = 1,
            tsNanos = 1,
            coroutineId = "c-1",
            jobId = "job-c-1",
            parentCoroutineId = null,
            scopeId = "Dispatchers.Default",
            label = "first",
            createdAtEpochMs = 0,
        )

    private fun started(session: VizSession): CoroutineStarted =
        CoroutineStarted(
            sessionId = session.sessionId,
            seq = 2,
            tsNanos = 2,
            coroutineId = "c-1",
            jobId = "job-c-1",
            parentCoroutineId = null,
            scopeId = "Dispatchers.Default",
            label = "first",
        )

    /** Polls [condition] for up to [timeoutMs]; true once it holds. Only bounds async delivery. */
    private fun eventually(
        timeoutMs: Long = 2_000,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(2)
        }
        return condition()
    }

    @Test
    fun `events sent immediately after construction reach every read model`() {
        val iterations = 200
        var metricsMisses = 0
        var hierarchyMisses = 0
        repeat(iterations) { i ->
            val session = VizSession("bus-race-$i")
            try {
                // No wait between construction and the first send: this is what a scenario
                // run, an ingest socket or a rehydrate does in production.
                session.send(created(session))
                session.send(started(session))

                if (!eventually { session.metricsProjection.snapshot(0, 30_000).active == 1 }) metricsMisses++
                if (!eventually { session.projectionService.getHierarchyTree().any { it.id == "c-1" } }) hierarchyMisses++
            } finally {
                session.close()
            }
        }
        assertEquals(0, metricsMisses, "MetricsProjection lost the first events in $metricsMisses/$iterations sessions")
        assertEquals(0, hierarchyMisses, "ProjectionService lost the first events in $hierarchyMisses/$iterations sessions")
    }
}
