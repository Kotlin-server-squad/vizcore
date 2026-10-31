package com.jh.proj.coroutineviz.client

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.first
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
 * time `launch` returns. [feed] therefore SUSPENDS until the bus
 * [VizSession.bus]'s `subscriptionCount` rises ABOVE the baseline captured just
 * before launch (i.e. the feed's OWN collector is provably registered) before
 * returning, so the caller can drive the source only after the feed is provably
 * subscribed — closing the startup race deterministically, with no sleep.
 *
 * Note the baseline is NOT assumed to be zero: a [VizSession] eagerly subscribes its
 * own ProjectionService collector at construction, so `subscriptionCount` is already
 * >= 1 before [feed]; awaiting a strict increase over the pre-launch baseline is what
 * guarantees the FEED's collector specifically is live.
 *
 * Overflow decision (T-07-08): the channel is a plain bounded buffer with NO
 * `onBufferOverflow` policy, so [Channel.trySend] returns a FAILURE when the buffer
 * is full. We deliberately trade the EventBus's silent `DROP_OLDEST` for a SURFACED,
 * counted drop-newest-when-full: the overflow path increments [dropped] (an
 * [AtomicLong]) and reports it at warn, rate-limited (see [offer]), so any backpressure
 * loss is OBSERVABLE, never silent, and never floods the host app's log. [capacity] defaults to 10,000 to mirror the EventBus buffer so the bridge
 * is not the narrower bottleneck.
 *
 * Ktor-free: this class depends only on kotlinx-coroutines ([Channel]/[Flow]) and
 * core's [VizSession]; the wire/socket concerns live in [IngestTransport].
 */
class OutboundBuffer internal constructor(
    private val capacity: Int,
    private val reportIntervalMillis: Long,
    private val clock: () -> Long,
) {
    constructor(capacity: Int = DEFAULT_CAPACITY) : this(capacity, DEFAULT_REPORT_INTERVAL_MS, ::monotonicMillis)

    private val logger = LoggerFactory.getLogger(OutboundBuffer::class.java)

    private val channel = Channel<VizEvent>(capacity = capacity)
    private val droppedCount = AtomicLong(0)
    private val offeredCount = AtomicLong(0)
    private val dequeuedCount = AtomicLong(0)
    private val sentCount = AtomicLong(0)

    // Drop reporting (#255). Only [offer] touches these, and it runs on the single feed collector.
    private var overflowing = false
    private var dropsSinceReport = 0L
    private var lastReportAt = 0L

    /** Number of events dropped on overflow — surfaced so loss is never silent. */
    val dropped: Long get() = droppedCount.get()

    /** Number of events the feed has put into the buffer; tests wait on it. */
    internal val offered: Long get() = offeredCount.get()

    /** Number of events [drain] has handed off (its send returned); tests read it. */
    internal val sent: Long get() = sentCount.get()

    /**
     * Launch the SINGLE lifetime feed: one long-lived collector on [scope] that
     * subscribes to `session.bus.stream()` and offers every event into the buffer.
     * SUSPENDS until the FEED's own collector is provably registered
     * (`session.bus.subscriptionCount` rises above the pre-launch baseline) before
     * returning the collector [Job], so the caller may drive the source only
     * afterwards (no startup race). Awaiting a strict increase over the baseline —
     * rather than a bare `>= 1` — is required because the session already has its own
     * ProjectionService subscriber, so the count is non-zero before this launch.
     *
     * MUST be invoked exactly ONCE per client lifetime, NOT per socket — the single
     * subscription survives every reconnect.
     */
    suspend fun feed(
        session: VizSession,
        scope: CoroutineScope,
    ): Job {
        val baseline = session.bus.subscriptionCount.value
        val job =
            scope.launch {
                session.bus.stream().collect { offer(it) }
            }
        // Await the feed's OWN collector registering with the replay=0 SharedFlow so
        // the caller can drive the source without racing the subscribe.
        session.bus.subscriptionCount.first { it > baseline }
        return job
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
        for (event in channel) {
            dequeuedCount.incrementAndGet()
            send(event)
            sentCount.incrementAndGet()
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
        val result = channel.trySend(event)
        when {
            result.isSuccess -> {
                offeredCount.incrementAndGet()
                if (overflowing && offeredCount.get() - dequeuedCount.get() <= capacity / 2) {
                    overflowing = false
                    logger.info(
                        "OutboundBuffer accepts events again; {} dropped since the last report, {} in total",
                        dropsSinceReport,
                        dropped,
                    )
                    dropsSinceReport = 0
                }
            }
            // Channel closed → the client is stopping; not a backpressure loss.
            result.isClosed -> Unit
            // Buffer full → surfaced, counted drop-newest-when-full.
            else -> reportDrop(droppedCount.incrementAndGet())
        }
    }

    private fun reportDrop(total: Long) {
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

        fun monotonicMillis(): Long = System.nanoTime() / 1_000_000
    }
}
