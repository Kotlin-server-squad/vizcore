package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.health.HealthInput
import com.jh.proj.coroutineviz.health.HealthMode
import com.jh.proj.coroutineviz.health.RetentionInfo
import com.jh.proj.coroutineviz.health.SessionHealth
import com.jh.proj.coroutineviz.health.SessionHealthDerivation
import com.jh.proj.coroutineviz.health.SessionKind
import com.jh.proj.coroutineviz.health.SessionOrigin
import com.jh.proj.coroutineviz.health.SummariesUnavailableException
import com.jh.proj.coroutineviz.health.SummaryFold
import com.jh.proj.coroutineviz.health.SummaryView
import com.jh.proj.coroutineviz.health.Transition
import com.jh.proj.coroutineviz.health.Transitions
import com.jh.proj.coroutineviz.health.WatermarkObservation
import com.jh.proj.coroutineviz.health.snapshot
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession

/**
 * Whether a session's summaries saw every event it ever had. In memory they are applied on every append and outlive
 * the bounded store's trim; in database mode they are folded from the stored events on every rebuild (until Part 5b
 * persists them), so a trim is invisible to them.
 */
internal fun summariesSeeEveryEvent(): Boolean = SessionManager.backingStore() !is ExposedSessionStore

/**
 * Retention facts (spec C-3 `retention`). [firstSeqEver] is the first seq the session had: the summaries' first seq
 * when they saw every event, else 1, the first seq every session gets (D-8).
 */
internal fun retentionOf(
    events: List<VizEvent>,
    firstSeqEver: Long,
): RetentionInfo {
    val oldest = events.firstOrNull()
    return RetentionInfo(oldest?.seq, oldest?.tsNanos, trimmed = oldest != null && oldest.seq > firstSeqEver)
}

/**
 * The session's health (spec C-3): live when [atSeq] is null, else the replay fold at the cursor, clamped to the last
 * event. Throws [com.jh.proj.coroutineviz.health.SummariesUnavailableException] when the session keeps no usable summaries.
 */
internal fun readSessionHealth(
    session: VizSession,
    atSeq: Long?,
    leakThresholdMs: Long,
): SessionHealth {
    // The ingest state first: a watermark is recorded only after its poll's events were applied, so the summaries copied
    // next include every event it vouches for (spec C-7). The other order could compare a newer watermark with an older
    // run start and report a coroutine that has since suspended.
    val ingest = IngestStates.snapshot(session.sessionId)
    val durable = session.readSummaries { it.snapshot() }
    // Read after the copy: the store holds at least every event the copy saw, possibly newer ones.
    val events = session.store.all()
    val everyEvent = summariesSeeEveryEvent()
    val retention = retentionOf(events, firstSeqEver = durable.firstSeqEver(everyEvent))
    val input =
        HealthInput(
            summaries = durable,
            kind = sessionKindOf(session.sessionId),
            watermark = ingest.watermark,
            lastIngestEpochMs = ingest.lastIngestEpochMs,
            transport = ingest.transport,
            leakThresholdMs = leakThresholdMs,
            historyIncomplete = retention.trimmed && !everyEvent,
            retention = retention,
        )
    if (atSeq == null) {
        val transitions = RetainedTransitions(events, upToSeq = durable.lastEventSeq)
        return SessionHealthDerivation.derive(input.copy(transitionsFor = transitions::of))
    }
    val cursor = minOf(atSeq, durable.lastEventSeq)
    return SessionHealthDerivation.derive(
        input.copy(
            summaries = SummaryFold.foldTo(events, cursor, durable),
            mode = HealthMode.Replay(cursor),
            // The seed cannot tell which coroutines beyond capacity existed at the cursor (D-9).
            historyIncomplete = input.historyIncomplete || (retention.trimmed && durable.untrackedCount > 0),
        ),
    )
}

/** Retained lifecycle transitions per coroutine up to [upToSeq], indexed on first use (`HealthInput.transitionsFor`). */
internal class RetainedTransitions(
    events: List<VizEvent>,
    upToSeq: Long,
) {
    private val byCoroutine: Map<String, List<Transition>> by lazy {
        val index = HashMap<String, MutableList<Transition>>()
        for (event in events) {
            if (event.seq > upToSeq || event !is CoroutineEvent) continue
            val transition = Transitions.of(event) ?: continue
            index.getOrPut(event.coroutineId) { mutableListOf() } += transition
        }
        index
    }

    fun of(coroutineId: String): List<Transition> = byCoroutine[coroutineId].orEmpty()
}

/** The first seq the session had (D-8): the summaries' own when they saw every event, else 1, every session's first. */
internal fun SummaryView.firstSeqEver(everyEvent: Boolean): Long = if (everyEvent) firstEventSeq else 1L

/** The C-3 session facts of `GET /api/sessions/{id}`, read from the ingest state, the summaries and [events]. */
internal data class SessionFacts(
    val kind: SessionKind,
    val captureStartAppNanos: Long?,
    val origin: SessionOrigin,
    val appConnected: Boolean,
    val lastIngestEpochMs: Long?,
    val lastWatermark: WatermarkObservation?,
    val retention: RetentionInfo,
)

internal fun readSessionFacts(
    session: VizSession,
    events: List<VizEvent>,
): SessionFacts {
    val kind = sessionKindOf(session.sessionId)
    val ingest = IngestStates.snapshot(session.sessionId)
    // Abandoned summaries leave origin and capture start unknown (H-3); the rest of the session still answers.
    val durable =
        try {
            session.readSummaries { it.snapshot() }
        } catch (_: SummariesUnavailableException) {
            null
        }
    val everyEvent = summariesSeeEveryEvent()
    val retention = retentionOf(events, firstSeqEver = durable?.firstSeqEver(everyEvent) ?: 1L)
    return SessionFacts(
        kind = kind,
        // A database session that was trimmed no longer knows when its capture started (D-7).
        captureStartAppNanos = durable?.earliestEventAppNanos?.takeIf { everyEvent || !retention.trimmed },
        origin = durable?.let { SessionHealthDerivation.sessionOrigin(it, kind) } ?: SessionOrigin.UNKNOWN,
        appConnected = IngestConnections.isConnected(session.sessionId),
        lastIngestEpochMs = ingest.lastIngestEpochMs,
        lastWatermark = ingest.watermark,
        retention = retention,
    )
}
