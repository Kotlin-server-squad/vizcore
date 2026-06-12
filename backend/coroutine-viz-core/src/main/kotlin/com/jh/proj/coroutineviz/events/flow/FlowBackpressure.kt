package com.jh.proj.coroutineviz.events.flow

import com.jh.proj.coroutineviz.events.VizEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Emitted when backpressure is detected in a Flow.
 * This occurs when producer is faster than consumer.
 */
@Serializable
@SerialName("FlowBackpressure")
data class FlowBackpressure(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val flowId: String,
    val collectorId: String,
    // "slow_collector", "buffer_full", "conflated"
    val reason: String,
    val pendingEmissions: Int,
    val bufferCapacity: Int?,
    // How long producer waited
    val durationNanos: Long?,
    val coroutineId: String? = null,
) : VizEvent {
    override val kind = "FlowBackpressure"
}
