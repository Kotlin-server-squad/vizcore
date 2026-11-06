package com.jh.proj.coroutineviz.client

import com.jh.proj.coroutineviz.events.PollWatermark
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.session.EventBus
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Lifetime-scoped outbound bridge between the `replay = 0` session [EventBus] and
 * the churning ingest WebSockets (CR-01 / RCO-04).
 *
 * The session bus is a `MutableSharedFlow(replay = 0, onBufferOverflow = DROP_OLDEST)`,
 * so an event emitted while no collector is subscribed — i.e. BEFORE the first socket
 * opens, and during EVERY reconnect-backoff window — has no live receiver and is
 * silently dropped. This bridge fixes that with a SINGLE source-side collector that
 * subscribes to `session.bus.stream()` exactly ONCE per client lifetime ([feed]) and
 * writes into a bounded [Channel] that OUTLIVES individual sockets. Whichever socket
 * is currently active [drain]s that channel; events emitted while disconnected are
 * retained and delivered on the next successful connect. Delivery is AT-MOST-ONCE:
 * all events still BUFFERED while disconnected survive, but an event already dequeued
 * for an in-flight `send` when the socket drops abruptly is NOT re-enqueued and may be
 * lost — at most one frame per drop (see [drain]). This is an accepted trade-off for a
 * dev visualization tool; the structurally-unbounded startup/backoff-window loss that
 * motivated CR-01 is what this bridge eliminates.
 *
 * Startup race: because `feed` launches its collector on a passed-in scope, the
 * collector has not necessarily registered with the `replay = 0` SharedFlow by the
 * time `launch` returns. [feed] therefore SUSPENDS until the bus reports the FEED's own
 * collector registered (its `onSubscribed` callback, which the shared flow runs only once
 * the collector's slot exists) before returning, so the caller can drive the source only
 * after the feed is provably subscribed — closing the startup race deterministically,
 * with no sleep. (A subscriber count would not say whose subscription it is: a
 * [VizSession] eagerly subscribes its own ProjectionService collector at construction.)
 *
 * Overflow decision (T-07-08): the channel is a plain bounded buffer with NO
 * `onBufferOverflow` policy, so [Channel.trySend] returns a FAILURE when the buffer
 * is full. We deliberately trade the EventBus's silent `DROP_OLDEST` for a SURFACED,
 * counted drop-newest-when-full: the overflow path increments [dropped] (an
 * [AtomicLong]) and reports it at warn, rate-limited (see [offer]), so any backpressure
 * loss is OBSERVABLE, never silent, and never floods the host app's log. [capacity] defaults to 10,000 to mirror the EventBus buffer so the bridge
 * is not the narrower bottleneck.
 *
 * Watermarks, losses and the cutoff (spec 2026-10-21, C-7). [poll] puts a completed poll's [PollWatermark] on the
 * session bus right after that poll's events, so bus order carries it behind them; the feed forwards at most one per
 * second, stamped with [lost] (exact: watermarks and the cutoff marker are numbered, so evicted control items are told
 * apart from evicted events) and [deliveryUncertain]. [cutoff] ends the capture at an exact bus index: the feed then
 * queues a `final` watermark carrying the newest completed poll (none without one: a watermark is an observation) and
 * closes the buffer, so the drain sends it last.
 *
 * Ktor-free: this class depends only on kotlinx-coroutines ([Channel]/[Flow]) and
 * core's [VizSession]; the wire/socket concerns live in [IngestTransport].
 */
class OutboundBuffer internal constructor(
    private val capacity: Int,
    private val reportIntervalMillis: Long,
    private val clock: () -> Long,
    private val watermarkIntervalNanos: Long = DEFAULT_WATERMARK_INTERVAL_NANOS,
) {
    // @JvmOverloads keeps both JVM constructors the 0.3.0 primary constructor had: () and (int).
    @JvmOverloads
    constructor(capacity: Int = DEFAULT_CAPACITY) : this(capacity, DEFAULT_REPORT_INTERVAL_MS, ::monotonicMillis)

    private val logger = LoggerFactory.getLogger(OutboundBuffer::class.java)

    private val channel = Channel<VizEvent>(capacity = capacity)
    private val droppedCount = AtomicLong(0)
    private val offeredCount = AtomicLong(0)
    private val dequeuedCount = AtomicLong(0)
    private val sentCount = AtomicLong(0)

    // Drop reporting (#255). [offer] runs on the single feed collector and [drain] on the transport,
    // so the state is guarded by [reportLock]; [overflowing] is volatile so a drain outside an
    // overflow, the normal case, takes no lock.
    private val reportLock = Any()

    @Volatile
    private var overflowing = false
    private var dropsSinceReport = 0L
    private var lastReportAt = 0L

    // Control items (poll watermarks and the cutoff marker) are numbered under controlLock as they go on the session
    // bus, so the feed can tell how many of the entries the bus evicted for it were control items, not events.
    private val controlLock = Any()
    private var controlSeq = 0L // guarded by controlLock
    private var subscribed = false // guarded by controlLock
    private var cutoffRequested = false // guarded by controlLock
    private var cutoffIndex = -1L // guarded by controlLock: the marker's bus index
    private var cutoffSeq = 0L // guarded by controlLock: the marker's number

    @Volatile
    private var cutoffMarker: PollWatermark? = null

    // Written by the feed; read when stamping, and by tests.
    private val missedEntries = AtomicLong(0)
    private val missedControl = AtomicLong(0)

    @Volatile
    private var uncertain = false

    // Feed-only state: one collector reads and writes it.
    private var lastIndex = 0L
    private var lastControlSeq = 0L
    private var latestPoll: PollWatermark? = null
    private var lastForwardedNanos: Long? = null

    /**
     * Events lost before sending, cumulative for the client (spec C-7): dropped because the buffer was full, or evicted
     * from the session bus before the feed read them. Skipped or evicted watermarks are not events and do not count.
     * Exact whenever the feed has just received a control item, which is when it stamps a watermark.
     */
    internal val lost: Long get() = droppedCount.get() + missedEntries.get() - missedControl.get()

    /**
     * A socket ended, other than by the buffer closing at the end of a drain, after taking an event that may not have
     * arrived (there is no delivery acknowledgement). Sticky.
     */
    internal val deliveryUncertain: Boolean get() = uncertain

    /** Number of events dropped on overflow — surfaced so loss is never silent. */
    val dropped: Long get() = droppedCount.get()

    /** Number of events the feed has put into the buffer; tests wait on it. */
    internal val offered: Long get() = offeredCount.get()

    /** Number of events [drain] has handed off (its send returned); tests read it. */
    internal val sent: Long get() = sentCount.get()

    /**
     * Launch the SINGLE lifetime feed: one long-lived collector on [scope] that
     * subscribes to the session bus and offers every event into the buffer.
     * SUSPENDS until the FEED's own collector is registered (the bus's `onSubscribed`)
     * before returning the collector [Job], so the caller may drive the source only
     * afterwards (no startup race). The collection ends at the [cutoff].
     *
     * MUST be invoked exactly ONCE per client lifetime, NOT per socket — the single
     * subscription survives every reconnect.
     */
    suspend fun feed(
        session: VizSession,
        scope: CoroutineScope,
    ): Job {
        // Signalled by the bus once the feed's own slot exists: every entry sent from then on reaches it (or is evicted
        // for it, which it sees).
        val subscription = CompletableDeferred<Unit>()
        val job =
            scope.launch {
                var reachedCutoff = false
                session.bus
                    .indexedStream { start ->
                        lastIndex = start
                        synchronized(controlLock) { subscribed = true }
                        subscription.complete(Unit)
                    }.takeWhile { entry -> accept(entry).also { if (!it) reachedCutoff = true } }
                    .collect { offer(it.event) }
                // The bus never completes: only the cutoff ends the collection.
                if (reachedCutoff) finish()
            }
        subscription.await()
        return job
    }

    /** Counts what the bus evicted before [entry]; `false` once the capture has reached the cutoff. */
    private fun accept(entry: EventBus.IndexedEvent): Boolean {
        if (entry.index - lastIndex > 1) {
            val (markerIndex, markerSeq) = synchronized(controlLock) { cutoffIndex to cutoffSeq }
            if (markerIndex in (lastIndex + 1) until entry.index) {
                // The marker itself was evicted: count what was lost before it, nothing after it.
                missedEntries.addAndGet(markerIndex - lastIndex - 1)
                countControl(markerSeq)
                return false
            }
            missedEntries.addAndGet(entry.index - lastIndex - 1)
        }
        lastIndex = entry.index
        val event = entry.event
        if (event is PollWatermark) countControl(event.seq)
        return event !== cutoffMarker
    }

    // Control item [seq] arrived (or the marker was evicted): the numbers skipped since the last one were evicted.
    private fun countControl(seq: Long) {
        if (seq <= lastControlSeq) return
        missedControl.addAndGet(seq - lastControlSeq - 1)
        lastControlSeq = seq
    }

    /**
     * Puts a completed poll's watermark on [session]'s bus (spec C-7), numbered as a control item. Called on the poll
     * loop right after the poll's events, so the watermark follows them. Nothing after the cutoff.
     */
    internal fun poll(
        session: VizSession,
        pollNanos: Long,
        pollEpochMs: Long,
    ) {
        synchronized(controlLock) {
            if (cutoffRequested) return
            session.bus.send(PollWatermark(session.sessionId, ++controlSeq, pollNanos, pollEpochMs))
        }
    }

    /**
     * Sets the capture cutoff (spec C-7): what [session] sent on its bus before this call is handed to the buffer,
     * nothing after it is, and none of that counts as lost. The feed then queues the final watermark and closes the
     * buffer, so a drain sends it last and returns. Before the feed subscribed nothing was captured: the buffer closes
     * now.
     */
    internal fun cutoff(session: VizSession) {
        synchronized(controlLock) {
            if (cutoffRequested) return
            cutoffRequested = true
            if (!subscribed) {
                close()
                return
            }
            val marker = PollWatermark(session.sessionId, ++controlSeq, Long.MIN_VALUE, 0)
            cutoffMarker = marker
            cutoffSeq = marker.seq
            cutoffIndex = session.bus.sendIndexed(marker)
            // Rejected by the bus (not expected under DROP_OLDEST): nothing can mark the end.
            if (cutoffIndex == 0L) close()
        }
    }

    /**
     * Drain the buffer into [send], one event at a time, until the channel is closed
     * ([close]) OR the calling coroutine is cancelled (a socket drop cancels the
     * drain). On cancellation the channel and its retained events SURVIVE — the NEXT
     * drain resumes from where this one left off.
     *
     * AT-MOST-ONCE caveat: `for (event in channel)` removes an event via the channel
     * iterator BEFORE [send] runs, so the single event already dequeued for the
     * in-flight [send] when cancellation hits is NOT re-enqueued — that one frame may
     * be lost per abrupt drop. All events still in the channel are retained.
     *
     * Cooperative cancellation propagates
     * (the underlying channel iteration rethrows [kotlinx.coroutines.CancellationException]).
     */
    suspend fun drain(send: suspend (VizEvent) -> Unit) {
        var tookEvent = false
        var closed = false
        try {
            for (event in channel) {
                // Watermarks are transport state: not events for the counters below.
                if (event is PollWatermark) {
                    send(event)
                    continue
                }
                tookEvent = true
                dequeuedCount.incrementAndGet()
                // Drops that stopped with the app going quiet are still reported once the buffer drains.
                if (overflowing) reportRecoveryIfDrained()
                send(event)
                sentCount.incrementAndGet()
            }
            closed = true
        } finally {
            // There is no delivery acknowledgement: an event this socket took may not have arrived (spec C-7).
            if (!closed && tookEvent) uncertain = true
        }
    }

    /**
     * Offer an event to the buffer; a drop on overflow is counted and reported, never silent.
     *
     * The report is rate-limited (#255): during a backend outage every new event is dropped, and a
     * line per drop buried the host app's own log. One WARN when the buffer fills, then at most one
     * summary per [reportIntervalMillis] while drops continue, and one INFO once the buffer has
     * drained to half. Half, not merely "an event got in": a slow backend keeps the buffer at the
     * edge, and each single free slot would otherwise start a new outage with a new warning.
     */
    private fun offer(event: VizEvent) {
        if (event is PollWatermark) return offerWatermark(event)
        val result = channel.trySend(event)
        when {
            result.isSuccess -> {
                offeredCount.incrementAndGet()
                if (overflowing) reportRecoveryIfDrained()
            }
            // Channel closed → the client is stopping; not a backpressure loss.
            result.isClosed -> Unit
            // Buffer full → surfaced, counted drop-newest-when-full.
            else -> reportDrop(droppedCount.incrementAndGet())
        }
    }

    /**
     * Keeps the newest poll for the close message and forwards at most one watermark per interval (by app clock, the
     * first at once), stamped with the counts as of now. One that does not fit a full buffer is skipped: it is not an
     * event, and the next one carries the counts.
     */
    private fun offerWatermark(poll: PollWatermark) {
        latestPoll = poll
        val last = lastForwardedNanos
        if (last != null && poll.tsNanos - last < watermarkIntervalNanos) return
        if (channel.trySend(stamped(poll, final = false)).isSuccess) lastForwardedNanos = poll.tsNanos
    }

    // seq = 0: the numbering is the bus's business; the server does not read a watermark's seq.
    private fun stamped(
        poll: PollWatermark,
        final: Boolean,
    ): PollWatermark = poll.copy(seq = 0, lostEvents = lost, deliveryUncertain = uncertain, final = final)

    /**
     * The close message: the newest completed poll before the cutoff with the final counts. Without a completed poll
     * there is none (a watermark is an observation, spec H-1): the buffer just closes.
     */
    private suspend fun finish() {
        val poll = latestPoll
        try {
            if (poll != null) channel.send(stamped(poll, final = true))
        } catch (_: ClosedSendChannelException) {
            // close() came first: nobody is waiting for the final watermark.
        }
        close()
    }

    private fun reportRecoveryIfDrained() {
        synchronized(reportLock) {
            if (!overflowing || offeredCount.get() - dequeuedCount.get() > capacity / 2) return
            overflowing = false
            logger.info(
                "OutboundBuffer accepts events again; {} dropped since the last report, {} in total",
                dropsSinceReport,
                dropped,
            )
            dropsSinceReport = 0
        }
    }

    private fun reportDrop(total: Long) =
        synchronized(reportLock) {
            reportDropLocked(total)
            // The transport may have drained the buffer between the failed send and the overflow
            // being marked; it saw no overflow then, so the recovery is checked here as well. Each
            // side writes its own counter or flag before reading the other's, so one of them sees it.
            reportRecoveryIfDrained()
        }

    private fun reportDropLocked(total: Long) {
        val now = clock()
        if (!overflowing) {
            overflowing = true
            lastReportAt = now
            dropsSinceReport = 0
            logger.warn(
                "OutboundBuffer full (capacity={}); new events are dropped until the backend takes them " +
                    "again, reported at most every {} s; {} in total",
                capacity,
                reportIntervalMillis / 1_000,
                total,
            )
            return
        }
        dropsSinceReport++
        if (now - lastReportAt >= reportIntervalMillis) {
            logger.warn(
                "OutboundBuffer still full; dropped {} events in the last {} s, {} in total",
                dropsSinceReport,
                (now - lastReportAt) / 1_000,
                total,
            )
            lastReportAt = now
            dropsSinceReport = 0
        }
    }

    /**
     * Close the buffer for send so any active [drain] finishes cleanly once the
     * already-buffered events are consumed. Idempotent: a second [close] is a no-op.
     */
    fun close() {
        try {
            channel.close()
        } catch (_: ClosedSendChannelException) {
            // Already closed — close() is idempotent.
        }
    }

    private companion object {
        /** Mirrors the EventBus 10k buffer so the bridge is not the narrower bottleneck. */
        const val DEFAULT_CAPACITY = 10_000

        /** At most one drop report per 30 s, so an outage never floods the host app's log (#255). */
        const val DEFAULT_REPORT_INTERVAL_MS = 30_000L

        /** At most one watermark a second (spec C-7). */
        const val DEFAULT_WATERMARK_INTERVAL_NANOS = 1_000_000_000L

        fun monotonicMillis(): Long = System.nanoTime() / 1_000_000
    }
}
