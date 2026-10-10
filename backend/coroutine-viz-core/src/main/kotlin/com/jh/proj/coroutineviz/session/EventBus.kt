package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.VizEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import org.slf4j.LoggerFactory

/**
 * Real-time event distribution bus using Kotlin Flow.
 *
 * The EventBus provides pub/sub functionality for [VizEvent] instances,
 * allowing multiple subscribers to receive events as they occur. It uses
 * a [MutableSharedFlow] with a bounded buffer ([capacity] events, 10,000 by
 * default) to absorb bursts without blocking emitters.
 *
 * When a subscriber lags by more than [capacity] events, the OLDEST buffered
 * events are evicted for it (`DROP_OLDEST`) so a slow subscriber never blocks
 * emission. Evictions are not silent: every event is stamped with a bus-local,
 * gap-free index, and each subscriber of [stream] detects a jump in that index
 * and reports the number of events it missed through [onEvicted]. (Event `seq`
 * values cannot be used for this: the session re-stamps them and they may have
 * gaps.)
 *
 * Usage:
 * ```kotlin
 * // Subscribe to events
 * eventBus.stream().collect { event ->
 *     println("Received: ${event.kind}")
 * }
 *
 * // Emit events (non-blocking)
 * eventBus.send(event)
 * ```
 *
 * @param capacity number of events buffered per lagging subscriber before the
 *   oldest are evicted for it.
 */
class EventBus(
    capacity: Int = DEFAULT_CAPACITY,
) {
    private val logger = LoggerFactory.getLogger(EventBus::class.java)

    /** An event plus its bus-local index; indices are assigned in buffer order with no gaps. */
    private class Indexed(
        val idx: Long,
        val event: VizEvent,
    )

    private val shared =
        MutableSharedFlow<Indexed>(
            extraBufferCapacity = capacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Guards [lastIndex] so that index order equals buffer order. */
    private val emitLock = Any()

    /** Index of the most recently emitted event; 0 before the first send. */
    private var lastIndex = 0L

    /** Callback invoked on each successful emit. */
    var onEmit: (() -> Unit)? = null

    /**
     * Callback invoked when [send] rejects an event. Under `DROP_OLDEST` the
     * emitter is never rejected, so this path is defensive; real losses are
     * reported through [onEvicted].
     */
    var onDrop: (() -> Unit)? = null

    /**
     * Callback invoked with the number of events a [stream] subscriber missed
     * because they were evicted from its buffer while it lagged. The count is
     * per subscriber: if N subscribers lag behind the same burst, each reports
     * its own evictions.
     */
    var onEvicted: ((Long) -> Unit)? = null

    /**
     * Non-suspending event emission.
     * Returns true if event was emitted, false if it was rejected (not expected
     * under `DROP_OLDEST`).
     */
    fun send(event: VizEvent): Boolean {
        val emitted =
            synchronized(emitLock) {
                val accepted = shared.tryEmit(Indexed(lastIndex + 1, event))
                if (accepted) lastIndex++
                accepted
            }
        if (emitted) {
            onEmit?.invoke()
        } else {
            logger.error("Event buffer full! Dropped event: ${event.kind} (seq=${event.seq})")
            onDrop?.invoke()
        }
        return emitted
    }

    /**
     * Suspending version for compatibility. The bus never suspends emitters
     * (`DROP_OLDEST`), so this is equivalent to [send].
     */
    @Suppress("RedundantSuspendModifier")
    suspend fun sendSuspend(event: VizEvent) {
        send(event)
    }

    /**
     * Get a Flow to subscribe to events.
     *
     * Each collection is a new subscriber that receives every event sent after
     * it is registered. If the subscriber lags by more than the bus capacity,
     * the events evicted for it are counted and reported via [onEvicted].
     *
     * @param onSubscribed invoked once the subscriber is registered on the bus
     *   and before it receives any event. It runs inside
     *   [kotlinx.coroutines.flow.onSubscription], which the shared flow calls
     *   only after it has allocated the collector's slot. Every event sent after this call
     *   starts is delivered to the subscriber (or counted as evicted), so a
     *   caller can snapshot the store inside or after it without a gap.
     * @return Cold flow that emits all future events
     */
    fun stream(onSubscribed: (suspend () -> Unit)? = null): Flow<VizEvent> =
        flow {
            var seen = 0L
            shared
                .onSubscription {
                    seen = synchronized(emitLock) { lastIndex }
                    onSubscribed?.invoke()
                }.collect { indexed ->
                    val missed = indexed.idx - seen - 1
                    if (missed > 0) {
                        logger.warn("Slow bus subscriber missed {} evicted events", missed)
                        onEvicted?.invoke(missed)
                    }
                    if (indexed.idx > seen) seen = indexed.idx
                    emit(indexed.event)
                }
        }

    /**
     * The number of subscribers currently collecting [stream], surfacing the
     * underlying [MutableSharedFlow.subscriptionCount].
     *
     * This is an ADDITIVE, Ktor-free accessor (kotlinx-coroutines [StateFlow], no
     * new dependency). It lets a consumer deterministically await a live collector
     * before emitting — important precisely because this bus is `replay = 0`, so an
     * event sent before any collector has subscribed has no live receiver and would
     * be lost. By awaiting `subscriptionCount.first { it >= 1 }`, a consumer can
     * close that startup race without sleeping. The `send`/`stream`/buffer semantics
     * are unchanged.
     */
    val subscriptionCount: StateFlow<Int> get() = shared.subscriptionCount

    companion object {
        /** Default per-subscriber buffer capacity. */
        const val DEFAULT_CAPACITY = 10_000
    }
}
