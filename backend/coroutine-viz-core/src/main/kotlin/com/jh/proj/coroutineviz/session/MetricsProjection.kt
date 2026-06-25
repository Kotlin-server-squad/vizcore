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
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * Per-session aggregate metrics projection (RCO-07, D-04/D-05/D-06/D-07).
 *
 * Mirrors [ProjectionService] EXACTLY: it subscribes to the session's [EventBus]
 * in `init` and exposes [rebuildFrom] so it is replay/DB-rehydrate consistent
 * (D-05). [VizSession.rehydrateFromStore] calls [rebuildFrom] alongside the
 * hierarchy projection so metrics are non-empty after a DB-backed reconstruction
 * (Pitfall 5).
 *
 * Computed metrics:
 * - **active** — count of coroutines in a non-terminal state. A coroutine becomes
 *   active on [CoroutineCreated] and leaves the active set on
 *   [CoroutineCompleted]/[CoroutineCancelled]/[CoroutineFailed].
 * - **peak** — the monotonic high-water mark of [active] observed across the stream.
 * - **throughputPerSec** — observed events/sec over a sliding window of recent event
 *   arrival timestamps. This is intentionally honest about the poll-bounded source:
 *   coroutines that start and finish between DebugProbes polls are never observed,
 *   so this reflects what the projection actually saw, not ground truth (Pitfall 2).
 * - **dispatcherUtilization** — `Map<dispatcherName, activeCount>` derived from the
 *   `scopeId` carried on coroutine events (08-01 sets `scopeId = dispatcherName ?:
 *   sourceId` on the DebugProbes path). It is NOT derived from `ThreadAssigned` —
 *   the DebugProbes synthesizer never emits one, so the `/threads` projection is
 *   empty for real-app sessions (Pitfall 1).
 * - **leaks** — coroutines still alive (created, not yet terminal) whose age
 *   `nowEpochMs - createdAtEpochMs` exceeds the caller-supplied threshold (D-07).
 *   The leak-age basis is WALL-CLOCK epoch millis ([CoroutineCreated.createdAtEpochMs],
 *   set from [System.currentTimeMillis] at construction), NOT [System.nanoTime]: epoch
 *   millis has a fixed origin, so the subtraction stays well-defined across a backend
 *   restart on the DB-rehydrated path that the /metrics route serves (CR-01). The route
 *   passes [System.currentTimeMillis] as `nowEpochMs`.
 *
 * Memory is bounded to the ACTIVE set: a coroutine's leak/dispatcher tracking is
 * dropped on any terminal event (T-08-06). The throughput window is also bounded
 * to the most recent [THROUGHPUT_WINDOW_NANOS] of arrivals.
 *
 * All mutable state is guarded by [lock] because the bus-collector runs on
 * [VizSession.sessionScope] (a background dispatcher) while [snapshot] is read
 * from the request thread.
 *
 * ## Two intentionally-distinct clocks — do NOT collapse them
 * This projection reads two DIFFERENT time bases on purpose:
 * - **Leak age** uses a WALL-CLOCK epoch-millis basis ([CoroutineCreated.createdAtEpochMs],
 *   read against `nowEpochMs` = [System.currentTimeMillis]). Epoch millis has a fixed
 *   origin, so leak age stays well-defined across a backend restart on the DB-rehydrated
 *   path (CR-01). It must NOT use [System.nanoTime].
 * - **Throughput** uses a per-process MONOTONIC [System.nanoTime] basis (event [tsNanos],
 *   evicted against a `nowNanos` read clock captured inside [snapshot]). nanoTime is
 *   immune to wall-clock adjustments, which is what a /s rate wants — but it is meaningless
 *   across processes, so on the DB-rehydrated path (no live arrivals) throughput correctly
 *   reads ~0.
 * Collapsing these onto one clock would re-break either leak age (if forced onto nanoTime)
 * or throughput (if forced onto epoch millis) — see WR-04 / CR-01.
 */
class MetricsProjection(
    private val session: VizSession,
) {
    private val lock = Any()

    /** Per-active-coroutine tracking: id -> (label, createdAtEpochMs, dispatcherName). */
    private val activeCoroutines = LinkedHashMap<String, ActiveCoroutine>()

    /** Monotonic high-water mark of [activeCoroutines] size. */
    private var peakActive = 0

    /** Recent event arrival timestamps (tsNanos), bounded to the sliding window. */
    private val recentEventTsNanos = ArrayDeque<Long>()

    init {
        // Subscribe to the event bus, exactly like ProjectionService.
        session.sessionScope.launch {
            session.eventBus.stream().collect { event ->
                processEvent(event)
            }
        }
    }

    /**
     * Rebuild all metric state by replaying [events] in order. The clear + full replay
     * run inside ONE [lock] critical section (WR-01): a live bus event delivered during a
     * DB-rehydrate can no longer interleave between the clear and the replay and corrupt
     * active/peak. A [snapshot] taken afterwards equals one taken after live-streaming the
     * same events (replay consistency, D-05).
     */
    fun rebuildFrom(events: List<VizEvent>) {
        synchronized(lock) {
            activeCoroutines.clear()
            peakActive = 0
            recentEventTsNanos.clear()
            events.forEach { applyLocked(it) }
        }
    }

    /** Live bus path: acquire the lock, then apply. */
    private fun processEvent(event: VizEvent) {
        synchronized(lock) {
            applyLocked(event)
        }
    }

    /**
     * Apply a single event to the metric state. The caller MUST already hold [lock]
     * (the live [processEvent] path and the [rebuildFrom] replay both wrap this).
     */
    private fun applyLocked(event: VizEvent) {
        recordArrival(event.tsNanos)
        when (event) {
            is CoroutineCreated -> {
                activeCoroutines[event.coroutineId] =
                    ActiveCoroutine(
                        label = event.label,
                        createdAtEpochMs = event.createdAtEpochMs,
                        dispatcherName = event.scopeId,
                    )
                if (activeCoroutines.size > peakActive) peakActive = activeCoroutines.size
            }

            // Terminal states: drop from the active set (bounds memory, T-08-06).
            is CoroutineCompleted -> activeCoroutines.remove(event.coroutineId)
            is CoroutineCancelled -> activeCoroutines.remove(event.coroutineId)
            is CoroutineFailed -> activeCoroutines.remove(event.coroutineId)

            // Non-terminal lifecycle transitions keep the coroutine active; they
            // are still observed events (counted toward throughput above).
            is CoroutineStarted,
            is CoroutineSuspended,
            is CoroutineResumed,
            is CoroutineBodyCompleted,
            -> Unit

            else -> Unit
        }
    }

    /**
     * Append [tsNanos] to the throughput window and bound it against the most recent
     * arrival. The authoritative eviction for the rate happens in [computeThroughput]
     * against the READ clock (WR-04) — this append-time trim only keeps the deque bounded
     * between reads.
     */
    private fun recordArrival(tsNanos: Long) {
        recentEventTsNanos.addLast(tsNanos)
        evictOlderThan(tsNanos - THROUGHPUT_WINDOW_NANOS)
    }

    /** Drop arrivals at or before [cutoffNanos] from the front of the window. */
    private fun evictOlderThan(cutoffNanos: Long) {
        while (recentEventTsNanos.isNotEmpty() && recentEventTsNanos.peekFirst() < cutoffNanos) {
            recentEventTsNanos.removeFirst()
        }
    }

    /**
     * Read-only computation of the current metric set.
     *
     * [nowEpochMs] is the WALL-CLOCK read clock for LEAK AGE (System.currentTimeMillis in
     * production; an explicit epoch-millis clock in tests). It is epoch millis — NOT
     * [System.nanoTime] — so the leak-age subtraction is well-defined across a restart
     * boundary (CR-01). [leakThresholdMs] flags any still-active coroutine older than it.
     *
     * [nowNanos] is the SEPARATE per-process monotonic read clock for THROUGHPUT eviction
     * (WR-04); it defaults to [System.nanoTime] for the live route path and is supplied
     * explicitly by tests that need to advance the throughput window deterministically.
     * The two clocks are intentionally distinct — see the class doc.
     */
    fun snapshot(
        nowEpochMs: Long,
        leakThresholdMs: Long,
        nowNanos: Long = System.nanoTime(),
    ): MetricsSnapshot =
        synchronized(lock) {
            val active = activeCoroutines.size

            val dispatcherUtilization =
                activeCoroutines.values
                    .groupingBy { it.dispatcherName }
                    .eachCount()

            val leaks =
                activeCoroutines
                    .asSequence()
                    .mapNotNull { (id, c) ->
                        // Wall-clock age in millis. A non-positive age (clock skew, or a
                        // default 0L stamp on a legacy row) is never reported as a leak.
                        val aliveMs = nowEpochMs - c.createdAtEpochMs
                        if (aliveMs > 0 && aliveMs > leakThresholdMs) {
                            LeakInfo(
                                coroutineId = id,
                                label = c.label,
                                aliveMs = aliveMs,
                            )
                        } else {
                            null
                        }
                    }
                    .sortedByDescending { it.aliveMs }
                    .toList()

            MetricsSnapshot(
                active = active,
                peak = peakActive,
                // Throughput reads its own per-process monotonic clock (nanoTime), NOT the
                // epoch leak clock — see the two-clock note in the class doc.
                throughputPerSec = computeThroughput(nowNanos),
                dispatcherUtilization = dispatcherUtilization,
                leaks = leaks,
            )
        }

    /**
     * Observed events/sec over the sliding window, evicted against the READ clock
     * [nowNanos] (WR-04): arrivals older than `nowNanos - THROUGHPUT_WINDOW_NANOS` are
     * dropped, then the retained count is divided by the elapsed span up to [nowNanos]
     * (capped at the window). So once events STOP, the window empties as `nowNanos`
     * advances and the rate decays toward 0 — a burst from long ago no longer reports a
     * stale non-zero /s indefinitely. Returns 0 with fewer than two retained arrivals (no
     * measurable span). Honest about poll limits — see the class doc (Pitfall 2).
     */
    private fun computeThroughput(nowNanos: Long): Double {
        evictOlderThan(nowNanos - THROUGHPUT_WINDOW_NANOS)
        if (recentEventTsNanos.size < 2) return 0.0
        val firstRetained = recentEventTsNanos.peekFirst()
        // Span from the oldest retained arrival up to the read clock (not last-arrival),
        // so the divisor keeps growing while the session is idle.
        val spanNanos = (nowNanos - firstRetained).coerceAtMost(THROUGHPUT_WINDOW_NANOS)
        if (spanNanos <= 0L) return 0.0
        val spanSeconds = spanNanos.toDouble() / NANOS_PER_SECOND
        return recentEventTsNanos.size.toDouble() / spanSeconds
    }

    private data class ActiveCoroutine(
        val label: String?,
        val createdAtEpochMs: Long,
        val dispatcherName: String,
    )

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000.0

        /** Sliding window for the observed-throughput estimate (10s of recent arrivals). */
        private const val THROUGHPUT_WINDOW_NANOS = 10_000_000_000L
    }
}

/**
 * Plain (non-serializable) core snapshot of the per-session aggregate metrics. The
 * route layer maps this to the wire DTO (`MetricsResponse`), mirroring how
 * [ProjectionService] returns plain `HierarchyNode`s that the route serializes —
 * keeping coroutine-viz-core free of route/DTO concerns.
 */
data class MetricsSnapshot(
    val active: Int,
    val peak: Int,
    val throughputPerSec: Double,
    val dispatcherUtilization: Map<String, Int>,
    val leaks: List<LeakInfo>,
)

/** A still-active coroutine older than the leak threshold (D-07). */
data class LeakInfo(
    val coroutineId: String,
    val label: String?,
    val aliveMs: Long,
)
