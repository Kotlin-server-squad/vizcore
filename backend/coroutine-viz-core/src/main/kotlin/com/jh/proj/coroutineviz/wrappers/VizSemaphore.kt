package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.SemaphoreAcquireRequested
import com.jh.proj.coroutineviz.events.SemaphoreCreated
import com.jh.proj.coroutineviz.events.SemaphorePermitAcquired
import com.jh.proj.coroutineviz.events.SemaphorePermitReleased
import com.jh.proj.coroutineviz.events.SemaphoreStateChanged
import com.jh.proj.coroutineviz.events.SemaphoreTryAcquireFailed
import com.jh.proj.coroutineviz.events.WaitKind
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Instrumented Semaphore wrapper that emits visualization events.
 *
 * This wrapper provides full transparency into semaphore behavior:
 * - Permit acquisition and release timing
 * - Active holder tracking, with the number of permits each holds
 * - Wait queue management; a wait for a permit is recorded as a suspension (spec 2026-11-05, S-7)
 * - Utilization metrics
 *
 * A permit released by a coroutine that holds none (or from outside any coroutine) is reported with
 * `holderUnknown`: whose permit came back cannot be known, so none is named.
 *
 * Real-world use cases:
 * - Connection pool limiting (max N connections)
 * - Rate limiting (max N concurrent API calls)
 * - Resource throttling
 * - Batch processing control
 *
 * @param session The visualization session for emitting events
 * @param permits Number of permits (concurrent access limit)
 * @param label Human-readable label for the semaphore
 */
class VizSemaphore(
    private val session: VizSession,
    val permits: Int,
    val label: String? = null,
    // F10: number of permits already held at construction. Lets a counting semaphore start with
    // fewer-than-`permits` available (e.g. a producer-consumer "full slots" semaphore that starts
    // empty: permits = capacity, acquiredPermits = capacity → 0 available). kotlinx requires
    // permits >= 1, so `Semaphore(0)` is illegal — express "0 available" via acquiredPermits instead.
    acquiredPermits: Int = 0,
) {
    val semaphoreId: String = "semaphore-${session.nextSeq()}"
    private val delegate = Semaphore(permits, acquiredPermits)
    private val totalPermits = permits

    // The permits each coroutine acquired and has not released itself, oldest first; a coroutine can hold several.
    // Each holder's list is replaced, never mutated, through merge/computeIfPresent.
    private val holders = ConcurrentHashMap<String, List<HolderInfo>>()

    // Wait queue tracking
    private val waitQueue = ConcurrentLinkedQueue<WaiterInfo>()

    data class HolderInfo(
        val coroutineId: String,
        val label: String?,
        val acquireTime: Long,
    )

    data class WaiterInfo(
        val coroutineId: String,
        val label: String?,
        val requestTime: Long,
        val permitsRequested: Int,
    )

    init {
        session.send(
            SemaphoreCreated(
                sessionId = session.sessionId,
                seq = session.nextSeq(),
                tsNanos = System.nanoTime(),
                semaphoreId = semaphoreId,
                semaphoreLabel = label,
                totalPermits = totalPermits,
            ),
        )
    }

    /**
     * Get number of available permits
     */
    val availablePermits: Int get() = delegate.availablePermits

    /**
     * Number of coroutines holding at least one permit they acquired
     */
    fun getActiveHolderCount(): Int = holders.size

    /**
     * Get number of coroutines waiting for permits
     */
    fun getWaitQueueSize(): Int = waitQueue.size

    /**
     * Calculate current utilization (0.0 to 1.0)
     */
    fun getUtilization(): Double {
        return (totalPermits - availablePermits).toDouble() / totalPermits
    }

    /**
     * Acquire a permit. Tries first; only when no permit is free does the coroutine queue and wait, and that wait
     * is recorded as a suspension (spec 2026-11-05, S-7).
     */
    suspend fun acquire() {
        val coroutineElement = currentCoroutineContext()[VizCoroutineElement]
        val coroutineId = coroutineElement?.coroutineId ?: "unknown-${System.nanoTime()}"
        val coroutineLabel = coroutineElement?.label

        // Try first, so whether to wait is never decided from `availablePermits`, which another coroutine can change.
        val available = delegate.availablePermits
        val acquiredAtOnce = delegate.tryAcquire()

        session.send(
            SemaphoreAcquireRequested(
                sessionId = session.sessionId,
                seq = session.nextSeq(),
                tsNanos = System.nanoTime(),
                semaphoreId = semaphoreId,
                semaphoreLabel = label,
                requesterId = coroutineId,
                requesterLabel = coroutineLabel,
                availablePermits = if (acquiredAtOnce) maxOf(available, 1) else 0,
                permitsRequested = 1,
            ),
        )

        val startWait = System.nanoTime()
        if (!acquiredAtOnce) waitInQueue(WaiterInfo(coroutineId, coroutineLabel, startWait, 1))
        val waitDuration = System.nanoTime() - startWait

        addHolder(HolderInfo(coroutineId, coroutineLabel, System.nanoTime()))

        session.send(
            SemaphorePermitAcquired(
                sessionId = session.sessionId,
                seq = session.nextSeq(),
                tsNanos = System.nanoTime(),
                semaphoreId = semaphoreId,
                semaphoreLabel = label,
                acquirerId = coroutineId,
                acquirerLabel = coroutineLabel,
                remainingPermits = delegate.availablePermits,
                waitDurationNanos = waitDuration,
            ),
        )

        emitStateChanged()
    }

    /** Queues [waiter] and waits for a permit, recorded as a suspension; the waiter leaves the queue however the wait ends. */
    private suspend fun waitInQueue(waiter: WaiterInfo) {
        waitQueue.add(waiter)
        emitStateChanged()
        var acquired = false
        try {
            session.recordWait(WaitKind.SEMAPHORE, semaphoreId) { delegate.acquire() }
            acquired = true
        } finally {
            // Without a permit (cancelled, or a timeout around the wait) the waiter no longer waits; left in the
            // queue it would show waiting for ever. With a permit, `acquire` reports the new state after Acquired.
            if (waitQueue.remove(waiter) && !acquired) emitStateChanged()
        }
    }

    /**
     * Release a permit back to the pool. It counts as the releasing coroutine's own (its oldest) when that
     * coroutine holds one; otherwise whose permit came back is unknown, and the event says so (`holderUnknown`)
     * instead of naming a holder.
     */
    fun release() {
        val coroutineElement = VizCoroutineElement.current()
        val released = coroutineElement?.let { takeOldestPermit(it.coroutineId) }

        delegate.release()
        forgetHoldersIfAllFree()

        session.send(
            SemaphorePermitReleased(
                sessionId = session.sessionId,
                seq = session.nextSeq(),
                tsNanos = System.nanoTime(),
                semaphoreId = semaphoreId,
                semaphoreLabel = label,
                releaserId = coroutineElement?.coroutineId ?: "unknown",
                releaserLabel = coroutineElement?.label,
                newAvailablePermits = delegate.availablePermits,
                holdDurationNanos = released?.let { System.nanoTime() - it.acquireTime } ?: 0L,
                holderUnknown = released == null,
            ),
        )

        emitStateChanged()
    }

    /**
     * Try to acquire a permit without suspending
     *
     * @return true if permit was acquired, false if none available
     */
    fun tryAcquire(): Boolean {
        // The holder must be the real coroutine, or its own release would count as another's (holder unknown).
        val coroutineElement = VizCoroutineElement.current()
        val coroutineId = coroutineElement?.coroutineId ?: "unknown-${System.nanoTime()}"
        val coroutineLabel = coroutineElement?.label

        val acquired = delegate.tryAcquire()

        if (acquired) {
            addHolder(HolderInfo(coroutineId, coroutineLabel, System.nanoTime()))

            session.send(
                SemaphorePermitAcquired(
                    sessionId = session.sessionId,
                    seq = session.nextSeq(),
                    tsNanos = System.nanoTime(),
                    semaphoreId = semaphoreId,
                    semaphoreLabel = label,
                    acquirerId = coroutineId,
                    acquirerLabel = coroutineLabel,
                    remainingPermits = delegate.availablePermits,
                    waitDurationNanos = 0,
                ),
            )

            emitStateChanged()
        } else {
            session.send(
                SemaphoreTryAcquireFailed(
                    sessionId = session.sessionId,
                    seq = session.nextSeq(),
                    tsNanos = System.nanoTime(),
                    semaphoreId = semaphoreId,
                    semaphoreLabel = label,
                    requesterId = coroutineId,
                    requesterLabel = coroutineLabel,
                    availablePermits = delegate.availablePermits,
                    permitsRequested = 1,
                ),
            )
        }

        return acquired
    }

    /**
     * Execute block while holding a permit
     */
    suspend fun <T> withPermit(action: suspend () -> T): T {
        acquire()
        try {
            return action()
        } finally {
            release()
        }
    }

    private fun addHolder(holder: HolderInfo) {
        holders.merge(holder.coroutineId, listOf(holder)) { held, added -> held + added }
    }

    /**
     * With every permit free nobody holds one: forgets the holders whose permits other coroutines released. Only
     * entries recorded before every permit was seen free are removed, each only if unchanged since, so a coroutine
     * that acquires meanwhile keeps its entry (its own release must still be attributed to it).
     */
    private fun forgetHoldersIfAllFree() {
        // HashMap(holders), not toMap(): toMap's single-entry fast path calls iterator().next() and races a
        // concurrent remove (see emitStateChanged).
        val recorded = HashMap(holders)
        if (recorded.isEmpty() || delegate.availablePermits != totalPermits) return
        recorded.forEach { (coroutineId, held) -> holders.remove(coroutineId, held) }
    }

    /** Removes and returns the oldest permit [coroutineId] holds, or `null` when it holds none. */
    private fun takeOldestPermit(coroutineId: String): HolderInfo? {
        var taken: HolderInfo? = null
        holders.computeIfPresent(coroutineId) { _, held ->
            taken = held.first()
            held.drop(1).ifEmpty { null }
        }
        return taken
    }

    private fun emitStateChanged() {
        // Atomic snapshots: Collection.toList() has a size==1 fast path that calls
        // iterator().next(), which races a concurrent remove() on these concurrent
        // collections and throws NoSuchElementException. toMutableList() copies via
        // ArrayList(this) → toArray(), which is a race-safe snapshot.
        val waiters = waitQueue.toMutableList()
        val held = holders.values.toMutableList()
        val available = delegate.availablePermits
        // Listed permits beyond those in use were released by other coroutines: their holder is unknown.
        val unknownHolderPermits = (held.sumOf { it.size } - (totalPermits - available)).coerceAtLeast(0)

        session.send(
            SemaphoreStateChanged(
                sessionId = session.sessionId,
                seq = session.nextSeq(),
                tsNanos = System.nanoTime(),
                semaphoreId = semaphoreId,
                semaphoreLabel = label,
                availablePermits = available,
                totalPermits = totalPermits,
                activeHolders = held.map { it.first().coroutineId },
                activeHolderLabels = held.map { it.first().label },
                waitingCoroutines = waiters.map { it.coroutineId },
                waitingLabels = waiters.map { it.label },
                activeHolderPermits = held.map { it.size },
                unknownHolderPermits = unknownHolderPermits,
            ),
        )
    }
}

// ============================================================================
// Extension function for creating VizSemaphore in VizScope
// ============================================================================

/**
 * Create a new instrumented Semaphore within this VizScope
 *
 * Example:
 * ```kotlin
 * val connectionPool = vizSemaphore("db-connections", permits = 3)
 * connectionPool.withPermit {
 *     // Use connection
 * }
 * ```
 */
fun VizScope.vizSemaphore(
    label: String? = null,
    permits: Int,
    acquiredPermits: Int = 0,
): VizSemaphore {
    return VizSemaphore(session, permits, label, acquiredPermits)
}
