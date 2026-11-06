package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.health.CoroutineSummary
import com.jh.proj.coroutineviz.health.RoundAnchoring
import com.jh.proj.coroutineviz.models.CoroutineState
import com.jh.proj.coroutineviz.models.CoroutineTimeline
import com.jh.proj.coroutineviz.models.HierarchyNode
import com.jh.proj.coroutineviz.session.EventStore
import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A hierarchy node (spec C-3, plan E-6): the projection's fields, the derived round and the C-2 summary. `state` is the
 * projection's string (a failure with a cancellation exception reads FAILED, job state changes apply); `summaryState` is
 * the health's classification, the one problem counts use.
 */
@Serializable
data class HierarchyNodeDto(
    val id: String,
    val parentId: String?,
    val children: List<String>,
    val name: String,
    val scopeId: String?,
    val state: String,
    val createdAtNanos: Long,
    val completedAtNanos: Long?,
    val dispatcherId: String?,
    val dispatcherName: String?,
    val currentThreadId: Long?,
    val currentThreadName: String?,
    val jobId: String?,
    val exceptionType: String?,
    val exceptionMessage: String?,
    val activeChildrenIds: List<String>,
    val activeChildrenCount: Int,
    val creationPoint: SuspensionPoint?,
    val lastSuspensionPoint: SuspensionPoint?,
    val roundId: String?,
    val summary: CoroutineSummary?,
    /** `summary.state`, which is computed and not serialized with the summary. */
    val summaryState: CoroutineState?,
    /** Built from the summary only: the events that created it are not retained. */
    val summaryOnly: Boolean = false,
    /** Replay only: this coroutine's state at the cursor is not known (plan E-3); open it from its summary. */
    val partial: Boolean = false,
)

/**
 * A throwaway in-memory session fed [events] (seq order, already cut at the view's seq) plus [synthesized] creations made
 * up from summaries: its projection and timelines are those of that moment, never a live projection that the bus updates
 * asynchronously (plan E-6, E-7).
 */
internal fun <T> projectionOf(
    sessionId: String,
    events: List<VizEvent>,
    synthesized: List<VizEvent> = emptyList(),
    read: (VizSession) -> T,
): T {
    val all = (events + synthesized).sortedBy { it.seq }
    val capacity = maxOf(1, all.size)
    val probe = VizSession("$sessionId#cut", maxEvents = capacity, eventStoreFactory = { EventStore(capacity) })
    try {
        all.forEach(probe.store::record)
        probe.projectionService.rebuildFrom(all)
        return read(probe)
    } finally {
        probe.close()
    }
}

/** The hierarchy of [view] (spec C-3): every node at the view's seq, optionally one scope or one round. */
internal fun hierarchyOf(
    view: SessionView,
    scopeId: String?,
    roundId: String?,
): List<HierarchyNodeDto> {
    val summaries = view.input.summaries
    val anchoring = RoundAnchoring.of(summaries)
    val inRound = { id: String -> roundId == null || anchoring.roundIdOf(id) == roundId }
    val nodes = projectionOf(view.session.sessionId, view.events) { it.projectionService.getHierarchyTree(scopeId) }
    val known = nodes.mapTo(HashSet()) { it.id }
    val full =
        nodes.filter { inRound(it.id) }.map { node ->
            view.requireKnown(node.id)
            node.toDto(summaries.get(node.id), anchoring.roundIdOf(node.id), view.isPartial(node.id))
        }
    // A summary has no scope: summary-only nodes appear only in the unscoped hierarchy.
    val summaryOnly =
        if (scopeId != null) {
            emptyList()
        } else {
            // A summary-only node's children come from the cut's parent links (its creation, which the projection
            // would have linked them under, is not retained).
            val childrenOf = summaries.all.filter { it.parentId != null }.groupBy({ it.parentId!! }, { it.id })
            summaries.all.filter { it.id !in known && inRound(it.id) }.map { s ->
                view.requireKnown(s.id)
                s.toSummaryOnlyDto(anchoring.roundIdOf(s.id), view.isPartial(s.id), childrenOf[s.id].orEmpty())
            }
        }
    return (full + summaryOnly).sortedWith(compareBy<HierarchyNodeDto> { it.createdAtNanos }.thenBy { it.id })
}

/** The projection nodes alone, for a session without usable summaries (the hierarchy is not a health read; E-6). */
internal fun projectionOnlyHierarchy(
    session: VizSession,
    scopeId: String?,
): List<HierarchyNodeDto> =
    projectionOf(session.sessionId, session.store.all()) { it.projectionService.getHierarchyTree(scopeId) }
        .map { it.toDto(summary = null, roundId = null, partial = false) }

/**
 * The timeline of [coroutineId] at the view's seq (plan E-7): its retained events up to the seq; for a coroutine whose
 * creation is not retained, built on a creation made up from its summary, with `eventsTrimmed` and unknown durations.
 * A bound or replay timeline describes its moment, not now: no open phase, no observation time. Null when unknown.
 */
internal fun timelineAt(
    view: SessionView,
    coroutineId: String,
): JsonObject? {
    view.requireKnown(coroutineId)
    val summary = view.input.summaries.get(coroutineId)
    val creationRetained = view.events.any { it is CoroutineCreated && it.coroutineId == coroutineId }
    val synthesized = if (!creationRetained && summary != null) listOf(summary.synthesizedCreation(view.session.sessionId)) else emptyList()
    val timeline =
        projectionOf(view.session.sessionId, view.events, synthesized) { it.projectionService.getCoroutineTimeline(coroutineId) }
            ?: return null
    val trimmed = synthesized.isNotEmpty()
    val atMoment =
        timeline.copy(
            openPhase = null,
            openPhaseSinceNanos = null,
            observedAtEpochMs = null,
            state = summary?.let { projectionState(it.state) } ?: timeline.state,
            totalDuration = timeline.totalDuration.takeUnless { trimmed },
            activeDuration = timeline.activeDuration.takeUnless { trimmed },
            suspendedDuration = timeline.suspendedDuration.takeUnless { trimmed },
            events =
                if (trimmed) {
                    timeline.events.filterNot {
                        it.seq == summary?.firstObserved?.seq && it.kind == CREATED_KIND
                    }
                } else {
                    timeline.events
                },
        )
    val body = appJson.encodeToJsonElement(CoroutineTimeline.serializer(), atMoment).jsonObject
    return JsonObject(body + mapOf("eventsTrimmed" to JsonPrimitive(trimmed), "partial" to JsonPrimitive(view.isPartial(coroutineId))))
}

/** The kind a timeline summary gives a creation (`ProjectionService.toSummary`). */
private const val CREATED_KIND = "coroutine.created"

/** The projection's state strings (`ProjectionService`): ACTIVE reads RUNNING, everything else by name. */
internal fun projectionState(state: CoroutineState): String = if (state == CoroutineState.ACTIVE) "RUNNING" else state.name

private fun HierarchyNode.toDto(
    summary: CoroutineSummary?,
    roundId: String?,
    partial: Boolean,
) = HierarchyNodeDto(
    id = id,
    parentId = parentId,
    children = children,
    name = name,
    scopeId = scopeId,
    state = state,
    createdAtNanos = createdAtNanos,
    completedAtNanos = completedAtNanos,
    dispatcherId = dispatcherId,
    dispatcherName = dispatcherName,
    currentThreadId = currentThreadId,
    currentThreadName = currentThreadName,
    jobId = jobId,
    exceptionType = exceptionType,
    exceptionMessage = exceptionMessage,
    activeChildrenIds = activeChildrenIds,
    activeChildrenCount = activeChildrenCount,
    creationPoint = creationPoint,
    lastSuspensionPoint = lastSuspensionPoint,
    roundId = roundId,
    summary = summary,
    summaryState = summary?.state,
    partial = partial,
)

private fun CoroutineSummary.toSummaryOnlyDto(
    roundId: String?,
    partial: Boolean,
    children: List<String>,
) = HierarchyNodeDto(
    id = id,
    parentId = parentId,
    children = children,
    name = name ?: id,
    scopeId = null,
    state = projectionState(state),
    createdAtNanos = firstObserved.appNanos,
    completedAtNanos = terminal?.appNanos,
    dispatcherId = null,
    dispatcherName = dispatcher,
    currentThreadId = lastThread?.threadId?.takeUnless { isTerminal },
    currentThreadName = lastThread?.threadName?.takeUnless { isTerminal },
    jobId = null,
    exceptionType = failure?.exceptionType,
    exceptionMessage = failure?.message,
    activeChildrenIds = emptyList(),
    activeChildrenCount = 0,
    creationPoint = creationLocation,
    lastSuspensionPoint = lastSuspension,
    roundId = roundId,
    summary = this,
    summaryState = state,
    summaryOnly = true,
    partial = partial,
)

/** A creation made up from what a summary keeps of it, for the throwaway projection only (plan E-7). */
private fun CoroutineSummary.synthesizedCreation(sessionId: String) =
    CoroutineCreated(
        sessionId = sessionId,
        seq = firstObserved.seq,
        tsNanos = firstObserved.appNanos,
        coroutineId = id,
        jobId = "",
        parentCoroutineId = parentId,
        scopeId = "",
        label = name,
        createdAtEpochMs = createdAtEpochMs,
        creationPoint = creationLocation,
        origin = origin,
    )

/**
 * `…/hierarchy` for a resolved session, shared by the session and the shared routes (plan E-6): nodes at one moment with
 * their round and summary; bound or replay views answer the read errors; the current hierarchy without usable summaries
 * still answers from the projection, unless a round filter needs the summaries (503).
 */
internal suspend fun io.ktor.server.application.ApplicationCall.respondHierarchy(
    session: VizSession,
    ingest: IngestSnapshot,
) {
    val scopeId = request.queryParameters["scopeId"]
    val roundId = request.queryParameters["roundId"]
    respondReadErrors {
        val binding = parseViewBinding(request.queryParameters)
        val view =
            try {
                readView(session, ingest, binding, leakThresholdMs())
            } catch (e: com.jh.proj.coroutineviz.health.SummariesUnavailableException) {
                if (binding != ViewBinding.Current || roundId != null) rethrow(e)
                null
            } catch (e: HistoryTrimmedException) {
                if (binding != ViewBinding.Current || roundId != null) rethrow(e)
                null
            }
        if (view == null) {
            respond(io.ktor.http.HttpStatusCode.OK, projectionOnlyHierarchy(session, scopeId))
        } else {
            if (binding != ViewBinding.Current) response.headers.append(AS_OF_SEQ_HEADER, view.seq.toString())
            respond(io.ktor.http.HttpStatusCode.OK, hierarchyOf(view, scopeId, roundId))
        }
    }
}
