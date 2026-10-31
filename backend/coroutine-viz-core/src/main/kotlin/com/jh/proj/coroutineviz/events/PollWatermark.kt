package com.jh.proj.coroutineviz.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The SDK's poll watermark (spec 2026-10-21, C-7): sent after a poll's events, at most once per
 * second, also when the poll produced none. [tsNanos] is the poll time on the app clock,
 * [pollEpochMs] the app's wall-clock time of that poll. [lostEvents] is the SDK's cumulative count of
 * events lost before sending; [deliveryUncertain] marks a connection that ended while events may have
 * been in flight. [final] marks the close message after `stop()` drained: no producer can add events
 * after it, so its [lostEvents] is final.
 */
@Serializable
@SerialName("PollWatermark")
data class PollWatermark(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val pollEpochMs: Long,
    val lostEvents: Long = 0,
    val deliveryUncertain: Boolean = false,
    val final: Boolean = false,
) : VizEvent {
    override val kind: String get() = "PollWatermark"
}
