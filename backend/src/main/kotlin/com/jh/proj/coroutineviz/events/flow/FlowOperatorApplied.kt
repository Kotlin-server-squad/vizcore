package com.jh.proj.coroutineviz.events.flow

import com.jh.proj.coroutineviz.events.VizEvent
import kotlinx.serialization.Serializable

/**
 * Emitted when a Flow operator is applied to create a derived Flow.
 * Tracks the operator chain for visualization.
 */
@Serializable
data class FlowOperatorApplied(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val flowId: String,
    // The upstream flow this operator transforms
    val sourceFlowId: String,
    // "map", "filter", "transform", "flatMapConcat", etc.
    val operatorName: String,
    // Position in operator chain (0 = first operator)
    val operatorIndex: Int,
    val label: String? = null,
    // If created within a coroutine context
    val coroutineId: String? = null,
) : VizEvent {
    override val kind = "FlowOperatorApplied"
}
