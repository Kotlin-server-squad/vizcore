package com.jh.proj.coroutineviz.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============================================================================
// Semaphore Event Types - For visualizing permit-based synchronization
// ============================================================================

/**
 * Base interface for all Semaphore-related events
 */
@Serializable
sealed interface SemaphoreEvent : VizEvent {
    val semaphoreId: String
    val semaphoreLabel: String?
}

/**
 * Emitted when a new Semaphore is created
 */
@Serializable
@SerialName("SemaphoreCreated")
data class SemaphoreCreated(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    override val semaphoreId: String,
    override val semaphoreLabel: String?,
    val totalPermits: Int,
) : SemaphoreEvent {
    override val kind: String get() = "SemaphoreCreated"
}

/**
 * Emitted when a coroutine requests to acquire a permit
 */
@Serializable
@SerialName("SemaphoreAcquireRequested")
data class SemaphoreAcquireRequested(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    override val semaphoreId: String,
    override val semaphoreLabel: String?,
    val requesterId: String,
    val requesterLabel: String?,
    val availablePermits: Int,
    // Usually 1, but can be more
    val permitsRequested: Int,
) : SemaphoreEvent {
    override val kind: String get() = "SemaphoreAcquireRequested"
}

/**
 * Emitted when a coroutine successfully acquires a permit
 */
@Serializable
@SerialName("SemaphorePermitAcquired")
data class SemaphorePermitAcquired(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    override val semaphoreId: String,
    override val semaphoreLabel: String?,
    val acquirerId: String,
    val acquirerLabel: String?,
    val remainingPermits: Int,
    val waitDurationNanos: Long,
) : SemaphoreEvent {
    override val kind: String get() = "SemaphorePermitAcquired"
}

/**
 * Emitted when a coroutine releases a permit
 */
@Serializable
@SerialName("SemaphorePermitReleased")
data class SemaphorePermitReleased(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    override val semaphoreId: String,
    override val semaphoreLabel: String?,
    val releaserId: String,
    val releaserLabel: String?,
    val newAvailablePermits: Int,
    val holdDurationNanos: Long,
    /**
     * True when [releaserId] held no permit of this semaphore, or no coroutine was known: a permit came back, but
     * whose is unknown, so no holder is named and [holdDurationNanos] is 0 (spec 2026-11-05, S-7). False on older
     * events, which named the releaser as the holder.
     */
    val holderUnknown: Boolean = false,
) : SemaphoreEvent {
    /** The 0.3.x JVM constructor, kept for binary compatibility. Hidden from source resolution. */
    @Deprecated("binary compatibility with 0.3.x", level = DeprecationLevel.HIDDEN)
    constructor(
        sessionId: String,
        seq: Long,
        tsNanos: Long,
        semaphoreId: String,
        semaphoreLabel: String?,
        releaserId: String,
        releaserLabel: String?,
        newAvailablePermits: Int,
        holdDurationNanos: Long,
    ) : this(
        sessionId, seq, tsNanos, semaphoreId, semaphoreLabel, releaserId, releaserLabel, newAvailablePermits,
        holdDurationNanos, false,
    )

    override val kind: String get() = "SemaphorePermitReleased"
}

/**
 * Emitted when a coroutine attempts tryAcquire and fails
 */
@Serializable
@SerialName("SemaphoreTryAcquireFailed")
data class SemaphoreTryAcquireFailed(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    override val semaphoreId: String,
    override val semaphoreLabel: String?,
    val requesterId: String,
    val requesterLabel: String?,
    val availablePermits: Int,
    val permitsRequested: Int,
) : SemaphoreEvent {
    override val kind: String get() = "SemaphoreTryAcquireFailed"
}

/**
 * Emitted when the overall semaphore state changes
 */
@Serializable
@SerialName("SemaphoreStateChanged")
data class SemaphoreStateChanged(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    override val semaphoreId: String,
    override val semaphoreLabel: String?,
    val availablePermits: Int,
    val totalPermits: Int,
    /** Coroutines holding permits they acquired, each listed once. */
    val activeHolders: List<String>,
    val activeHolderLabels: List<String?>,
    val waitingCoroutines: List<String>,
    val waitingLabels: List<String?>,
    /** How many permits each of [activeHolders] acquired and has not released itself; empty on older events. */
    val activeHolderPermits: List<Int> = emptyList(),
    /**
     * Of the permits counted in [activeHolderPermits], how many are no longer in use: another coroutine released
     * them, so which holder lost one is unknown (spec 2026-11-05, S-6/S-7).
     */
    val unknownHolderPermits: Int = 0,
) : SemaphoreEvent {
    /** The 0.3.x JVM constructor, kept for binary compatibility. Hidden from source resolution. */
    @Deprecated("binary compatibility with 0.3.x", level = DeprecationLevel.HIDDEN)
    constructor(
        sessionId: String,
        seq: Long,
        tsNanos: Long,
        semaphoreId: String,
        semaphoreLabel: String?,
        availablePermits: Int,
        totalPermits: Int,
        activeHolders: List<String>,
        activeHolderLabels: List<String?>,
        waitingCoroutines: List<String>,
        waitingLabels: List<String?>,
    ) : this(
        sessionId, seq, tsNanos, semaphoreId, semaphoreLabel, availablePermits, totalPermits, activeHolders,
        activeHolderLabels, waitingCoroutines, waitingLabels, emptyList(), 0,
    )

    override val kind: String get() = "SemaphoreStateChanged"
}
