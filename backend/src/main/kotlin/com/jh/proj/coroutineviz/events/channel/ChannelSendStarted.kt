package com.jh.proj.coroutineviz.events.channel

import com.jh.proj.coroutineviz.events.VizEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Emitted when a coroutine begins sending a value to a Channel.
 *
 * @property channelId ID of the Channel being sent to
 * @property coroutineId ID of the sending coroutine
 * @property valueDescription Preview of the value being sent
 */
@Serializable
@SerialName("ChannelSendStarted")
data class ChannelSendStarted(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val channelId: String,
    val coroutineId: String,
    val valueDescription: String,
) : VizEvent {
    override val kind: String get() = "ChannelSendStarted"
}
