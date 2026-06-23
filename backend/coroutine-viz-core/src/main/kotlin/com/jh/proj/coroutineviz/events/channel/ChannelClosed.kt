package com.jh.proj.coroutineviz.events.channel

import com.jh.proj.coroutineviz.events.VizEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Emitted when a Channel is closed.
 *
 * @property channelId ID of the closed Channel
 * @property cause Description of the close cause, if any
 */
@Serializable
@SerialName("ChannelClosed")
data class ChannelClosed(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val channelId: String,
    val cause: String? = null,
) : VizEvent {
    override val kind: String get() = "ChannelClosed"
}
