package com.jh.proj.coroutineviz.events.channel

import com.jh.proj.coroutineviz.events.VizEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Emitted when the buffer state of a Channel changes (after send or receive).
 *
 * @property channelId ID of the Channel
 * @property currentSize Current number of elements in the buffer
 * @property capacity Maximum capacity of the channel buffer
 */
@Serializable
@SerialName("ChannelBufferStateChanged")
data class ChannelBufferStateChanged(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val channelId: String,
    val currentSize: Int,
    val capacity: Int,
) : VizEvent {
    override val kind: String get() = "ChannelBufferStateChanged"
}
