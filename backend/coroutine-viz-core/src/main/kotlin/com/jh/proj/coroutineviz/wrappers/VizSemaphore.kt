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
     *
     * The release is reported before it is made: once the delegate has the permit back, a waiter can take it and
     * report its acquire on another thread straight away, and a release reported after that would show one more
     * holder than there are permits. The permit is given back even if reporting it fails.
     *
     * `newAvailablePermits` is one more than were free just before: the permit this release frees. A waiter that takes
     * it at once reports its acquire next, with one fewer. Releases at the same moment on several threads can report
     * the same number; the state snapshot each release sends afterwards reads the delegate.
     */
    fun release() {
        // A release the delegate would refuse fails as before, before anything is reported or untracked.
        val available = delegate.availablePermits
        check(available < totalPermits) { "The number of released permits cannot be greater than $totalPermits" }

        val coroutineElement = VizCoroutineElement.current()
        val released = coroutineElement?.let { takeOldestPermit(it.coroutineId) }

        try {
            session.send(
                SemaphorePermitReleased(
                    sessionId = session.sessionId,
                    seq = session.nextSeq(),
                    tsNanos = System.nanoTime(),
                    semaphoreId = semaphoreId,
                    semaphoreLabel = label,
                    releaserId = coroutineElement?.coroutineId ?: "unknown",
                    releaserLabel = coroutineElement?.label,
                    newAvailablePermits = available + 1,
                    holdDurationNanos = released?.let { System.nanoTime() - it.acquireTime } ?: 0L,
                    holderUnknown = released == null,
                ),
            )
        } finally {
            delegate.release()
        }
        trimHoldersToPermitsInUse()
        retireEndedHolders()

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
     * Nobody holds more permits than are in use: drops a holder's oldest records beyond that, and every record once
     * every permit is free. Without this, permits that other coroutines release (holder unknown) would pile up on
     * their acquirer for as long as the semaphore is never entirely free. Permits in use are read inside each
     * holder's update, so a permit a holder has just acquired and recorded is counted and keeps its record.
     */
    private fun trimHoldersToPermitsInUse() {
        for (coroutineId in holders.keys) {
            holders.computeIfPresent(coroutineId) { _, held ->
                val inUse = totalPermits - delegate.availablePermits
                if (held.size > inUse) held.takeLast(inUse).ifEmpty { null } else held
            }
        }
    }

    /**
     * While more permits are listed than are in use, forgets the records of coroutines that have ended (or whose job
     * is not known, e.g. an acquire outside any coroutine), oldest first: their permits were released by others.
     * Without this, every coroutine that acquired a permit someone else released would stay listed for as long as
     * the semaphore is never entirely free. A running coroutine's records are kept; the rest of the excess is
     * reported as `unknownHolderPermits`.
     */
    private fun retireEndedHolders() {
        var excess = holders.values.sumOf { it.size } - (totalPermits - delegate.availablePermits)
        if (excess <= 0) return
        val ended =
            holders.entries
                .filter { (coroutineId, _) -> session.snapshot.jobOf(coroutineId)?.isCompleted != false }
                .flatMap { (coroutineId, held) -> held.map { coroutineId to it } }
                .sortedBy { (_, holder) -> holder.acquireTime }
        for ((coroutineId, holder) in ended) {
            if (excess <= 0) break
            var removed = false
            holders.computeIfPresent(coroutineId) { _, held ->
                removed = holder in held
                (held - holder).ifEmpty { null }
            }
            if (removed) excess--
        }
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
