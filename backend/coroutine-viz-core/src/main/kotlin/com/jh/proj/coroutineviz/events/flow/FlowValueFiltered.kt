package com.jh.proj.coroutineviz.events.flow

import com.jh.proj.coroutineviz.events.VizEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Emitted when a value is filtered (either passed or dropped) by a filter operator.
 */
@Serializable
@SerialName("FlowValueFiltered")
data class FlowValueFiltered(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val flowId: String,
    // "filter", "filterNot", "filterIsInstance", "distinctUntilChanged"
    val operatorName: String,
    val valuePreview: String,
    val valueType: String,
    // true = value passed filter, false = value dropped
    val passed: Boolean,
    val sequenceNumber: Int,
    val coroutineId: String? = null,
    val collectorId: String? = null,
) : VizEvent {
    override val kind = "FlowValueFiltered"
}
