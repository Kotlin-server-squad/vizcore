package com.jh.proj.coroutineviz.events.channel

import com.jh.proj.coroutineviz.events.VizEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Emitted when a receive operation from a Channel completes with a value.
 *
 * @property channelId ID of the Channel
 * @property coroutineId ID of the receiving coroutine
 * @property valueDescription Preview of the received value
 */
@Serializable
@SerialName("ChannelReceiveCompleted")
data class ChannelReceiveCompleted(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val channelId: String,
    val coroutineId: String,
    val valueDescription: String,
) : VizEvent {
    override val kind: String get() = "ChannelReceiveCompleted"
}
