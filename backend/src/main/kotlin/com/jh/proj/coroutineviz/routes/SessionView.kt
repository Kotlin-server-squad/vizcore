package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.health.BoundSummaries
import com.jh.proj.coroutineviz.health.DerivedHealth
import com.jh.proj.coroutineviz.health.HealthInput
import com.jh.proj.coroutineviz.health.HealthMode
import com.jh.proj.coroutineviz.health.RebuildLevel
import com.jh.proj.coroutineviz.health.SessionHealthDerivation
import com.jh.proj.coroutineviz.health.SummaryBinding
import com.jh.proj.coroutineviz.health.SummaryView
import com.jh.proj.coroutineviz.health.WatermarkObservation
import com.jh.proj.coroutineviz.health.snapshot
import com.jh.proj.coroutineviz.session.VizSession

/** One read's view of a session: the summaries at [seq], how well each is known, and the derivation input (plan E-1..E-3). */
internal class SessionView(
    val session: VizSession,
    /** The stored events up to [seq], read after the summaries were copied. */
    val events: List<VizEvent>,
    val binding: ViewBinding,
    /** The seq the view is at: the last event (Current), the clamped cursor (Replay, Bound). */
    val seq: Long,
    val bound: BoundSummaries,
    val input: HealthInput,
) {
    val strict: Boolean get() = binding is ViewBinding.Bound

    /** 0 outside replay; in replay, how many coroutines' state at the cursor is not known (E-3). */
    val unknownAtSeq: Int get() = if (binding is ViewBinding.Replay) bound.unknownIds.size else 0

    fun derive(): DerivedHealth = SessionHealthDerivation.deriveFull(input)

    /** A strict read cannot answer while any coroutine's state at [seq] is unknown (E-2). */
    fun requireNoUnknown() {
        if (strict && bound.unknownIds.isNotEmpty()) throw StaleViewException("${bound.unknownIds.size} coroutines changed since $seq")
    }

    /** A strict read cannot return [id] unless it is known at [seq] (E-2). */
    fun requireKnown(id: String) {
        if (strict && bound.levelOf(id) == RebuildLevel.UNKNOWN) throw StaleViewException("$id changed since $seq")
    }

    /** Replay only: [id]'s state at the cursor is not known (E-3). */
    fun isPartial(id: String): Boolean = binding is ViewBinding.Replay && bound.levelOf(id) == RebuildLevel.UNKNOWN

    /** The cut this view's health is derived from, for its follow-up reads (E-17). Current views only. */
    fun cut(nowMs: Long): HealthCut {
        check(binding == ViewBinding.Current) { "only the current view is a health cut" }
        return HealthCut(
            sessionId = session.sessionId,
            seq = seq,
            summaries = input.summaries,
            ingest = IngestSnapshot(input.watermark, input.lastIngestEpochMs, input.transport),
            retention = checkNotNull(input.retention),
            createdAtMs = nowMs,
        )
    }
}

/**
 * Reads [session] as [binding] says (plan E-3, E-17), in 5a's order (D-9c): [ingestCut] was read before [session] was
 * resolved; the summaries are copied; the transport is read again after the copy; the events after it.
 *
 * @throws com.jh.proj.coroutineviz.health.SummariesUnavailableException when the session keeps no usable summaries.
 * @throws HistoryTrimmedException for a database session trimmed without a checkpoint (unless a kept cut answers).
 * @throws StaleViewException for a bound read that missed its cut and whose transport revision moved on.
 */
internal fun readView(
    session: VizSession,
    ingestCut: IngestSnapshot,
    binding: ViewBinding,
    leakThresholdMs: Long,
): SessionView {
    val durable = session.readSummaries { it.snapshot() }
    val ingest = IngestStates.snapshot(session.sessionId).copy(watermark = ingestCut.watermark)
    val allEvents = session.store.all()
    val last = durable.lastEventSeq
    val kept = (binding as? ViewBinding.Bound)?.let { HealthCuts.find(session.sessionId, minOf(it.asOfSeq, last)) }
    val everyEvent = summariesSeeEveryEvent()
    val retention = retentionOf(allEvents, firstSeqEver = durable.firstSeqEver(everyEvent))
    if (kept == null && retention.trimmed && !everyEvent) throw HistoryTrimmedException("session ${session.sessionId} was trimmed")
    val kind = sessionKindOf(session.sessionId)
    val transport = transportOf(ingest, session, kind, last)
    val base =
        HealthInput(
            summaries = durable,
            kind = kind,
            watermark = ingest.watermark,
            lastIngestEpochMs = ingest.lastIngestEpochMs,
            transport = transport,
            leakThresholdMs = leakThresholdMs,
            retention = retention,
        )

    fun upTo(seq: Long) = allEvents.filter { it.seq <= seq }

    fun transitionsUpTo(seq: Long) = RetainedTransitions(allEvents, upToSeq = seq)::of
    return when (binding) {
        ViewBinding.Current ->
            SessionView(
                session,
                upTo(last),
                binding,
                last,
                BoundSummaries.exact(durable),
                base.copy(transitionsFor = transitionsUpTo(last)),
            )
        is ViewBinding.Replay -> {
            val cursor = minOf(binding.atSeq, last)
            val bound = SummaryBinding.bindAt(durable, allEvents, cursor)
            val input =
                base.copy(
                    summaries = bound.view,
                    mode = HealthMode.Replay(cursor),
                    // The seed cannot tell which coroutines beyond capacity existed at the cursor: their failures may be missing.
                    historyIncomplete = retention.trimmed && durable.untrackedCount > 0,
                )
            SessionView(session, upTo(cursor), binding, cursor, bound, input)
        }
        is ViewBinding.Bound -> boundView(session, binding, durable, allEvents, base)
    }
}

private fun boundView(
    session: VizSession,
    binding: ViewBinding.Bound,
    durable: SummaryView,
    allEvents: List<VizEvent>,
    base: HealthInput,
): SessionView {
    val s = minOf(binding.asOfSeq, durable.lastEventSeq)
    val events = allEvents.filter { it.seq <= s }
    val transitions = RetainedTransitions(allEvents, upToSeq = s)::of
    val kept = HealthCuts.find(session.sessionId, s)
    if (kept != null) {
        val input =
            base.copy(
                summaries = kept.summaries,
                watermark = kept.ingest.watermark,
                lastIngestEpochMs = kept.ingest.lastIngestEpochMs,
                transport = kept.ingest.transport,
                retention = kept.retention,
                transitionsFor = transitions,
            )
        return SessionView(session, events, binding, s, BoundSummaries.exact(kept.summaries), input)
    }
    if (binding.transportRevision != null && binding.transportRevision != base.transport.revision) {
        throw StaleViewException("the transport changed since the health")
    }
    val bound = SummaryBinding.bindAt(durable, allEvents, s)
    val watermark =
        binding.watermarkNanos?.let { w ->
            base.watermark?.takeIf { it.appNanos == w } ?: WatermarkObservation(w, appEpochMs = 0, receivedEpochMs = 0)
        }
    val trimmed = base.retention?.trimmed == true
    val input =
        base.copy(
            summaries = bound.view,
            watermark = watermark,
            historyIncomplete = s < durable.lastEventSeq && trimmed && durable.untrackedCount > 0,
            transitionsFor = transitions,
        )
    return SessionView(session, events, binding, s, bound, input)
}
