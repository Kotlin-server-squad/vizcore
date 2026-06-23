package com.jh.proj.coroutineviz.events.channel

import com.jh.proj.coroutineviz.events.VizEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Emitted when a send operation suspends because the channel buffer is full.
 *
 * @property channelId ID of the Channel
 * @property coroutineId ID of the suspended sending coroutine
 * @property bufferSize Current number of elements in the buffer
 * @property capacity Maximum capacity of the channel buffer
 */
@Serializable
@SerialName("ChannelSendSuspended")
data class ChannelSendSuspended(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val channelId: String,
    val coroutineId: String,
    val bufferSize: Int,
    val capacity: Int,
) : VizEvent {
    override val kind: String get() = "ChannelSendSuspended"
}
