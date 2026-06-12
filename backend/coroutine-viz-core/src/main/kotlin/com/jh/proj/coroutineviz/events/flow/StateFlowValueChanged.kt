package com.jh.proj.coroutineviz.events.flow

import com.jh.proj.coroutineviz.events.VizEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Emitted when a StateFlow's value changes.
 */
@Serializable
@SerialName("StateFlowValueChanged")
data class StateFlowValueChanged(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val flowId: String,
    val oldValuePreview: String,
    val newValuePreview: String,
    val valueType: String,
    // Number of active collectors
    val subscriberCount: Int,
    val coroutineId: String? = null,
    val label: String? = null,
) : VizEvent {
    override val kind = "StateFlowValueChanged"
}
