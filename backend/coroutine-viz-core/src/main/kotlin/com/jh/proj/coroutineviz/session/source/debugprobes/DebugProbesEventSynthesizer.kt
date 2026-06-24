package com.jh.proj.coroutineviz.session.source.debugprobes

import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.session.EventContext
import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.session.coroutineCompleted
import com.jh.proj.coroutineviz.session.coroutineCreated
import com.jh.proj.coroutineviz.session.coroutineResumed
import com.jh.proj.coroutineviz.session.coroutineStarted
import com.jh.proj.coroutineviz.session.coroutineSuspended

/**
 * Maps a [CoroutineDelta] to the existing `VizEvent` subtypes via the
 * [EventContext] extension functions (Research §"Don't Hand-Roll": reuse
 * EventContext so seq/ts are filled and events are byte-for-byte what the FE
 * renders). RCO-03 attribution rides EXISTING event fields — no new VizEvent
 * field is added:
 * - `CoroutineName` → `label`
 * - function/file:line + reason → `SuspensionPoint(function, fileName, lineNumber, reason)`
 *
 * Decisions locked for v1 (06-RESEARCH.md):
 * - `parentCoroutineId` = null (A5; FE tolerates null parent).
 * - `scopeId` = [SOURCE_ID] ("debugprobes").
 * - Vanished → [com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted]
 *   only — DebugProbes cannot distinguish completed/cancelled/failed (A3).
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
            parentCoroutineId = null,
            scopeId = sourceId,
            label = snapshot.label,
        )

    private fun suspensionPointOf(snapshot: CoroutineSnapshot): SuspensionPoint? {
        // Only build a point when we have at least a function name to attribute.
        val function = snapshot.function ?: return null
        return SuspensionPoint(
            function = function,
            fileName = snapshot.fileName,
            lineNumber = snapshot.lineNumber,
            reason = snapshot.reason ?: DEFAULT_REASON,
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
     * Map one delta to the ordered list of synthesized events for the bound
     * [session].
     */
    fun synthesize(
        delta: CoroutineDelta,
        session: VizSession,
    ): List<VizEvent> =
        when (delta) {
            is CoroutineDelta.Appeared -> {
                val ctx = contextFor(session, delta.now)
                when (delta.now.state) {
                    CoroState.CREATED -> listOf(ctx.coroutineCreated())
                    CoroState.RUNNING -> listOf(ctx.coroutineCreated(), ctx.coroutineStarted())
                    CoroState.SUSPENDED ->
                        listOf(ctx.coroutineCreated(), ctx.coroutineStarted()) + suspendedEvents(ctx, delta.now)
                }
            }

            is CoroutineDelta.StateChanged -> {
                val ctx = contextFor(session, delta.now)
                when {
                    delta.from == CoroState.CREATED && delta.to == CoroState.RUNNING ->
                        listOf(ctx.coroutineStarted())
                    // A coroutine observed jumping CREATED→SUSPENDED (it started and
                    // parked between polls) must emit started BEFORE suspended, else
                    // the FE sees a suspend with no prior start (WR-01). This case
                    // must precede the generic `to == SUSPENDED` branch below.
                    delta.from == CoroState.CREATED && delta.to == CoroState.SUSPENDED ->
                        listOf(ctx.coroutineStarted()) + suspendedEvents(ctx, delta.now)
                    delta.to == CoroState.SUSPENDED -> suspendedEvents(ctx, delta.now)
                    delta.from == CoroState.SUSPENDED && delta.to == CoroState.RUNNING ->
                        listOf(ctx.coroutineResumed())
                    // Defensive: any other transition into RUNNING treated as a start.
                    delta.to == CoroState.RUNNING -> listOf(ctx.coroutineStarted())
                    else -> emptyList()
                }
            }

            is CoroutineDelta.Vanished -> {
                val ctx = contextFor(session, delta.last)
                listOf(ctx.coroutineCompleted())
            }
        }
}
