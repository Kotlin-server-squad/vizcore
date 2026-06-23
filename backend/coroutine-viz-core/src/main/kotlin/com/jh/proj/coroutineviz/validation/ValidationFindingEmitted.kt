package com.jh.proj.coroutineviz.validation

import com.jh.proj.coroutineviz.events.VizEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Emitted when real-time validation detects an issue.
 * Streamed via SSE so the frontend can update the validation dashboard in real-time.
 */
@Serializable
@SerialName("ValidationFindingEmitted")
data class ValidationFindingEmitted(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val finding: RuleFinding,
) : VizEvent {
    override val kind: String get() = "ValidationFindingEmitted"
}
