package com.jh.proj.coroutineviz.events

/**
 * Base interface for all visualization events.
 * All events in the system must implement this interface.
 *
 * Note: Not sealed to allow extension from subpackages.
 */
interface VizEvent {
    val sessionId: String

    /**
     * Monotonic per-session sequence number.
     *
     * Mutable because the authoritative value is assigned by `VizSession.send`
     * atomically with the store append: an event constructed with a provisional
     * seq (from `VizSession.nextSeq()`) keeps it when it is still in allocation
     * order, and is re-stamped under the session send lock when a concurrent
     * sender appended a higher seq first. This guarantees store order == seq
     * order == live-bus delivery order, which SSE replay deduplication relies on.
     * Do not mutate outside `VizSession.send`.
     */
    var seq: Long
    val tsNanos: Long
    val kind: String
}

/**
 * Base interface for coroutine lifecycle events.
 * Events that track a specific coroutine's state changes.
 *
 * Note: Not sealed to allow extension from subpackages.
 */
interface CoroutineEvent : VizEvent {
    val coroutineId: String
    val jobId: String
    val parentCoroutineId: String?
    val scopeId: String
    val label: String?
}
