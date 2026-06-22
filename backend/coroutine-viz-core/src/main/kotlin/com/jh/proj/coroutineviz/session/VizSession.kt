package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.job.JobStateChanged
import com.jh.proj.coroutineviz.models.RuntimeSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Central container for a visualization session.
 *
 * A VizSession holds all the state and infrastructure needed to track coroutine
 * execution within a single visualization context. Each session maintains:
 * - An [EventBus] for real-time event distribution to subscribers
 * - An [EventStore] for persistent storage of all events
 * - A [RuntimeSnapshot] reflecting current coroutine states
 * - A [ProjectionService] for computing derived views (timelines, hierarchies)
 *
 * Events flow through the session in the following order:
 * 1. Event is appended to [store] (persistent log)
 * 2. Event is applied to [snapshot] via [EventApplier] (state update)
 * 3. Event is broadcast via [eventBus] (real-time notifications)
 *
 * All three steps run atomically under a single send lock, and the event's
 * seq is finalized inside the same critical section. This guarantees that
 * store order, seq order, and live-bus delivery order are identical — the
 * invariant SSE replay deduplication (max-seq watermark) depends on.
 *
 * @property sessionId Unique identifier for this session
 */
class VizSession(
    val sessionId: String,
    maxEvents: Int = 100_000,
    val createdAtMs: Long = System.currentTimeMillis(),
    /**
     * Event-store factory seam. Defaults to the in-memory bounded [EventStore]
     * so the SDK default is byte-for-byte unchanged (D-04a). The persistence
     * layer (`:backend`) injects a factory that returns a DB-backed
     * `EventStoreInterface` scoped to this session — without adding any DB
     * dependency to coroutine-viz-core (SDK purity preserved).
     */
    eventStoreFactory: (sessionId: String) -> EventStoreInterface = { EventStore(maxEvents) },
) {
    // Session-scoped coroutine scope for async operations
    val sessionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val eventBus = EventBus()
    val bus = eventBus // Alias for backwards compatibility
    val store: EventStoreInterface = eventStoreFactory(sessionId)
    val snapshot = RuntimeSnapshot()
    val jobMonitor = JobStatusMonitor(session = this, 100)

    private val applier = EventApplier(snapshot)
    private var monitoringEnabled = false

    // Sequence generator to keep events globally ordered in this session
    private val seqGenerator = AtomicLong(0)

    // Serializes seq finalization + store append + snapshot apply + bus broadcast (WR-02/WR-12).
    private val sendLock = Any()

    // Highest seq appended so far; guarded by [sendLock].
    private var lastSentSeq = 0L

    val projectionService = ProjectionService(this)

    /**
     * Optional callback invoked after each event is successfully emitted via [send].
     * Used by the metrics layer to increment the events.emitted counter without
     * adding a Micrometer dependency to coroutine-viz-core.
     */
    var onEventEmitted: (() -> Unit)? = null

    /**
     * Optional callback invoked each time an event is dropped due to the bounded
     * EventStore capacity (mirrors EventStore.onEvict but at the session level).
     * Used by the metrics layer to increment the events.dropped counter.
     */
    var onEventDropped: (() -> Unit)? = null

    /**
     * Optional callback invoked with the wall-clock nanos spent processing a single
     * event inside [send] (store + applier + bus). Used for event.processing.duration.
     */
    var onEventProcessed: ((Long) -> Unit)? = null

    /**
     * Allocate next sequence number.
     */
    fun nextSeq(): Long = seqGenerator.incrementAndGet()

    /**
     * Non-suspending event send - PREFERRED for most use cases.
     * Synchronously emits event to bus, stores it, and updates snapshot.
     *
     * Seq finalization, store append, snapshot apply, and bus broadcast run as one
     * atomic critical section: with concurrent senders, thread A can construct
     * seq=10, thread B construct seq=11 and reach send() first. Without the lock
     * the store (and bus) would deliver 11 before 10, breaking the max-seq
     * watermark dedup used by SSE replay (WR-02). Inside the lock, an event whose
     * provisional seq is no longer the highest is re-stamped with a fresh seq, so
     * store order == seq order always holds (WR-12).
     */
    fun send(event: VizEvent) {
        // Capture the callback once: assigning onEventProcessed between a null
        // check and the invocation must not produce a garbage `nanoTime - 0`
        // duration sample (WR-12 check-then-act race).
        val onProcessed = onEventProcessed
        val startNanos = if (onProcessed != null) System.nanoTime() else 0L
        synchronized(sendLock) {
            if (event.seq <= lastSentSeq) {
                // A concurrent sender appended a higher seq after this event was
                // constructed (or the event carries a stale/placeholder seq):
                // re-stamp so seq order matches append order.
                event.seq = seqGenerator.incrementAndGet()
            }
            lastSentSeq = event.seq
            store.record(event)
            applier.apply(event)
            eventBus.send(event)
        }
        onEventEmitted?.invoke()
        onProcessed?.invoke(System.nanoTime() - startNanos)
    }

    /**
     * Rebuild both read models — the [snapshot] and the [projectionService] — by
     * replaying every event currently in [store].
     *
     * A VizSession reconstructed from a DB-backed store (ADR-015) starts with
     * empty read models: the events were persisted by a previous instance and
     * never flow through [send] again, so the coroutine tree, thread lanes, and
     * timeline projections render empty even though the events exist in the store.
     * Persistence stores call this right after construction. It deliberately does
     * NOT re-[store.record] or re-[eventBus.send] the events (no DB duplication,
     * no spurious SSE delivery); it only repopulates the in-memory views and
     * advances the seq watermark so any subsequent live [send] keeps ordering.
     */
    fun rehydrateFromStore() {
        val events = store.all() // ExposedEventStore returns these ordered by seq
        if (events.isEmpty()) return
        synchronized(sendLock) {
            events.forEach { applier.apply(it) }
            val maxSeq = events.maxOf { it.seq }
            if (maxSeq > lastSentSeq) {
                lastSentSeq = maxSeq
                seqGenerator.set(maxSeq)
            }
        }
        projectionService.rebuildFrom(events)
    }

    /**
     * Async event send - for non-coroutine contexts.
     * Launches on session scope to emit event asynchronously.
     */
    fun sendAsync(event: VizEvent) {
        sessionScope.launch {
            send(event)
        }
    }

    /**
     * Suspending version - for backwards compatibility.
     * Delegates to synchronous send() since bus.send() doesn't suspend.
     */
    fun sent(event: VizEvent) {
        send(event)
    }

    fun enableJobMonitoring() {
        if (!monitoringEnabled) {
            jobMonitor.start()
            monitoringEnabled = true
        }
    }

    /**
     * Clean up session resources.
     * Call this when session is closed.
     */
    fun close() {
        jobMonitor.stop()
        sessionScope.cancel()
    }

    fun coroutineLifecycleFlow(): Flow<CoroutineEvent> {
        return eventBus.stream()
            .filterIsInstance<CoroutineEvent>()
            .filter { event ->
                event.kind in
                    setOf(
                        "CoroutineCreated",
                        "CoroutineStarted",
                        "CoroutineSuspended",
                        "CoroutineResumed",
                        "CoroutineCompleted",
                        "CoroutineCancelled",
                        "CoroutineFailed",
                        "CoroutineBodyCompleted",
                    )
            }
    }

    fun jobStateFlow(): Flow<JobStateChanged> {
        return eventBus.stream().filterIsInstance<JobStateChanged>()
    }

    fun mergeTimeFlow(): Flow<VizEvent> {
        return merge(coroutineLifecycleFlow(), jobStateFlow())
    }

    /**
     * Get merged flow of coroutine lifecycle + job state events,
     * sorted by timestamp (newest first)
     */
    fun mergedTimelineFlow(newestFirst: Boolean = true): Flow<VizEvent> {
        return merge(
            coroutineLifecycleFlow(),
            jobStateFlow(),
        ).let { flow ->
            if (newestFirst) {
                // For real-time streams, events are already time-ordered
                // But we can buffer and sort if needed
                flow
            } else {
                flow
            }
        }
    }

    /**
     * Get paired flow that correlates coroutine events with job state changes
     * Events are paired if they share the same jobId/coroutineId
     */
    fun correlatedFlow(): Flow<CorrelatedEventPair> {
        val coroutineFlow = coroutineLifecycleFlow()
        val jobFlow = jobStateFlow()

        return combine(coroutineFlow, jobFlow) { coroutineEvent, jobEvent ->
            // Correlate by jobId
            if (coroutineEvent.jobId == jobEvent.jobId) {
                CorrelatedEventPair(
                    coroutineEvent = coroutineEvent,
                    jobEvent = jobEvent,
                    timeDelta = coroutineEvent.tsNanos - jobEvent.tsNanos,
                )
            } else {
                null
            }
        }.filterNotNull()
    }

    /**
     * Get timeline for specific coroutine, with both lifecycle and job events
     * Returns list sorted by timestamp (newest first by default)
     */
    fun getCoroutineTimeline(
        coroutineId: String,
        newestFirst: Boolean = true,
    ): List<VizEvent> {
        val allEvents = store.all()

        val filtered =
            allEvents.filter { event ->
                when (event) {
                    is CoroutineEvent -> event.coroutineId == coroutineId
                    is JobStateChanged -> {
                        // Find if this job belongs to our coroutine
                        snapshot.coroutines[coroutineId]?.jobId == event.jobId
                    }
                    else -> false
                }
            }

        return if (newestFirst) {
            filtered.sortedByDescending { it.tsNanos }
        } else {
            filtered.sortedBy { it.tsNanos }
        }
    }

    /**
     * Get split timeline (two separate lists)
     */
    fun getSplitTimeline(
        coroutineId: String,
        newestFirst: Boolean = true,
    ): SplitTimeline {
        val allEvents = store.all()

        val coroutineEvents =
            allEvents
                .filterIsInstance<CoroutineEvent>()
                .filter { it.coroutineId == coroutineId }

        val jobId = snapshot.coroutines[coroutineId]?.jobId
        val jobEvents =
            if (jobId != null) {
                allEvents
                    .filterIsInstance<JobStateChanged>()
                    .filter { it.jobId == jobId }
            } else {
                emptyList()
            }

        return SplitTimeline(
            coroutineEvents =
                if (newestFirst) {
                    coroutineEvents.sortedByDescending { it.tsNanos }
                } else {
                    coroutineEvents.sortedBy { it.tsNanos }
                },
            jobEvents =
                if (newestFirst) {
                    jobEvents.sortedByDescending { it.tsNanos }
                } else {
                    jobEvents.sortedBy { it.tsNanos }
                },
            merged =
                if (newestFirst) {
                    (coroutineEvents + jobEvents).sortedByDescending { it.tsNanos }
                } else {
                    (coroutineEvents + jobEvents).sortedBy { it.tsNanos }
                },
        )
    }
}

// Data classes for the new types
data class CorrelatedEventPair(
    val coroutineEvent: CoroutineEvent,
    val jobEvent: JobStateChanged,
    // coroutineEvent.tsNanos - jobEvent.tsNanos
    val timeDelta: Long,
)

data class SplitTimeline(
    val coroutineEvents: List<CoroutineEvent>,
    val jobEvents: List<JobStateChanged>,
    // Both types merged and sorted
    val merged: List<VizEvent>,
)
