package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.WaitingForChildren
import com.jh.proj.coroutineviz.events.coroutine.CoroutineBodyCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.events.dispatcher.DispatcherSelected
import com.jh.proj.coroutineviz.events.dispatcher.ThreadAssigned
import com.jh.proj.coroutineviz.events.job.JobStateChanged
import com.jh.proj.coroutineviz.models.CoroutineTimeline
import com.jh.proj.coroutineviz.models.HierarchyNode
import com.jh.proj.coroutineviz.models.ThreadEvent
import com.jh.proj.coroutineviz.models.TimelineEventSummary
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Computes derived views (projections) from raw events.
 *
 * The ProjectionService subscribes to the [EventBus] and maintains
 * computed views that are useful for visualization, such as:
 * - Coroutine hierarchy trees (parent-child relationships)
 * - Thread activity timelines (which coroutines ran on which threads)
 * - Individual coroutine timelines with computed durations
 *
 * This implements the "read model" side of CQRS, where projections are
 * optimized for specific query patterns rather than being normalized.
 *
 * Usage:
 * ```kotlin
 * val hierarchy = projectionService.getHierarchyTree()
 * val threadActivity = projectionService.getThreadActivity()
 * val timeline = projectionService.getCoroutineTimeline(coroutineId)
 * ```
 *
 * @property session The session to subscribe to for events
 */
class ProjectionService(
    private val session: VizSession,
) {
    // In-memory state
    private val coroutines = ConcurrentHashMap<String, HierarchyNode>()
    private val threadActivity = ConcurrentHashMap<String, MutableList<ThreadEvent>>()

    init {
        // Subscribe to event bus
        session.sessionScope.launch {
            session.eventBus.stream().collect { event ->
                processEvent(event)
            }
        }
    }

    /**
     * Rebuild every projection by replaying [events] in order. A [VizSession]
     * reconstructed from a DB-backed store (ADR-015) starts with empty
     * projections because the events were persisted by a previous instance and
     * never flow through the live [eventBus] again; without replay the hierarchy
     * tree and thread-activity views are empty even though the events exist.
     * Clears existing state first so the call is safe to invoke at reconstruction
     * time. Does not touch the store or the bus.
     */
    fun rebuildFrom(events: List<VizEvent>) {
        coroutines.clear()
        threadActivity.clear()
        events.forEach { processEvent(it) }
    }

    private fun processEvent(event: VizEvent) {
        when (event) {
            is CoroutineCreated -> {
                coroutines[event.coroutineId] =
                    HierarchyNode(
                        id = event.coroutineId,
                        parentId = event.parentCoroutineId,
                        name = event.label ?: event.coroutineId,
                        scopeId = event.scopeId,
                        state = "CREATED",
                        createdAtNanos = event.tsNanos,
                        jobId = event.jobId,
                    )

                // Add to parent's children list
                event.parentCoroutineId?.let { parentId ->
                    coroutines[parentId]?.let { parent ->
                        coroutines[parentId] =
                            parent.copy(
                                children = parent.children + event.coroutineId,
                            )
                    }
                }
            }

            is CoroutineStarted -> {
                coroutines[event.coroutineId]?.let { node ->
                    coroutines[event.coroutineId] = node.copy(state = "RUNNING")
                }
            }

            is ThreadAssigned -> {
                coroutines[event.coroutineId]?.let { node ->
                    coroutines[event.coroutineId] =
                        node.copy(
                            currentThreadId = event.threadId,
                            currentThreadName = event.threadName,
                        )
                }

                // Track thread activity
                threadActivity.getOrPut(event.threadId.toString()) { mutableListOf() }
                    .add(
                        ThreadEvent(
                            coroutineId = event.coroutineId,
                            threadId = event.threadId,
                            threadName = event.threadName,
                            timestamp = event.tsNanos,
                            eventType = "ASSIGNED",
                            dispatcherName = event.dispatcherName,
                        ),
                    )
            }

            is CoroutineSuspended -> {
                coroutines[event.coroutineId]?.let { node ->
                    coroutines[event.coroutineId] = node.copy(state = "SUSPENDED")
                }
            }

            is CoroutineCompleted -> {
                coroutines[event.coroutineId]?.let { node ->
                    coroutines[event.coroutineId] =
                        node.copy(
                            state = "COMPLETED",
                            completedAtNanos = event.tsNanos,
                        )
                }
            }

            is CoroutineCancelled -> {
                coroutines[event.coroutineId]?.let { node ->
                    coroutines[event.coroutineId] =
                        node.copy(
                            state = "CANCELLED",
                            completedAtNanos = event.tsNanos,
                        )
                }
            }

            is CoroutineFailed -> {
                coroutines[event.coroutineId]?.let { node ->
                    coroutines[event.coroutineId] =
                        node.copy(
                            state = "FAILED",
                            completedAtNanos = event.tsNanos,
                        )
                }
            }

            is CoroutineResumed -> {
                coroutines[event.coroutineId]?.let { node ->
                    coroutines[event.coroutineId] = node.copy(state = "RUNNING")
                }
            }

            is CoroutineBodyCompleted -> {
                // Body finished, but coroutine may still be waiting for children
                coroutines[event.coroutineId]?.let { node ->
                    coroutines[event.coroutineId] = node.copy(state = "WAITING_FOR_CHILDREN")
                }
            }

            is DispatcherSelected -> {
                // Track dispatcher information
                coroutines[event.coroutineId]?.let { node ->
                    coroutines[event.coroutineId] =
                        node.copy(
                            dispatcherId = event.dispatcherId,
                            dispatcherName = event.dispatcherName,
                        )
                }
            }

            is WaitingForChildren -> {
                coroutines[event.coroutineId]?.let { node ->
                    coroutines[event.coroutineId] =
                        node.copy(
                            state = "WAITING_FOR_CHILDREN",
                            activeChildrenIds = event.activeChildrenIds,
                            activeChildrenCount = event.activeChildrenCount,
                        )
                }
            }

            is JobStateChanged -> {
                coroutines[event.coroutineId]?.let { node ->
                    val newState =
                        when {
                            event.isCancelled -> "CANCELLED"
                            event.isCompleted -> "COMPLETED"
                            !event.isActive && event.childrenCount > 0 -> "WAITING_FOR_CHILDREN"
                            event.isActive -> "ACTIVE"
                            else -> node.state
                        }

                    coroutines[event.coroutineId] =
                        node.copy(
                            state = newState,
                        )
                }
            }

            // Ignore other event types (job operations, deferred, etc.)
            else -> { /* Ignore events we don't track in hierarchy */ }
        }
    }

    /**
     * Get hierarchy tree, optionally filtered by scopeId
     */
    fun getHierarchyTree(scopeId: String? = null): List<HierarchyNode> {
        val filtered =
            coroutines.values
                .filter { scopeId == null || it.scopeId == scopeId }

        // Find root nodes (no parent)
        val roots = filtered.filter { it.parentId == null }

        // Return full tree (children are referenced by IDs)
        return buildTree(roots, filtered)
    }

    private fun buildTree(
        roots: List<HierarchyNode>,
        allNodes: List<HierarchyNode>,
    ): List<HierarchyNode> {
        // Could return flat list or nested structure
        // Frontend can rebuild tree from parent/children IDs
        return allNodes.sortedBy { it.createdAtNanos }
    }

    /**
     * Get thread activity timeline
     */
    fun getThreadActivity(): Map<String, List<ThreadEvent>> {
        return threadActivity.mapValues { (_, events) ->
            events.sortedBy { it.timestamp }
        }
    }

    /**
     * Get timeline for specific coroutine
     */
    fun getCoroutineTimeline(coroutineId: String): CoroutineTimeline? {
        val node = coroutines[coroutineId] ?: return null

        // Aggregate the coroutine's raw events (oldest-first) into source-frame summaries.
        // Reuse the existing raw-event-by-coroutineId filter as the aggregation seed (D-02);
        // reading fresh from the store keeps this replay/DB-rehydrate safe. Only CoroutineStarted
        // and CoroutineSuspended carry source frames in v1 (D-03/D-04); other event types are
        // skipped. Dispatcher/thread/duration breakdowns stay null (deferred per D-02/D-04).
        val events =
            session.getCoroutineTimeline(coroutineId, newestFirst = false)
                .mapNotNull { toSummary(it) }

        return CoroutineTimeline(
            coroutineId = coroutineId,
            name = node.name,
            state = node.state,
            totalDuration = node.completedAtNanos?.let { it - node.createdAtNanos },
            events = events,
        )
    }

    /**
     * Map a raw event to a source-focused [TimelineEventSummary], or null to skip it.
     *
     * Emits the kebab-case `kind` strings the FE filters on (D-03). The `suspensionPoint`
     * from a [CoroutineSuspended] is passed through UNCHANGED (no flatten, no conversion);
     * [CoroutineStarted] carries no source frame (D-04).
     */
    private fun toSummary(event: VizEvent): TimelineEventSummary? =
        when (event) {
            is CoroutineSuspended ->
                TimelineEventSummary(
                    seq = event.seq,
                    tsNanos = event.tsNanos,
                    kind = "coroutine.suspended",
                    reason = event.reason,
                    suspensionPoint = event.suspensionPoint,
                )

            is CoroutineStarted ->
                TimelineEventSummary(
                    seq = event.seq,
                    tsNanos = event.tsNanos,
                    kind = "coroutine.started",
                )

            else -> null
        }

    /**
     * Get all active children of a coroutine
     */
    fun getActiveChildren(coroutineId: String): List<HierarchyNode> {
        val parent = coroutines[coroutineId] ?: return emptyList()
        return parent.children.mapNotNull { childId ->
            coroutines[childId]?.takeIf {
                it.state in listOf("ACTIVE", "RUNNING", "SUSPENDED")
            }
        }
    }

    /**
     * Check if coroutine is waiting for children
     */
    fun isWaitingForChildren(coroutineId: String): Boolean {
        val node = coroutines[coroutineId] ?: return false
        return node.state == "WAITING_FOR_CHILDREN" ||
            (node.state == "BODY_COMPLETED" && getActiveChildren(coroutineId).isNotEmpty())
    }
}
