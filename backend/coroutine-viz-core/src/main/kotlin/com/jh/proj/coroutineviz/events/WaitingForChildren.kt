package com.jh.proj.coroutineviz.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("WaitingForChildren")
data class WaitingForChildren(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    override val coroutineId: String,
    override val jobId: String,
    override val parentCoroutineId: String?,
    override val scopeId: String,
    override val label: String?,
    val activeChildrenCount: Int,
    // Coroutine IDs of active children
    val activeChildrenIds: List<String>,
) : CoroutineEvent {
    override val kind: String get() = "WaitingForChildren"
}
