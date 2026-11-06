package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineOrigin

/**
 * The core health derivation (spec C-1): pure, table-tested, used by the backend for live, replay and
 * shared reads. It absorbs the plugin's ProblemDerivation domain (real-exception rule, ordering) and
 * RoundGrouping's anchoring; presentation stays in each client.
 */
object SessionHealthDerivation {
    private val INCIDENT_ORDER: Comparator<Incident> =
        compareBy<Incident> { it.kind.ordinal }.thenByDescending { it.sortSeq }.thenBy { it.id }

    fun derive(input: HealthInput): SessionHealth = deriveFull(input).health

    /** The health plus the uncapped lists behind its capped ones (spec C-3 `health/items`, `rounds`). */
    fun deriveFull(input: HealthInput): DerivedHealth {
        val view = input.summaries
        val demo = input.kind == SessionKind.DEMO
        // Demo sessions are always REPORTED, whatever the producer stated.
        val sessionOrigin =
            if (demo) SessionOrigin.REPORTED else input.reportedOrigin ?: sessionOrigin(view, input.kind)
        val originOf = { s: CoroutineSummary -> effectiveOrigin(s, demo, sessionOrigin) }
        val (vizcore, evaluated) = view.all.partition { originOf(it) == CoroutineOrigin.VIZCORE }
        val anchoring = RoundAnchoring.of(view)
        val asOf = resolveAsOf(input)
        val evaluation = evaluatePossiblyBlocked(input)
        val incidents = incidentsOf(input, evaluated, anchoring, evaluation)
        val failuresAtLeast = view.untrackedCount > 0 || transportLossApplies(input) || input.historyIncomplete
        val problemRounds = HealthParts.problemRounds(incidents, view)
        val problemAnchors = problemRounds.mapTo(HashSet()) { it.anchorId }
        val watermarkNanos = liveWatermarkNanos(input)
        val longLived = HealthParts.longLived(evaluated, watermarkNanos, input)
        val longSuspended = HealthParts.longSuspended(evaluated, watermarkNanos, input)

        val health = SessionHealth(
            state =
                stateOf(
                    input = input,
                    problemCount = incidents.size,
                    // Spec: never claimed with origin UNKNOWN, nor when coroutines went untracked.
                    noAppCoroutines =
                        evaluated.isEmpty() && sessionOrigin == SessionOrigin.REPORTED && view.untrackedCount == 0L &&
                            !input.historyIncomplete,
                    // Green needs at least one evaluated coroutine that is known to be the app's (unknown is never
                    // counted as healthy, spec H-3), both kinds evaluated, and complete counts.
                    green =
                        evaluated.any { originOf(it) == CoroutineOrigin.APP } && sessionOrigin == SessionOrigin.REPORTED &&
                            evaluation.status == EvaluationStatus.EVALUATED && !failuresAtLeast,
                ),
            asOf = asOf,
            asOfSeq = (input.mode as? HealthMode.Replay)?.atSeq ?: asOf?.seq ?: 0L,
            sessionOrigin = sessionOrigin,
            problemCount = incidents.size,
            failedRoundCount = incidents.count { it is FailedRoundIncident },
            ungroupedFailureCount = incidents.count { it is UngroupedFailureIncident },
            possiblyBlockedCount = incidents.count { it is PossiblyBlockedIncident },
            incidents = Capped.of(incidents),
            possiblyBlocked = evaluation,
            observations = Observations(Capped.of(longLived), Capped.of(longSuspended)),
            counts = HealthParts.originCounts(view, originOf),
            failuresAtLeast = failuresAtLeast,
            vizcoreConnection = HealthParts.vizcoreConnection(vizcore),
            transport = input.transport,
            roundCount = anchoring.anchorIds.count { id -> id in problemAnchors || view.get(id)?.let(originOf) != CoroutineOrigin.VIZCORE },
            problemRoundCount = problemRounds.size,
            problemRounds = Capped.of(problemRounds),
            asOfWatermark = input.watermark?.takeIf { watermarkNanos != null },
            retention = input.retention,
        )
        return DerivedHealth(
            health = health,
            incidents = incidents,
            problemRounds = problemRounds,
            longLived = longLived,
            longSuspended = longSuspended,
            anchoring = anchoring,
            originOf = originOf,
            view = view,
            evaluatedIds = evaluated.mapTo(HashSet()) { it.id },
        )
    }

    private fun incidentsOf(
        input: HealthInput,
        evaluated: List<CoroutineSummary>,
        anchoring: RoundAnchoring,
        evaluation: PossiblyBlockedEvaluation,
    ): List<Incident> {
        val watermark = input.watermark
        val blocked =
            if (evaluation.status == EvaluationStatus.EVALUATED && watermark != null) {
                PossiblyBlockedIncidents.build(evaluated, anchoring, watermark.appNanos, input.transitionsFor)
            } else {
                emptyList()
            }
        return (FailureIncidents.build(evaluated, anchoring) + blocked).sortedWith(INCIDENT_ORDER)
    }

    /**
     * Long-lived coroutines of every origin, vizcore's own included, with their effective origin (spec C-3 `/metrics` by
     * origin): the same rule and ages as the health's long-lived observation, so the state bar filter and the health agree.
     */
    fun longLivedByOrigin(input: HealthInput): List<Pair<CoroutineOrigin, ObservedAge>> {
        val view = input.summaries
        val demo = input.kind == SessionKind.DEMO
        val origin = if (demo) SessionOrigin.REPORTED else input.reportedOrigin ?: sessionOrigin(view, input.kind)
        return HealthParts.longLived(view.all.toList(), liveWatermarkNanos(input), input).map { age ->
            effectiveOrigin(checkNotNull(view.get(age.coroutineId)), demo, origin) to age
        }
    }

    /** Lost or possibly lost events count in replay only when the loss happened at or before the cursor. */
    private fun transportLossApplies(input: HealthInput): Boolean {
        val transport = input.transport
        if (transport.lostEvents <= 0 && !transport.deliveryUncertain) return false
        val mode = input.mode
        val since = transport.sinceSeq
        return mode !is HealthMode.Replay || since == null || since <= mode.atSeq
    }

    /** The watermark observes every coroutine of a live attached app; demo and replay have none. */
    private fun liveWatermarkNanos(input: HealthInput): Long? =
        input.watermark?.appNanos?.takeIf { input.mode == HealthMode.Live && input.kind == SessionKind.ATTACHED }

    /** The session origin as the derivation reads it (spec "Origin"): demos are REPORTED; otherwise REPORTED once a coroutine reported one. */
    fun sessionOrigin(
        view: SummaryView,
        kind: SessionKind,
    ): SessionOrigin {
        val reported = view.all.any { it.origin == CoroutineOrigin.APP || it.origin == CoroutineOrigin.VIZCORE }
        return if (kind == SessionKind.DEMO || reported) SessionOrigin.REPORTED else SessionOrigin.UNKNOWN
    }

    private fun effectiveOrigin(
        s: CoroutineSummary,
        demo: Boolean,
        sessionOrigin: SessionOrigin,
    ): CoroutineOrigin =
        when {
            demo -> CoroutineOrigin.APP
            sessionOrigin == SessionOrigin.UNKNOWN -> CoroutineOrigin.UNKNOWN
            else -> s.origin ?: CoroutineOrigin.UNKNOWN
        }

    private fun resolveAsOf(input: HealthInput): AsOf? {
        val view = input.summaries
        val lastNanos = view.latestEventAppNanos
        val mode = input.mode
        if (mode is HealthMode.Replay) {
            // Appends are not in app-time order, so once events were trimmed no cursor's latest time is known for sure.
            val trimmed = input.retention?.trimmed == true
            return lastNanos?.let { AsOf(AsOfKind.REPLAY_CURSOR, it, seq = mode.atSeq, lowerBound = trimmed) }
        }
        val watermark = input.watermark
        return when {
            input.kind == SessionKind.DEMO -> lastNanos?.let { AsOf(AsOfKind.LAST_EVENT, it, seq = view.lastEventSeq) }
            // The watermark is the latest observation unless an event arrived after it (both app clock).
            watermark != null && watermark.appNanos >= (lastNanos ?: Long.MIN_VALUE) ->
                AsOf(AsOfKind.WATERMARK, watermark.appNanos, watermark.appEpochMs, watermark.receivedEpochMs, view.lastEventSeq)
            else -> lastNanos?.let { AsOf(AsOfKind.INGEST, it, receivedEpochMs = input.lastIngestEpochMs, seq = view.lastEventSeq) }
        }
    }

    private fun evaluatePossiblyBlocked(input: HealthInput): PossiblyBlockedEvaluation {
        val reason =
            when {
                input.mode is HealthMode.Replay -> NotEvaluatedReason.REPLAY
                input.kind == SessionKind.DEMO -> NotEvaluatedReason.DEMO
                // Sticky for the rest of the session (spec "Health strip states"), so before NO_WATERMARK.
                input.transport.lostEvents > 0 || input.transport.deliveryUncertain -> NotEvaluatedReason.EVENTS_DROPPED
                input.historyIncomplete -> NotEvaluatedReason.HISTORY_TRIMMED
                input.summaries.untrackedCount > 0 -> NotEvaluatedReason.OVERFLOW
                input.watermark == null -> NotEvaluatedReason.NO_WATERMARK
                else -> null
            }
        return if (reason == null) {
            PossiblyBlockedEvaluation(EvaluationStatus.EVALUATED)
        } else {
            PossiblyBlockedEvaluation(EvaluationStatus.NOT_EVALUATED, reason)
        }
    }

    private fun stateOf(
        input: HealthInput,
        problemCount: Int,
        noAppCoroutines: Boolean,
        green: Boolean,
    ): HealthState {
        val noEvents = input.summaries.all.isEmpty() && input.summaries.lastEventSeq == 0L
        val noData = noEvents && input.watermark == null && input.mode == HealthMode.Live
        return when {
            noData -> HealthState.AWAITING_FIRST_DATA
            problemCount > 0 -> HealthState.PROBLEMS
            noAppCoroutines -> HealthState.NO_APP_COROUTINES
            green -> HealthState.NO_PROBLEMS
            else -> HealthState.NO_FAILURES_PARTIAL
        }
    }
}

/** The health plus the uncapped lists behind its capped ones, for paging (spec C-3 `health/items`, `rounds`; plan E-11). */
class DerivedHealth internal constructor(
    val health: SessionHealth,
    /** Every incident, in the health's order (kind, then newest first). */
    val incidents: List<Incident>,
    /** Every round holding an incident, newest anchor first. */
    val problemRounds: List<ProblemRound>,
    val longLived: List<ObservedAge>,
    val longSuspended: List<ObservedAge>,
    val anchoring: RoundAnchoring,
    /** The effective origin the derivation used for each coroutine (demo → APP, a session of UNKNOWN origin → UNKNOWN). */
    val originOf: (CoroutineSummary) -> CoroutineOrigin,
    private val view: SummaryView,
    private val evaluatedIds: Set<String>,
) {
    /** The members of the failed-round incident [incidentId] (failures first, then cancellations after), or null when it is not one here. */
    fun roundMembers(incidentId: String): List<RoundMember>? {
        val incident = incidents.firstOrNull { it.id == incidentId } as? FailedRoundIncident ?: return null
        val inRound = view.all.filter { it.id in evaluatedIds && anchoring.anchorOf(it.id) == incident.anchorId }
        val (failures, cancelled) = FailureIncidents.members(inRound) ?: return emptyList()
        return failures.map { it.member(MemberRole.FAILED) } + cancelled.map { it.member(MemberRole.CANCELLED_AFTER) }
    }
}

private fun CoroutineSummary.member(role: MemberRole) =
    RoundMember(
        coroutineId = id,
        name = name,
        role = role,
        exceptionType = failure?.exceptionType,
        message = failure?.message,
        terminalSeq = FailureIncidents.terminalSeq(this),
        detailsDropped = reduced,
    )
