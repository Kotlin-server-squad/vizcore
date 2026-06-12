package com.jh.proj.coroutineviz.events.flow

import com.jh.proj.coroutineviz.events.VizEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Emitted when a value is emitted to a SharedFlow.
 */
@Serializable
@SerialName("SharedFlowEmission")
data class SharedFlowEmission(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val flowId: String,
    val valuePreview: String,
    val valueType: String,
    val subscriberCount: Int,
    // Current replay cache size
    val replayCache: Int,
    val extraBufferCapacity: Int,
    val coroutineId: String? = null,
    val label: String? = null,
) : VizEvent {
    override val kind = "SharedFlowEmission"
}
