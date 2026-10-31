package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.job.JobStateChanged
import com.jh.proj.coroutineviz.health.SummariesUnavailableException
import com.jh.proj.coroutineviz.health.SummaryTable
import com.jh.proj.coroutineviz.health.SummaryView
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
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
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
 * 3. Event is applied to the summary table (durable per-coroutine summaries, only with `keepSummaries = true`)
 * 4. Event is broadcast via [eventBus] (real-time notifications)
 *
 * All four steps run atomically under a single send lock, and the event's
 * seq is finalized inside the same critical section. This guarantees that
 * store order, seq order, and live-bus delivery order are identical — the
 * invariant SSE replay deduplication (max-seq watermark) depends on.
 *
 * The session owns its seq space. Events produced in-process ([send]) and events
 * received from a remote client ([ingest]) draw from one counter, so the
 * session's event order is strictly increasing with no duplicates. A remote
 * event's own seq is never kept: [ingest] always assigns a fresh server seq.
 *
 * @property sessionId Unique identifier for this session
 */
class VizSession(
    val sessionId: String,
    maxEvents: Int = 100_000,
    /**
     * Wall-clock source for [createdAtMs] and [lastActivityAtMs]. Defaults to
     * [System.currentTimeMillis]; override in tests so retention can be driven
     * deterministically without sleeping.
     */
    private val clock: () -> Long = System::currentTimeMillis,
    val createdAtMs: Long = clock(),
    /**
     * Whether to keep the per-coroutine summary table (spec 2026-10-21, C-2). Opt-in: only a host that reads
     * the summaries asks for it. [SessionManager.createSession] (the backend's in-memory sessions) does; the
     * SDK's in-app session never reads them and keeps the default, sparing the app the memory and the work.
     */
    keepSummaries: Boolean = false,
    /**
     * Event-store factory seam. Defaults to the in-memory bounded [EventStore]
     * so the SDK default is byte-for-byte unchanged (D-04a). The persistence
     * layer (`:backend`) injects a factory that returns a DB-backed
     * `EventStoreInterface` scoped to this session — without adding any DB
     * dependency to coroutine-viz-core (SDK purity preserved).
     */
    eventStoreFactory: (sessionId: String) -> EventStoreInterface = { EventStore(maxEvents) },
) {
    /**
     * The 0.2.x constructor, kept (hidden from source) so code compiled against 0.2.x still links:
     * `keepSummaries` was added in the middle of the parameter list, which changed the JVM signatures.
     */
    @Deprecated("binary compatibility with 0.2.x", level = DeprecationLevel.HIDDEN)
    constructor(
        sessionId: String,
        maxEvents: Int = 100_000,
        clock: () -> Long = System::currentTimeMillis,
        createdAtMs: Long = clock(),
        eventStoreFactory: (String) -> EventStoreInterface = { EventStore(maxEvents) },
    ) : this(sessionId, maxEvents, clock, createdAtMs, false, eventStoreFactory)

    // Session-scoped coroutine scope for async operations
    val sessionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val eventBus = EventBus()
    val bus = eventBus // Alias for backwards compatibility
    val store: EventStoreInterface = eventStoreFactory(sessionId)
    val snapshot = RuntimeSnapshot()
    val jobMonitor = JobStatusMonitor(session = this, 100)

    /**
     * Wall-clock of the LAST activity on this session: the last event appended
     * through [send]/[ingest], or the last ingest socket open/close. Initialized to
     * [createdAtMs] so a session that never sees an event ages exactly as before.
     *
     * WHY: [RetentionPolicy] used to age a session by [createdAtMs], so a real app
     * streaming through the ingest socket was evicted after `session.maxAgeMs`
     * (1 h by default) WHILE STILL SENDING — the session vanished mid-run and the
     * client's next frame hit a session that no longer existed (#129). Aging by
     * last activity keeps a busy session alive and still evicts idle ones.
     *
     * Written under [sendLock] on the append path and volatile-published, so the
     * retention sweep (another thread) always reads a value at least as recent as
     * the last completed append.
     */
    @Volatile
    var lastActivityAtMs: Long = createdAtMs
        private set

    /**
     * Number of ingest WebSockets currently open against this session. Maintained by
     * [ingestSocketOpened]/[ingestSocketClosed] from the route layer.
     */
    private val openIngestSockets = AtomicInteger(0)

    /**
     * Whether a remote client currently holds an ingest socket open. A session in
     * this state is NEVER evicted by [RetentionPolicy] — neither by age nor by
     * count — because a connected client is live activity even between frames
     * (a long-idle-then-busy app is the exact #129 case).
     */
    val hasOpenIngestSocket: Boolean get() = openIngestSockets.get() > 0

    /** Record that an ingest socket opened; counts as activity. */
    fun ingestSocketOpened() {
        openIngestSockets.incrementAndGet()
        lastActivityAtMs = clock()
    }

    /**
     * Record that an ingest socket closed; counts as activity, so a session whose
     * client just disconnected starts its idle clock from the disconnect, not from
     * creation. Clamped at zero so an unbalanced close (defensive) cannot make
     * [hasOpenIngestSocket] wrong for a later real open.
     */
    fun ingestSocketClosed() {
        openIngestSockets.updateAndGet { if (it > 0) it - 1 else 0 }
        lastActivityAtMs = clock()
    }

    private val applier = EventApplier(snapshot)

    // Durable per-coroutine summaries (spec 2026-10-21, C-2); guarded by [sendLock] like the snapshot.
    private val summaryTable: SummaryTable? = if (keepSummaries) SummaryTable() else null

    /** Test hook: runs before each event is applied to the summary table, to inject a failure. */
    @Volatile
    internal var summaryFailureForTest: ((VizEvent) -> Unit)? = null

    // Set once when applying an event to [summaryTable] fails; read by [readSummaries].
    @Volatile
    private var summariesDegraded = false
    private var monitoringEnabled = false

    /** Whether job monitoring is currently active (test/diagnostic, WR-07). */
    fun isMonitoringEnabled(): Boolean = monitoringEnabled

    // Sequence generator to keep events globally ordered in this session
    private val seqGenerator = AtomicLong(0)

    // Serializes seq finalization + store append + snapshot apply + bus broadcast (WR-02/WR-12).
    private val sendLock = Any()

    // Highest seq appended so far; guarded by [sendLock].
    private var lastSentSeq = 0L

    val projectionService = ProjectionService(this)

    /**
     * Per-session aggregate metrics (active/peak/throughput/dispatcher-util/leaks,
     * RCO-07). Like [projectionService] it subscribes to [eventBus] in its own
     * `init` and is rebuilt from the store on [rehydrateFromStore] (Pitfall 5).
     */
    val metricsProjection = MetricsProjection(this)

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
     * event inside [send] (store + applier + summaries + bus). Used for event.processing.duration.
     */
    var onEventProcessed: ((Long) -> Unit)? = null

    /**
     * Allocate next sequence number.
     */
    fun nextSeq(): Long = seqGenerator.incrementAndGet()

    /**
     * Read the session's summaries under the send lock, so a read never sees a half-applied event.
     * Keep [block] short: it holds up event ingestion while it runs. The view is live and only safe under
     * the lock, so [block] must copy what it returns (e.g. `it.all.toList()`), never the view itself.
     *
     * @throws SummariesUnavailableException if the session was not created with `keepSummaries = true`, or if
     *   applying an event to the table failed and the table was abandoned (it is never applied again).
     */
    fun <T> readSummaries(block: (SummaryView) -> T): T {
        val table = summaryTable ?: throw SummariesUnavailableException("session $sessionId keeps no summaries")
        return synchronized(sendLock) {
            if (summariesDegraded) throw SummariesUnavailableException("session $sessionId summaries are degraded")
            block(table)
        }
    }

    /**
     * Summary bookkeeping must never break ingestion or loading: a failure here (e.g. a duplicate seq in a
     * stored log) abandons the table, logs once, and leaves store, snapshot and bus untouched.
     * Caller holds [sendLock].
     */
    private fun applyToSummaries(event: VizEvent) {
        val table = summaryTable ?: return
        if (summariesDegraded) return
        try {
            summaryFailureForTest?.invoke(event)
            table.apply(event)
        } catch (e: RuntimeException) {
            summariesDegraded = true
            logger.error("Summaries of session {} abandoned at seq {}", sessionId, event.seq, e)
        }
    }

    /**
     * Non-suspending event send - PREFERRED for most use cases.
     * Synchronously emits event to bus, stores it, and updates snapshot.
     *
     * Use this for events produced in-process, whose provisional seq came from
     * [nextSeq]. Events received from a remote client go through [ingest].
     *
     * Seq finalization, store append, snapshot apply, and bus broadcast run as one
     * atomic critical section: with concurrent senders, thread A can construct
     * seq=10, thread B construct seq=11 and reach send() first. Without the lock
     * the store (and bus) would deliver 11 before 10, breaking the max-seq
     * watermark dedup used by SSE replay (WR-02). Inside the lock, an event whose
     * provisional seq is no longer the highest is re-stamped with a fresh seq, so
     * store order == seq order always holds (WR-12). A kept provisional seq also
     * advances the generator, so [send] and [ingest] share one strictly
     * increasing counter.
     */
    fun send(event: VizEvent) {
        append(event) {
            if (event.seq <= lastSentSeq) {
                // A concurrent sender appended a higher seq after this event was
                // constructed (or the event carries a stale/placeholder seq):
                // re-stamp so seq order matches append order.
                event.seq = assignNextSeq()
            } else {
                seqGenerator.updateAndGet { maxOf(it, event.seq) }
            }
        }
    }

    /**
     * Publish an event received from a remote client (the ingest transport).
     *
     * The server, not the client, owns the session's seq space: under the send
     * lock the event's seq is ALWAYS replaced with `max(seqGenerator, lastSentSeq) + 1`
     * and the generator advances to that value. Whatever seq the client sent
     * (a restart from 1, a decreasing or duplicate value, `Long.MAX_VALUE`) is
     * discarded, so every ingested event is kept and the session's order stays
     * strictly increasing even when ingest interleaves with in-process [send].
     */
    fun ingest(event: VizEvent) {
        append(event) { event.seq = assignNextSeq() }
    }

    /**
     * Next seq after everything allocated or appended so far. Caller must hold
     * [sendLock]; the generator is advanced atomically so a concurrent [nextSeq]
     * outside the lock never hands out the same value.
     */
    private fun assignNextSeq(): Long = seqGenerator.updateAndGet { maxOf(it, lastSentSeq) + 1 }

    /**
     * Shared append path of [send] and [ingest]: finalize the seq with [finalizeSeq],
     * then store, apply and broadcast, all under [sendLock].
     */
    private inline fun append(
        event: VizEvent,
        finalizeSeq: () -> Unit,
    ) {
        // Capture the callback once: assigning onEventProcessed between a null
        // check and the invocation must not produce a garbage `nanoTime - 0`
        // duration sample (WR-12 check-then-act race).
        val onProcessed = onEventProcessed
        val startNanos = if (onProcessed != null) System.nanoTime() else 0L
        synchronized(sendLock) {
            finalizeSeq()
            lastSentSeq = event.seq
            store.record(event)
            applier.apply(event)
            applyToSummaries(event)
            eventBus.send(event)
            // Retention ages a session by this stamp, not by createdAtMs (#129).
            lastActivityAtMs = clock()
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
     * no spurious SSE delivery); it only repopulates the in-memory views (snapshot,
     * projections and summaries) and advances the seq watermark so any subsequent
     * live [send] keeps ordering.
     *
     * Call it once, before the session is published: it reads [store] outside the
     * send lock. A summary failure degrades the
     * summaries only; the rest of the rehydration still completes.
     */
    fun rehydrateFromStore() {
        val events = store.all() // ExposedEventStore returns these ordered by seq
        if (events.isEmpty()) return
        synchronized(sendLock) {
            events.forEach {
                applier.apply(it)
                applyToSummaries(it)
            }
            val maxSeq = events.maxOf { it.seq }
            if (maxSeq > lastSentSeq) {
                lastSentSeq = maxSeq
                seqGenerator.set(maxSeq)
            }
        }
        projectionService.rebuildFrom(events)
        metricsProjection.rebuildFrom(events)
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
     * Stop job monitoring if it was enabled. Idempotent (a no-op when monitoring
     * is already off), and safe to call independently of [close] — a source that
     * enabled monitoring in `start()` can release it in `stop()` so the source's
     * `isRunning=false` is truthful and the monitor is not still emitting after
     * the source claims to have stopped (Phase 6 WR-07). [close] also calls this.
     */
    fun disableJobMonitoring() {
        if (monitoringEnabled) {
            jobMonitor.stop()
            monitoringEnabled = false
        }
    }

    /**
     * Clean up session resources.
     * Call this when session is closed.
     */
    fun close() {
        disableJobMonitoring()
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

    private companion object {
        private val logger = LoggerFactory.getLogger(VizSession::class.java)
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
