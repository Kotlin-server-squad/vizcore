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
 * Order: the map has none (it is a hash map), so [nodes] sorts by start order: the seq of the event that first
 * created the coroutine, then its id. Every view of a session shows siblings in that order, and it never changes
 * while the session runs.
 *
 * @property coroutines Map of coroutine ID to [CoroutineNode] state
 */
class RuntimeSnapshot {
    val coroutines: ConcurrentMap<String, CoroutineNode> = ConcurrentHashMap()
    private val jobToCoroutineId = ConcurrentHashMap<Job, String>()
    private val jobByCoroutineId = ConcurrentHashMap<String, Job>()

    /** The seq of the event that first created each coroutine: its place in [nodes]. */
    private val createdSeqById = ConcurrentHashMap<String, Long>()

    /** Number of coroutines currently tracked; safe to call while events are being applied. */
    val coroutineCount: Int get() = coroutines.size

    /**
     * Point-in-time copy of every tracked coroutine node, safe to iterate and serialize while
     * the event pipeline keeps applying events. Each node is copied so later mutation of the
     * returned objects cannot leak back into the live snapshot.
     *
     * In start order: by the seq of the coroutine's first creation event, then by id. A node put
     * into [coroutines] without [created] has no seq and comes after the others, by id.
     */
    fun nodes(): List<CoroutineNode> =
        coroutines.values
            .map { (createdSeqById[it.id] ?: Long.MAX_VALUE) to it.copy() }
            .sortedWith(compareBy({ it.first }, { it.second.id }))
            .map { it.second }

    /**
     * Track a coroutine created by the event with [seq]. Creating the same id again replaces the node and keeps
     * its first seq, so it keeps its place in [nodes].
     */
    internal fun created(
        node: CoroutineNode,
        seq: Long,
    ) {
        createdSeqById.putIfAbsent(node.id, seq)
        coroutines[node.id] = node
    }

    fun registerJob(
        job: Job,
        coroutineId: String,
    ) {
        jobToCoroutineId[job] = coroutineId
        jobByCoroutineId[coroutineId] = job
    }

    fun getCoroutineIdFromJob(job: Job): String? {
        return jobToCoroutineId[job]
    }

    /** The job registered for [coroutineId], or `null` (a coroutine not started by `vizLaunch` / `vizAsync`). */
    fun jobOf(coroutineId: String): Job? = jobByCoroutineId[coroutineId]
}
