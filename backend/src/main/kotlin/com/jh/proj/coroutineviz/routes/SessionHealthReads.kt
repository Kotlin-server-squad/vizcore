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
import com.jh.proj.coroutineviz.health.TransportStatus
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
 * A database session whose stored events were trimmed (until Part 5b persists its summaries): its summaries are folded
 * from what is left, and a coroutine whose parent's creation was trimmed loses its round, so one failed round can read
 * as several ungrouped failures. "At least" would then overstate, so the health is unavailable instead (H-1, H-3).
 */
internal class HistoryTrimmedException(
    message: String,
) : IllegalStateException(message)

/**
 * The transport a read reports (spec C-3 `transport`). A database session outlives this process: events that arrived
 * before the first connection this process saw came through a connection nobody here watched end (a crash runs no close
 * handler), so nothing vouches that all of them arrived (H-3). Until Part 5b persists the transport and the "ingest
 * open" marker, such a session reads delivery-uncertain from its first seq.
 */
internal fun transportOf(
    ingest: IngestSnapshot,
    kind: SessionKind,
    lastSeq: Long,
): TransportStatus {
    val unwatched =
        !summariesSeeEveryEvent() &&
            kind == SessionKind.ATTACHED &&
            lastSeq > 0 &&
            (ingest.firstConnectionSeq ?: Long.MAX_VALUE) > 1
    return if (unwatched) ingest.transport.copy(deliveryUncertain = true, sinceSeq = 1) else ingest.transport
}

/**
 * The session's health (spec C-3): live when [atSeq] is null, else the replay fold at the cursor, clamped to the last
 * event. [ingest] must be read before [session] was resolved: in database mode resolving rebuilds the session from the
 * stored events, and a watermark is recorded only after its poll's events were stored, so every event the watermark
 * vouches for is in the rebuilt summaries (spec C-7). The other order could compare a newer watermark with an older run
 * start and report a coroutine that has since suspended.
 *
 * @throws com.jh.proj.coroutineviz.health.SummariesUnavailableException when the session keeps no usable summaries.
 * @throws HistoryTrimmedException for a trimmed database session.
 */
internal fun readSessionHealth(
    session: VizSession,
    ingest: IngestSnapshot,
    atSeq: Long?,
    leakThresholdMs: Long,
): SessionHealth {
    val durable = session.readSummaries { it.snapshot() }
    // Read after the copy: the store holds at least every event the copy saw, possibly newer ones.
    val events = session.store.all()
    val everyEvent = summariesSeeEveryEvent()
    val retention = retentionOf(events, firstSeqEver = durable.firstSeqEver(everyEvent))
    if (retention.trimmed && !everyEvent) throw HistoryTrimmedException("session ${session.sessionId} was trimmed")
    val kind = sessionKindOf(session.sessionId)
    val input =
        HealthInput(
            summaries = durable,
            kind = kind,
            watermark = ingest.watermark,
            lastIngestEpochMs = ingest.lastIngestEpochMs,
            transport = transportOf(ingest, kind, durable.lastEventSeq),
            leakThresholdMs = leakThresholdMs,
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
            // The seed cannot tell which coroutines beyond capacity existed at the cursor: their failures may be missing.
            historyIncomplete = retention.trimmed && durable.untrackedCount > 0,
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
            Transitions.of(event)?.let { index.getOrPut(event.coroutineId) { mutableListOf() }.add(it) }
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
