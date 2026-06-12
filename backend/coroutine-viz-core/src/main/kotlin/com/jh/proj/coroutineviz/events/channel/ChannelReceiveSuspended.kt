package com.jh.proj.coroutineviz.events.channel

import com.jh.proj.coroutineviz.events.VizEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Emitted when a receive operation suspends because the channel is empty.
 *
 * @property channelId ID of the Channel
 * @property coroutineId ID of the suspended receiving coroutine
 */
@Serializable
@SerialName("ChannelReceiveSuspended")
data class ChannelReceiveSuspended(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val channelId: String,
    val coroutineId: String,
) : VizEvent {
    override val kind: String get() = "ChannelReceiveSuspended"
}
