package com.jh.proj.coroutineviz.models

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * Current state snapshot of all coroutines in a session.
 *
 * RuntimeSnapshot provides the current coroutine states, updated incrementally by
 * [com.jh.proj.coroutineviz.session.EventApplier] as events are processed. This is
 * the "current state" in an event-sourcing architecture.
 *
 * Concurrency contract: the event pipeline is the single writer (it applies events under
 * the session's send lock), while HTTP readers run concurrently without taking that lock.
 * The map is therefore a [ConcurrentHashMap], whose iteration is weakly consistent and never
 * throws [ConcurrentModificationException]. A published [CoroutineNode] is never mutated in
 * place: every state transition replaces the map entry with a copy, so a reader holding a node
 * always sees a consistent value. Readers that need a stable list should use [nodes].
 *
 * @property coroutines Map of coroutine ID to [CoroutineNode] state
 */
class RuntimeSnapshot {
    val coroutines: ConcurrentMap<String, CoroutineNode> = ConcurrentHashMap()
    private val jobToCoroutineId = ConcurrentHashMap<Job, String>()

    /** Number of coroutines currently tracked; safe to call while events are being applied. */
    val coroutineCount: Int get() = coroutines.size

    /**
     * Point-in-time copy of every tracked coroutine node, safe to iterate and serialize while
     * the event pipeline keeps applying events. Each node is copied so later mutation of the
     * returned objects cannot leak back into the live snapshot.
     */
    fun nodes(): List<CoroutineNode> = coroutines.values.map { it.copy() }

    fun registerJob(
        job: Job,
        coroutineId: String,
    ) {
        jobToCoroutineId[job] = coroutineId
    }

    fun getCoroutineIdFromJob(job: Job): String? {
        return jobToCoroutineId[job]
    }
}
