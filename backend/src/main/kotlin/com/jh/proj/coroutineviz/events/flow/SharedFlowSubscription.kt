package com.jh.proj.coroutineviz.events.flow

import com.jh.proj.coroutineviz.events.VizEvent
import kotlinx.serialization.Serializable

/**
 * Emitted when a collector subscribes to or unsubscribes from a SharedFlow.
 */
@Serializable
data class SharedFlowSubscription(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val flowId: String,
    val collectorId: String,
    // "subscribed" or "unsubscribed"
    val action: String,
    // Total subscribers after this action
    val subscriberCount: Int,
    val coroutineId: String? = null,
    val label: String? = null,
) : VizEvent {
    override val kind = "SharedFlowSubscription"
}
