package com.jh.proj.coroutineviz.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============================================================================
// Actor Pattern Events
// ============================================================================

/**
 * Emitted when an actor is created.
 *
 * @property actorId Unique identifier for this actor
 * @property coroutineId The coroutine backing this actor
 * @property name Human-readable name for the actor
 * @property mailboxCapacity Capacity of the actor's message channel
 */
@Serializable
@SerialName("ActorCreated")
data class ActorCreated(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val actorId: String,
    val coroutineId: String,
    val name: String?,
    val mailboxCapacity: Int,
) : VizEvent {
    override val kind: String get() = "ActorCreated"
}

/**
 * Emitted when a message is sent to an actor's mailbox.
 *
 * @property actorId Target actor
 * @property senderId Coroutine that sent the message
 * @property messageType Type/class of the message
 * @property messagePreview String preview of the message content
 * @property mailboxSize Current mailbox size after enqueueing
 */
@Serializable
@SerialName("ActorMessageSent")
data class ActorMessageSent(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val actorId: String,
    val senderId: String,
    val messageType: String,
    val messagePreview: String,
    val mailboxSize: Int,
) : VizEvent {
    override val kind: String get() = "ActorMessageSent"
}

/**
 * Emitted when an actor begins processing a message.
 *
 * @property actorId The actor processing the message
 * @property messageType Type of the message being processed
 * @property messagePreview Preview of the message content
 * @property queueWaitNanos Time the message spent in the mailbox
 */
@Serializable
@SerialName("ActorMessageProcessing")
data class ActorMessageProcessing(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val actorId: String,
    val messageType: String,
    val messagePreview: String,
    val queueWaitNanos: Long,
) : VizEvent {
    override val kind: String get() = "ActorMessageProcessing"
}

/**
 * Emitted when an actor finishes processing a message.
 *
 * @property actorId The actor that processed the message
 * @property messageType Type of the processed message
 * @property processingDurationNanos How long processing took
 * @property success Whether processing completed successfully
 */
@Serializable
@SerialName("ActorMessageProcessed")
data class ActorMessageProcessed(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val actorId: String,
    val messageType: String,
    val processingDurationNanos: Long,
    val success: Boolean,
) : VizEvent {
    override val kind: String get() = "ActorMessageProcessed"
}

/**
 * Emitted when an actor's internal state changes.
 *
 * @property actorId The actor whose state changed
 * @property oldStatePreview Preview of the previous state
 * @property newStatePreview Preview of the new state
 * @property stateType Type/class of the state object
 */
@Serializable
@SerialName("ActorStateChanged")
data class ActorStateChanged(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val actorId: String,
    val oldStatePreview: String,
    val newStatePreview: String,
    val stateType: String,
) : VizEvent {
    override val kind: String get() = "ActorStateChanged"
}

/**
 * Emitted when the actor's mailbox size changes significantly.
 *
 * @property actorId The actor
 * @property currentSize Current number of messages in the mailbox
 * @property capacity Maximum capacity
 * @property pendingSenders Number of senders currently suspended waiting to send
 */
@Serializable
@SerialName("ActorMailboxChanged")
data class ActorMailboxChanged(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val actorId: String,
    val currentSize: Int,
    val capacity: Int,
    val pendingSenders: Int,
) : VizEvent {
    override val kind: String get() = "ActorMailboxChanged"
}

/**
 * Emitted when an actor is closed/stopped.
 *
 * @property actorId The closed actor
 * @property reason Why the actor was closed
 * @property totalMessagesProcessed Total messages processed during lifetime
 */
@Serializable
@SerialName("ActorClosed")
data class ActorClosed(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val actorId: String,
    val reason: String?,
    val totalMessagesProcessed: Long,
) : VizEvent {
    override val kind: String get() = "ActorClosed"
}
