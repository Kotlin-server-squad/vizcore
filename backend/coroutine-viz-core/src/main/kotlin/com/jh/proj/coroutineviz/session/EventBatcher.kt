package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.VizEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * Hybrid-frame batching for the SSE egress flow (PERF-02, D-04/D-05).
 *
 * Collapses an upstream [VizEvent] stream into batches of [List]<[VizEvent]> using a
 * **count-OR-time-window** flush trigger — whichever fires first (D-05):
 *
 * - **Count trigger:** the pending list is flushed the moment it reaches [count] elements.
 * - **Time trigger:** the pending list is flushed when [windowMs] elapses *since the first
 *   pending element of the current batch* — NOT a fixed wall ticker. A single-shot timer is
 *   (re)armed only when a batch opens (its first element arrives) and is cancelled on every
 *   flush, so an idle stream never produces an empty batch.
 *
 * ## Hybrid invisibility-until-load (D-04)
 * Below load — when at most one event arrives per window — the batcher emits **size-1**
 * batches. The SSE route (Plan 03) renders a size-1 batch as today's single-event frame, so
 * the wire stays byte-identical and old clients keep working. Only under load (multiple events
 * inside one window) does a multi-element batch — the array frame — appear.
 *
 * ## Lossless
 * Batching only *regroups*; it never drops. The total number of events emitted across all
 * batches equals the total received. An empty list is NEVER emitted.
 *
 * kotlinx-Flow has no built-in `chunked(count, timeout)` for the "OR whichever-first" semantic
 * and `MutableSharedFlow`/`Channel` overflow is predicate-free, so this is implemented as a
 * custom [channelFlow] collector that [select]s between the next upstream element and a
 * per-batch single-shot timer. The timer uses [delay], which honors the coroutine context's
 * virtual clock under [kotlinx.coroutines.test.runTest], so the window trigger is
 * deterministically testable. Pure-Kotlin, JVM-17, no new dependency, no Micrometer / io.ktor
 * (Pitfall P6) — and no `testImplementation`-only types leak into `main`.
 */
object EventBatcher {
    /** Default flush size — a burst this large flushes immediately on the count trigger (D-05). */
    const val BATCH_COUNT: Int = 50

    /** Default flush window in ms — a partial batch flushes after this long idle (D-05). */
    const val BATCH_WINDOW_MS: Long = 100L

    /**
     * Wraps [upstream] in a count-OR-time-window flush collector.
     *
     * @param upstream the source event flow (the SSE per-subscriber egress flow in Plan 03)
     * @param count flush as soon as the pending list reaches this size (count trigger)
     * @param windowMs flush after this many ms since the first pending element (time trigger)
     * @return a flow of non-empty [List]<[VizEvent]> batches; never emits an empty list
     */
    fun batched(
        upstream: Flow<VizEvent>,
        count: Int = BATCH_COUNT,
        windowMs: Long = BATCH_WINDOW_MS,
    ): Flow<List<VizEvent>> {
        require(count >= 1) { "count must be >= 1, was $count" }
        require(windowMs >= 1) { "windowMs must be >= 1, was $windowMs" }

        return channelFlow {
            // Forward the cold upstream into a rendezvous channel we can `select` over alongside
            // the per-batch timer. A child coroutine owns the collection; closing the channel
            // (normally or exceptionally) signals end-of-stream to the batching loop.
            val inbox = Channel<VizEvent>(Channel.RENDEZVOUS)
            launch {
                try {
                    upstream.collect { inbox.send(it) }
                    inbox.close()
                } catch (t: Throwable) {
                    inbox.close(t)
                }
            }

            // Single-shot timer for the OPEN batch. Fires `Unit` into `timer` `windowMs` after a
            // batch opens; cancelled and re-armed across flushes so the window is measured from
            // the FIRST pending element (D-05), and never fires for an empty batch.
            val timer = Channel<Unit>(Channel.CONFLATED)
            var timerJob: Job? = null

            val pending = ArrayList<VizEvent>(count)

            fun cancelTimer() {
                timerJob?.cancel()
                timerJob = null
            }

            try {
                while (true) {
                    val result: ChannelResult<VizEvent>? =
                        select {
                            inbox.onReceiveCatching { it }
                            // The window arm is live ONLY while a batch is open, so an empty
                            // batch can never time out into an empty flush.
                            if (pending.isNotEmpty()) {
                                timer.onReceive { null }
                            }
                        }

                    if (result == null) {
                        // Time trigger fired: flush the partial (guaranteed non-empty) batch.
                        cancelTimer()
                        send(ArrayList(pending))
                        pending.clear()
                        continue
                    }

                    val event = result.getOrNull()
                    if (event == null) {
                        // Upstream closed: flush any tail, propagate failure if close carried one.
                        cancelTimer()
                        if (pending.isNotEmpty()) {
                            send(ArrayList(pending))
                            pending.clear()
                        }
                        result.exceptionOrNull()?.let { throw it }
                        break
                    }

                    if (pending.isEmpty()) {
                        // First element of a new batch: clear any stale tick from a prior batch,
                        // then arm a fresh single-shot window timer.
                        timer.tryReceive()
                        timerJob =
                            launch {
                                delay(windowMs)
                                timer.trySend(Unit)
                            }
                    }
                    pending.add(event)
                    if (pending.size >= count) {
                        // Count trigger fired: flush the full batch and disarm the window.
                        cancelTimer()
                        send(ArrayList(pending))
                        pending.clear()
                    }
                }
            } finally {
                cancelTimer()
            }
        }
    }
}
