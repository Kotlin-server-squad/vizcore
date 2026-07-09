package com.jh.proj.coroutineviz.models

import com.jh.proj.coroutineviz.events.SuspensionPoint
import kotlinx.serialization.Serializable

/**
 * Represents a coroutine in the hierarchy tree projection.
 *
 * HierarchyNode is used by [ProjectionService] to build parent-child relationship
 * trees for visualization. Unlike [CoroutineNode], this model is serializable
 * and includes computed fields like children lists and timing information.
 *
 * @property id Unique coroutine identifier
 * @property parentId ID of parent coroutine, null for root coroutines
 * @property children List of child coroutine IDs
 * @property name Display name (label or generated)
 * @property scopeId ID of the owning VizScope
 * @property state Current state as a string
 * @property createdAtNanos Timestamp when coroutine was created
 * @property completedAtNanos Timestamp when coroutine finished, if applicable
 * @property dispatcherId ID of the assigned dispatcher
 * @property dispatcherName Human-readable dispatcher name
 * @property currentThreadId ID of thread currently executing, if any
 * @property currentThreadName Name of thread currently executing, if any
 * @property jobId Associated Job identifier
 * @property exceptionType Type of exception if failed
 * @property exceptionMessage Exception message if failed
 * @property creationPoint Launch site (first user frame of the creation stack), durable
 * @property lastSuspensionPoint Last observed suspension frame, durable across eviction
 */
@Serializable
data class HierarchyNode(
    // coroutineId
    val id: String,
    // parentCoroutineId
    val parentId: String?,
    // child coroutine IDs
    val children: List<String> = emptyList(),
    // label or generated name
    val name: String,
    val scopeId: String,
    // "CREATED", "RUNNING", "SUSPENDED", "COMPLETED", "CANCELLED", "FAILED"
    val state: String,
    val createdAtNanos: Long,
    val completedAtNanos: Long? = null,
    val dispatcherId: String? = null,
    val dispatcherName: String? = null,
    val currentThreadId: Long? = null,
    val currentThreadName: String? = null,
    val jobId: String,
    // If failed
    val exceptionType: String? = null,
    val exceptionMessage: String? = null,
    val activeChildrenIds: List<String> = emptyList(),
    val activeChildrenCount: Int = 0,
    // Durable source refs. The EventStore is a 10k DROP_OLDEST ring; per-coroutine timelines
    // empty out for older coroutines (events: [] observed live at exactly 10000 events),
    // erasing "Suspended at"/"Launched at"/jump targets. The projection node is the durable
    // home so those survive eviction (UAT: enrichment-empty item 4, jump-to-source aggravator).
    val creationPoint: SuspensionPoint? = null,
    val lastSuspensionPoint: SuspensionPoint? = null,
)
