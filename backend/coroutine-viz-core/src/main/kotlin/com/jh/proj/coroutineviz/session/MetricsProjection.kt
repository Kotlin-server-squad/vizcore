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
     * Rebuild all metric state by replaying [events] in order. Clears first so the
     * call is safe at reconstruction time, then a [snapshot] taken afterwards equals
     * one taken after live-streaming the same events (replay consistency, D-05).
     */
    fun rebuildFrom(events: List<VizEvent>) {
        synchronized(lock) {
            activeCoroutines.clear()
            peakActive = 0
            recentEventTsNanos.clear()
        }
        events.forEach { processEvent(it) }
    }

    private fun processEvent(event: VizEvent) {
        synchronized(lock) {
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
    }

    /** Append [tsNanos] to the throughput window and evict timestamps older than the window. */
    private fun recordArrival(tsNanos: Long) {
        recentEventTsNanos.addLast(tsNanos)
        val cutoff = tsNanos - THROUGHPUT_WINDOW_NANOS
        while (recentEventTsNanos.isNotEmpty() && recentEventTsNanos.peekFirst() < cutoff) {
            recentEventTsNanos.removeFirst()
        }
    }

    /**
     * Read-only computation of the current metric set. [nowEpochMs] is the WALL-CLOCK
     * read clock used for leak age (System.currentTimeMillis in production; an explicit
     * epoch-millis clock in tests). It is epoch millis — NOT [System.nanoTime] — so the
     * leak-age subtraction is well-defined across a restart boundary (CR-01).
     * [leakThresholdMs] flags any still-active coroutine older than the threshold.
     */
    fun snapshot(
        nowEpochMs: Long,
        leakThresholdMs: Long,
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
                throughputPerSec = computeThroughput(),
                dispatcherUtilization = dispatcherUtilization,
                leaks = leaks,
            )
        }

    /**
     * Observed events/sec over the sliding window: (events in window) / (window span
     * in seconds). Returns 0 when fewer than two events have arrived (no measurable
     * span). Honest about poll limits — see the class doc (Pitfall 2).
     */
    private fun computeThroughput(): Double {
        if (recentEventTsNanos.size < 2) return 0.0
        val spanNanos = recentEventTsNanos.peekLast() - recentEventTsNanos.peekFirst()
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
