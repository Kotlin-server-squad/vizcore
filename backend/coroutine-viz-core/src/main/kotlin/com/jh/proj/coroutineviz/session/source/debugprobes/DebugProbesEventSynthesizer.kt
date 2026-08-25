package com.jh.proj.coroutineviz.session.source.debugprobes

import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.session.EventContext
import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.session.coroutineCancelled
import com.jh.proj.coroutineviz.session.coroutineCompleted
import com.jh.proj.coroutineviz.session.coroutineCreated
import com.jh.proj.coroutineviz.session.coroutineFailed
import com.jh.proj.coroutineviz.session.coroutineResumed
import com.jh.proj.coroutineviz.session.coroutineStarted
import com.jh.proj.coroutineviz.session.coroutineSuspended
import com.jh.proj.coroutineviz.session.dispatcherSelected
import com.jh.proj.coroutineviz.session.threadAssigned

/**
 * Maps a [CoroutineDelta] to the existing `VizEvent` subtypes via the
 * [EventContext] extension functions (Research §"Don't Hand-Roll": reuse
 * EventContext so seq/ts are filled and events are byte-for-byte what the FE
 * renders). RCO-03 attribution rides the event fields:
 * - `CoroutineName` → `label`
 * - the TRUE suspension frame (first user frame of `lastObservedStackTrace`) +
 *   reason → `SuspensionPoint(function, fileName, lineNumber, reason)` on
 *   `coroutine.suspended`, falling back to the creation-derived frame when the
 *   observed stack has no user frame (15-08 Task 3 — a frame is never lost)
 * - the launch site (creation-derived frame) → `CoroutineCreated.creationPoint`
 *   (GAP-JUMP-WRONG-FRAME: the creation frame used to be stamped onto every
 *   suspended event as its SuspensionPoint, making "Suspended at" structurally
 *   the launch site; the two frames now ride separate fields)
 *
 * Hierarchy + grouping (Phase 8, D-01/D-02/D-03 — supersedes the v1 flat locks):
 * - `parentCoroutineId` = the nearest-observed-ancestor's id, derived as
 *   `"dp-${snapshot.parentKey.token}"` — the SAME `dp-` form as [coroutineId], so a
 *   child's `parentCoroutineId` equals its parent coroutine's own id and
 *   ProjectionService wires the tree edge with zero downstream change. Null
 *   `parentKey` (tree root / synthetic null-job path) → null parent.
 * - `scopeId` = `snapshot.dispatcherName ?: sourceId` (D-03 / Open Q1 default), so
 *   `getHierarchyTree(scopeId)` groups by dispatcher instead of one flat
 *   "debugprobes" bucket.
 *
 * Thread/dispatcher enrichment (15-08 Task 2, GAP-ENRICHMENT-EMPTY):
 * ProjectionService populates hierarchy `currentThread*`/`dispatcher*` ONLY from
 * ThreadAssigned/DispatcherSelected events. This synthesizer now emits both on
 * the agent path: ThreadAssigned on Appeared (RUNNING/SUSPENDED) and on every
 * transition INTO RUNNING, when the snapshot carries an observed thread (never
 * fabricated); DispatcherSelected ONCE per coroutine, at Appeared, with
 * `dispatcherId = dispatcherName` (the stable normalized name is the only id
 * DebugProbes has). The `scopeId` dispatcher routing above is NOT moved — the
 * dedicated events are additive (MetricsProjection keys on scopeId).
 *
 * Vanished outcome mapping (15-08, supersedes the v1 A3 lock): DebugProbes'
 * DUMP still cannot distinguish completed/cancelled/failed, but the Job
 * completion cause can — [CoroutineInfoAdapter] registers
 * `Job.invokeOnCompletion` per observed Job and records a [CompletionOutcome]
 * the source consumes at Vanished time. Vanished therefore maps to
 * [com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed] (failure cause),
 * [com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled] (cancellation),
 * or [com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted] (no
 * recorded outcome — normal completion or no Job in hand).
 *
 * The synthesizer is PURE w.r.t. session lifecycle: it builds an [EventContext]
 * (which calls `session.nextSeq()`) and returns the events; the caller (the
 * source poll loop) is responsible for `session.send`-ing them.
 */
class DebugProbesEventSynthesizer(
    private val sourceId: String = SOURCE_ID,
) {
    companion object {
        const val SOURCE_ID: String = "debugprobes"

        /** Default suspension reason when the snapshot carries none (IN-05). */
        private const val DEFAULT_REASON: String = "suspend"

        /** Reason marking a creationPoint as a launch-site ref (15-08 Task 3). */
        private const val CREATION_REASON: String = "launch"
    }

    /** Derive a stable coroutine/job id from the opaque key token. */
    private fun coroutineId(snapshot: CoroutineSnapshot): String = "dp-${snapshot.key.token}"

    private fun jobId(snapshot: CoroutineSnapshot): String = "job-dp-${snapshot.key.token}"

    private fun contextFor(
        session: VizSession,
        snapshot: CoroutineSnapshot,
    ): EventContext =
        EventContext(
            session = session,
            coroutineId = coroutineId(snapshot),
            jobId = jobId(snapshot),
            // Same "dp-" form as coroutineId(snapshot) (line above) so the child's
            // parentCoroutineId equals the parent coroutine's OWN id — edges connect
            // (D-01/D-02, key-consistency note). Null parentKey → root.
            parentCoroutineId = snapshot.parentKey?.let { "dp-${it.token}" },
            // Dispatcher-derived grouping (D-03 / Open Q1 default); source-id fallback
            // when the dispatcher is unknown.
            scopeId = snapshot.dispatcherName ?: sourceId,
            label = snapshot.label,
        )

    /**
     * The TRUE suspension frame: first user frame of the last observed stack
     * (15-08 Task 3). When that yields no user frame (empty/all-infrastructure
     * stack, or another party pre-installed DebugProbes differently), FALL BACK
     * to the creation-derived snapshot fields so an available frame is never
     * lost — degrade, never null-out.
     */
    private fun suspensionPointOf(snapshot: CoroutineSnapshot): SuspensionPoint? {
        val observed = snapshot.lastObservedStackTrace?.let { SourceAttribution.fromStack(it) }
        val observedFunction = observed?.function
        if (observedFunction != null) {
            return SuspensionPoint(
                function = observedFunction,
                fileName = observed.fileName,
                lineNumber = observed.lineNumber,
                reason = snapshot.reason ?: DEFAULT_REASON,
            )
        }
        // Fallback: creation-derived fields (the pre-15-08 behavior).
        val function = snapshot.function ?: return null
        return SuspensionPoint(
            function = function,
            fileName = snapshot.fileName,
            lineNumber = snapshot.lineNumber,
            reason = snapshot.reason ?: DEFAULT_REASON,
        )
    }

    /**
     * The launch site (creation-derived frame) riding CoroutineCreated.creationPoint
     * (15-08 Task 3). Null when creation stacks are unavailable (e.g. a foreign
     * DebugProbes install without creation stacks) — nothing regresses, the
     * suspended events still carry the last-observed frame.
     */
    private fun creationPointOf(snapshot: CoroutineSnapshot): SuspensionPoint? {
        val function = snapshot.function ?: return null
        return SuspensionPoint(
            function = function,
            fileName = snapshot.fileName,
            lineNumber = snapshot.lineNumber,
            reason = CREATION_REASON,
        )
    }

    private fun suspendedEvents(
        ctx: EventContext,
        snapshot: CoroutineSnapshot,
    ): List<VizEvent> =
        listOf(
            ctx.coroutineSuspended(
                reason = snapshot.reason ?: DEFAULT_REASON,
                suspensionPoint = suspensionPointOf(snapshot),
            ),
        )

    /**
     * ThreadAssigned when BOTH thread id and name were observed (15-08 Task 2);
     * empty otherwise — thread data is never fabricated.
     */
    private fun threadEvents(
        ctx: EventContext,
        snapshot: CoroutineSnapshot,
    ): List<VizEvent> {
        val threadId = snapshot.threadId ?: return emptyList()
        val threadName = snapshot.threadName ?: return emptyList()
        return listOf(ctx.threadAssigned(threadId, threadName, snapshot.dispatcherName))
    }

    /**
     * DispatcherSelected once per coroutine, at Appeared only (15-08 Task 2).
     * `dispatcherId = dispatcherName`: the stable normalized name from
     * [SourceAttribution.dispatcherName] is the only id DebugProbes has.
     */
    private fun dispatcherEvents(
        ctx: EventContext,
        snapshot: CoroutineSnapshot,
    ): List<VizEvent> =
        snapshot.dispatcherName
            ?.let { listOf(ctx.dispatcherSelected(dispatcherId = it, dispatcherName = it)) }
            .orEmpty()

    /**
     * Map one delta to the ordered list of synthesized events for the bound
     * [session].
     *
     * @param outcome the coroutine's recorded terminal outcome, consumed by the
     *   source at Vanished time (null for non-Vanished deltas, or when the
     *   coroutine completed normally / carried no Job). Trailing + defaulted so
     *   all pre-15-08 call sites stay source-compatible.
     */
    fun synthesize(
        delta: CoroutineDelta,
        session: VizSession,
        outcome: CompletionOutcome? = null,
    ): List<VizEvent> =
        when (delta) {
            is CoroutineDelta.Appeared -> appearedEvents(contextFor(session, delta.now), delta.now)
            is CoroutineDelta.StateChanged -> stateChangedEvents(contextFor(session, delta.now), delta)
            is CoroutineDelta.Vanished -> listOf(vanishedEvent(contextFor(session, delta.last), outcome))
        }

    /**
     * Appeared mapping: created/started per observed state, then the one-shot
     * DispatcherSelected (once per coroutine), then ThreadAssigned (RUNNING/
     * SUSPENDED with an observed thread), then suspended last (15-08 Task 2).
     */
    private fun appearedEvents(
        ctx: EventContext,
        snapshot: CoroutineSnapshot,
    ): List<VizEvent> {
        val dispatcher = dispatcherEvents(ctx, snapshot)
        val created = ctx.coroutineCreated(creationPoint = creationPointOf(snapshot))
        return when (snapshot.state) {
            CoroState.CREATED -> listOf<VizEvent>(created) + dispatcher
            CoroState.RUNNING ->
                listOf(created, ctx.coroutineStarted()) + dispatcher + threadEvents(ctx, snapshot)
            CoroState.SUSPENDED ->
                listOf(created, ctx.coroutineStarted()) + dispatcher +
                    threadEvents(ctx, snapshot) + suspendedEvents(ctx, snapshot)
        }
    }

    private fun stateChangedEvents(
        ctx: EventContext,
        delta: CoroutineDelta.StateChanged,
    ): List<VizEvent> =
        when {
            delta.from == CoroState.CREATED && delta.to == CoroState.RUNNING ->
                listOf(ctx.coroutineStarted()) + threadEvents(ctx, delta.now)
            // A coroutine observed jumping CREATED→SUSPENDED (it started and
            // parked between polls) must emit started BEFORE suspended, else
            // the FE sees a suspend with no prior start (WR-01). This case
            // must precede the generic `to == SUSPENDED` branch below.
            delta.from == CoroState.CREATED && delta.to == CoroState.SUSPENDED ->
                listOf(ctx.coroutineStarted()) + suspendedEvents(ctx, delta.now)
            delta.to == CoroState.SUSPENDED -> suspendedEvents(ctx, delta.now)
            delta.from == CoroState.SUSPENDED && delta.to == CoroState.RUNNING ->
                listOf(ctx.coroutineResumed()) + threadEvents(ctx, delta.now)
            // Defensive: any other transition into RUNNING treated as a start.
            delta.to == CoroState.RUNNING -> listOf(ctx.coroutineStarted()) + threadEvents(ctx, delta.now)
            else -> emptyList()
        }

    /**
     * Outcome-aware Vanished mapping (15-08): no outcome → completed (byte-identical
     * to the pre-15-08 event); cancelled → CoroutineCancelled; else → CoroutineFailed.
     */
    private fun vanishedEvent(
        ctx: EventContext,
        outcome: CompletionOutcome?,
    ): VizEvent =
        when {
            outcome == null -> ctx.coroutineCompleted()
            outcome.cancelled -> ctx.coroutineCancelled(cause = outcome.message)
            else -> ctx.coroutineFailed(exceptionType = outcome.exceptionType, message = outcome.message)
        }
}
