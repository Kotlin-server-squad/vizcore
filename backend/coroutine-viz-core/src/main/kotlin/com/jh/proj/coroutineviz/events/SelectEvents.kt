package com.jh.proj.coroutineviz.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============================================================================
// Select Expression Events
// ============================================================================

/**
 * Emitted when a select expression begins evaluation.
 *
 * @property selectId Unique identifier for this select invocation
 * @property coroutineId ID of the coroutine executing the select
 * @property clauseCount Number of clauses registered in this select
 */
@Serializable
@SerialName("SelectStarted")
data class SelectStarted(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val selectId: String,
    val coroutineId: String,
    val clauseCount: Int,
) : VizEvent {
    override val kind: String get() = "SelectStarted"
}

/**
 * Emitted when a clause is registered in a select expression.
 *
 * @property selectId The select expression this clause belongs to
 * @property clauseIndex Index of the clause in the select block (0-based)
 * @property clauseType Type of clause: "onReceive", "onSend", "onAwait", "onTimeout", "onJoin"
 * @property channelId Channel ID for onReceive/onSend clauses
 * @property deferredId Deferred ID for onAwait clauses
 * @property timeoutMillis Timeout for onTimeout clauses
 */
@Serializable
@SerialName("SelectClauseRegistered")
data class SelectClauseRegistered(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val selectId: String,
    val coroutineId: String,
    val clauseIndex: Int,
    val clauseType: String,
    val channelId: String? = null,
    val deferredId: String? = null,
    val timeoutMillis: Long? = null,
    val label: String? = null,
) : VizEvent {
    override val kind: String get() = "SelectClauseRegistered"
}

/**
 * Emitted when a clause wins the select race.
 *
 * @property selectId The select expression
 * @property winnerClauseIndex Index of the winning clause
 * @property winnerClauseType Type of the winning clause
 * @property waitDurationNanos How long the select waited before a clause was ready
 */
@Serializable
@SerialName("SelectClauseWon")
data class SelectClauseWon(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val selectId: String,
    val coroutineId: String,
    val winnerClauseIndex: Int,
    val winnerClauseType: String,
    val waitDurationNanos: Long,
) : VizEvent {
    override val kind: String get() = "SelectClauseWon"
}

/**
 * Emitted when a select expression completes.
 *
 * @property selectId The select expression
 * @property totalDurationNanos Total time from SelectStarted to completion
 */
@Serializable
@SerialName("SelectCompleted")
data class SelectCompleted(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val selectId: String,
    val coroutineId: String,
    val totalDurationNanos: Long,
) : VizEvent {
    override val kind: String get() = "SelectCompleted"
}
