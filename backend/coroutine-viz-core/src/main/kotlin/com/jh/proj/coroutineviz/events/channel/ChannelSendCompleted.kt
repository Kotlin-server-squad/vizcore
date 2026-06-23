package com.jh.proj.coroutineviz.events.channel

import com.jh.proj.coroutineviz.events.VizEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Emitted when a send operation to a Channel completes successfully.
 *
 * @property channelId ID of the Channel
 * @property coroutineId ID of the sending coroutine
 * @property valueDescription Preview of the value that was sent
 */
@Serializable
@SerialName("ChannelSendCompleted")
data class ChannelSendCompleted(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val channelId: String,
    val coroutineId: String,
    val valueDescription: String,
) : VizEvent {
    override val kind: String get() = "ChannelSendCompleted"
}
