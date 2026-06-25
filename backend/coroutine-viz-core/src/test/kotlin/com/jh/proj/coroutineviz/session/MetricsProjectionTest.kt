package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wave-0 (RED) spec for [MetricsProjection] (RCO-07, D-04/D-05/D-06/D-07).
 *
 * The projection mirrors [ProjectionService]: it subscribes to the session bus in
 * `init` and exposes `rebuildFrom(events)` so it is replay/DB-rehydrate consistent.
 * These tests drive the metric set directly through `rebuildFrom` (deterministic,
 * synchronous) and verify live streaming converges to the same snapshot.
 */
class MetricsProjectionTest {
    private var seq = 0L

    private fun created(
        session: VizSession,
        id: String,
        scope: String,
        tsNanos: Long,
        label: String? = id,
        createdAtEpochMs: Long = 0L,
    ): CoroutineCreated =
        CoroutineCreated(
            sessionId = session.sessionId,
            seq = ++seq,
            tsNanos = tsNanos,
            coroutineId = id,
            jobId = "job-$id",
            parentCoroutineId = null,
            scopeId = scope,
            label = label,
            createdAtEpochMs = createdAtEpochMs,
        )

    private fun started(
        session: VizSession,
        id: String,
        scope: String,
        tsNanos: Long,
    ): CoroutineStarted =
        CoroutineStarted(
            sessionId = session.sessionId,
            seq = ++seq,
            tsNanos = tsNanos,
            coroutineId = id,
            jobId = "job-$id",
            parentCoroutineId = null,
            scopeId = scope,
            label = id,
        )

    private fun completed(
        session: VizSession,
        id: String,
        scope: String,
        tsNanos: Long,
    ): CoroutineCompleted =
        CoroutineCompleted(
            sessionId = session.sessionId,
            seq = ++seq,
            tsNanos = tsNanos,
            coroutineId = id,
            jobId = "job-$id",
            parentCoroutineId = null,
            scopeId = scope,
            label = id,
        )

    @Test
    fun `active and peak track non-terminal coroutines with a monotonic high-water mark`() {
        val session = VizSession("metrics-active-peak")
        try {
            val metrics = session.metricsProjection
            val events =
                listOf(
                    created(session, "a", "io", 0),
                    started(session, "a", "io", 1),
                    created(session, "b", "io", 2),
                    started(session, "b", "io", 3),
                    // peak is 2 here
                    completed(session, "a", "io", 4),
                    completed(session, "b", "io", 5),
                )
            metrics.rebuildFrom(events)

            val snap = metrics.snapshot(nowEpochMs = 10, leakThresholdMs = 30_000)
            assertEquals(0, snap.active, "both completed -> active is 0")
            assertEquals(2, snap.peak, "peak high-water mark is 2")
        } finally {
            session.close()
        }
    }

    @Test
    fun `throughput is observed events per second over a window and zero when empty`() {
        val session = VizSession("metrics-throughput")
        try {
            val metrics = session.metricsProjection
            assertEquals(0.0, metrics.snapshot(0, 30_000).throughputPerSec, "no events -> 0 throughput")

            // 4 events spread across ~1s of arrivals.
            val oneMs = 1_000_000L
            val events =
                listOf(
                    created(session, "a", "io", 0),
                    started(session, "a", "io", 100 * oneMs),
                    created(session, "b", "io", 500 * oneMs),
                    started(session, "b", "io", 900 * oneMs),
                )
            metrics.rebuildFrom(events)
            // Read the throughput clock just after the last arrival so the window retains
            // all 4 events (WR-04: throughput is evicted against the nowNanos read clock).
            val snap =
                metrics.snapshot(
                    nowEpochMs = 1_000,
                    leakThresholdMs = 30_000,
                    nowNanos = 1_000 * oneMs,
                )
            assertTrue(snap.throughputPerSec > 0.0, "throughput must be positive after a burst; was ${snap.throughputPerSec}")
        } finally {
            session.close()
        }
    }

    @Test
    fun `dispatcher utilization groups active coroutines by scope-derived dispatcher name`() {
        val session = VizSession("metrics-dispatcher")
        try {
            val metrics = session.metricsProjection
            val events =
                listOf(
                    created(session, "a", "Dispatchers.IO", 0),
                    started(session, "a", "Dispatchers.IO", 1),
                    created(session, "b", "Dispatchers.Default", 2),
                    started(session, "b", "Dispatchers.Default", 3),
                )
            metrics.rebuildFrom(events)
            val util = metrics.snapshot(10, 30_000).dispatcherUtilization
            assertEquals(1, util["Dispatchers.IO"], "one active on IO")
            assertEquals(1, util["Dispatchers.Default"], "one active on Default")
        } finally {
            session.close()
        }
    }

    @Test
    fun `dispatcher utilization drops a coroutine on terminal so memory stays bounded`() {
        val session = VizSession("metrics-dispatcher-drop")
        try {
            val metrics = session.metricsProjection
            metrics.rebuildFrom(
                listOf(
                    created(session, "a", "Dispatchers.IO", 0),
                    started(session, "a", "Dispatchers.IO", 1),
                    completed(session, "a", "Dispatchers.IO", 2),
                ),
            )
            val util = metrics.snapshot(10, 30_000).dispatcherUtilization
            assertEquals(0, util["Dispatchers.IO"] ?: 0, "completed coroutine must not count toward dispatcher util")
        } finally {
            session.close()
        }
    }

    @Test
    fun `a coroutine alive longer than the threshold is flagged a leak the recent one is not`() {
        val session = VizSession("metrics-leak")
        try {
            val metrics = session.metricsProjection
            // Leak age is WALL-CLOCK epoch millis (CR-01): createdAtEpochMs is the basis,
            // NOT tsNanos. 'old' created at epoch 0; 'young' created at epoch 29s.
            metrics.rebuildFrom(
                listOf(
                    created(session, "old", "io", tsNanos = 0, label = "old-leak", createdAtEpochMs = 0),
                    started(session, "old", "io", 1),
                    created(session, "young", "io", tsNanos = 2, createdAtEpochMs = 29_000),
                    started(session, "young", "io", 3),
                ),
            )
            // now = 31s (epoch ms), threshold = 30s -> only 'old' (age 31s) leaks; 'young' is 2s.
            val snap = metrics.snapshot(nowEpochMs = 31_000, leakThresholdMs = 30_000)
            assertEquals(1, snap.leaks.size, "exactly one leak; was ${snap.leaks}")
            val leak = snap.leaks.single()
            assertEquals("old", leak.coroutineId)
            assertEquals("old-leak", leak.label)
            assertTrue(leak.aliveMs >= 30_000, "aliveMs must reflect the age past the threshold; was ${leak.aliveMs}")
        } finally {
            session.close()
        }
    }

    @Test
    fun `rebuildFrom is replay-consistent with live streaming the same events`() {
        val msToNanos = 1_000_000L
        val build = { session: VizSession ->
            listOf<VizEvent>(
                created(session, "a", "Dispatchers.IO", 0),
                started(session, "a", "Dispatchers.IO", 1),
                created(session, "b", "Dispatchers.Default", 2),
                started(session, "b", "Dispatchers.Default", 3),
                completed(session, "a", "Dispatchers.IO", 4),
                created(session, "c", "Dispatchers.IO", 5 * msToNanos),
                started(session, "c", "Dispatchers.IO", 5 * msToNanos + 1),
            )
        }

        // 1) Rebuild path (synchronous).
        val rebuilt = VizSession("metrics-replay-rebuild")
        val rebuildSnap =
            try {
                seq = 0
                val events = build(rebuilt)
                rebuilt.metricsProjection.rebuildFrom(events)
                rebuilt.metricsProjection.snapshot(nowEpochMs = 100 * msToNanos, leakThresholdMs = 30_000)
            } finally {
                rebuilt.close()
            }

        // 2) Live-stream path: send the SAME logical events through the session bus.
        val live = VizSession("metrics-replay-live")
        val liveSnap =
            try {
                seq = 0
                val events = build(live)
                runBlocking {
                    events.forEach { live.send(it) }
                    // The projection collector runs async on sessionScope; wait until it
                    // has observed all events (active converges to the rebuilt active).
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (System.nanoTime() < deadline) {
                        val s = live.metricsProjection.snapshot(100 * msToNanos, 30_000)
                        if (s.active == rebuildSnap.active && s.peak == rebuildSnap.peak) break
                        Thread.sleep(10)
                    }
                }
                live.metricsProjection.snapshot(nowEpochMs = 100 * msToNanos, leakThresholdMs = 30_000)
            } finally {
                live.close()
            }

        assertEquals(rebuildSnap.active, liveSnap.active, "active must match between rebuild and live")
        assertEquals(rebuildSnap.peak, liveSnap.peak, "peak must match between rebuild and live")
        assertEquals(
            rebuildSnap.dispatcherUtilization,
            liveSnap.dispatcherUtilization,
            "dispatcher util must match between rebuild and live",
        )
    }

    @Test
    fun `rebuildFrom replays a known sequence to exact active and peak under one lock`() {
        // WR-01: clear + replay run inside ONE lock, so rebuildFrom over a known sequence
        // yields the deterministic active/peak with no interleaving corruption.
        val session = VizSession("metrics-rebuild-atomic")
        try {
            val metrics = session.metricsProjection
            metrics.rebuildFrom(
                listOf(
                    created(session, "a", "io", 0),
                    started(session, "a", "io", 1),
                    created(session, "b", "io", 2),
                    started(session, "b", "io", 3),
                    created(session, "c", "io", 4),
                    // peak is 3 here (a, b, c all active)
                    completed(session, "a", "io", 5),
                    completed(session, "b", "io", 6),
                ),
            )
            // A second rebuild over the SAME sequence must reproduce the identical result
            // (clear-then-replay is atomic and idempotent).
            metrics.rebuildFrom(
                listOf(
                    created(session, "a", "io", 0),
                    started(session, "a", "io", 1),
                    created(session, "b", "io", 2),
                    started(session, "b", "io", 3),
                    created(session, "c", "io", 4),
                    completed(session, "a", "io", 5),
                    completed(session, "b", "io", 6),
                ),
            )
            val snap = metrics.snapshot(nowEpochMs = 10, leakThresholdMs = 30_000)
            assertEquals(1, snap.active, "only 'c' remains active after the sequence")
            assertEquals(3, snap.peak, "peak high-water mark across the sequence is 3")
        } finally {
            session.close()
        }
    }

    @Test
    fun `throughput decays toward zero on an idle session as the read clock advances`() {
        // WR-04: after a burst, advancing the nowNanos read clock past the window evicts
        // the stale arrivals so the rate decays to ~0 instead of reporting a non-zero /s
        // indefinitely.
        val session = VizSession("metrics-idle-decay")
        try {
            val metrics = session.metricsProjection
            val oneMs = 1_000_000L
            metrics.rebuildFrom(
                listOf(
                    created(session, "a", "io", 0),
                    started(session, "a", "io", 100 * oneMs),
                    created(session, "b", "io", 200 * oneMs),
                    started(session, "b", "io", 300 * oneMs),
                ),
            )
            // Right after the burst: positive throughput.
            val live =
                metrics.snapshot(nowEpochMs = 0, leakThresholdMs = 30_000, nowNanos = 300 * oneMs)
            assertTrue(live.throughputPerSec > 0.0, "throughput must be positive right after a burst")

            // 60s later with no new arrivals (window is 10s): the window is empty -> ~0 /s.
            val idle =
                metrics.snapshot(nowEpochMs = 0, leakThresholdMs = 30_000, nowNanos = 60_000 * oneMs)
            assertEquals(0.0, idle.throughputPerSec, "idle throughput must decay to 0; was ${idle.throughputPerSec}")
        } finally {
            session.close()
        }
    }
}
