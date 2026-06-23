package com.jh.proj.coroutineviz.events.channel

import com.jh.proj.coroutineviz.events.VizEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Emitted when a coroutine begins receiving from a Channel.
 *
 * @property channelId ID of the Channel being received from
 * @property coroutineId ID of the receiving coroutine
 */
@Serializable
@SerialName("ChannelReceiveStarted")
data class ChannelReceiveStarted(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val channelId: String,
    val coroutineId: String,
) : VizEvent {
    override val kind: String get() = "ChannelReceiveStarted"
}
