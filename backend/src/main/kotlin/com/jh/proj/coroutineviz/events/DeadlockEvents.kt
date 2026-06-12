package com.jh.proj.coroutineviz.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============================================================================
// Deadlock Detection Events
// ============================================================================

/**
 * Emitted when a deadlock is detected in the system
 */
@Serializable
@SerialName("DeadlockDetected")
data class DeadlockDetected(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val involvedCoroutines: List<String>,
    val involvedCoroutineLabels: List<String?>,
    val involvedMutexes: List<String>,
    val involvedMutexLabels: List<String?>,
    // coroutineId -> waitingForMutexId
    val waitGraph: Map<String, String>,
    // mutexId -> heldByCoroutineId
    val holdGraph: Map<String, String>,
    // Human-readable cycle description
    val cycleDescription: String,
) : VizEvent {
    override val kind: String get() = "DeadlockDetected"
}

/**
 * Emitted when a potential deadlock pattern is detected (warning, not actual deadlock)
 */
@Serializable
@SerialName("PotentialDeadlockWarning")
data class PotentialDeadlockWarning(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val coroutineId: String,
    val coroutineLabel: String?,
    val holdingMutex: String,
    val holdingMutexLabel: String?,
    val requestingMutex: String,
    val requestingMutexLabel: String?,
    val recommendation: String,
) : VizEvent {
    override val kind: String get() = "PotentialDeadlockWarning"
}

/**
 * Represents a node in the wait-for graph for deadlock detection
 */
data class WaitGraphNode(
    val coroutineId: String,
    val coroutineLabel: String?,
    val holdingMutexIds: Set<String>,
    val waitingForMutexId: String?,
)

/**
 * Result of deadlock analysis
 */
sealed class DeadlockAnalysisResult {
    data object NoDeadlock : DeadlockAnalysisResult()

    data class DeadlockFound(
        // Coroutine IDs in the cycle
        val cycle: List<String>,
        // Corresponding labels
        val cycleLabels: List<String?>,
        // Mutex IDs involved
        val involvedMutexes: List<String>,
        // Corresponding labels
        val mutexLabels: List<String?>,
    ) : DeadlockAnalysisResult()

    data class PotentialDeadlock(
        val coroutineId: String,
        val holdingMutex: String,
        val requestingMutex: String,
        val reason: String,
    ) : DeadlockAnalysisResult()
}
