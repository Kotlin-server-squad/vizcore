package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineOrigin

/**
 * The core health derivation (spec C-1): pure, table-tested, used by the backend for live, replay and
 * shared reads. It absorbs the plugin's ProblemDerivation domain (real-exception rule, ordering) and
 * RoundGrouping's anchoring; presentation stays in each client.
 */
object SessionHealthDerivation {
    private val INCIDENT_ORDER: Comparator<Incident> =
        compareBy<Incident> { it.kind.ordinal }.thenByDescending { it.sortSeq }

    fun derive(input: HealthInput): SessionHealth {
        val view = input.summaries
        val demo = input.kind == SessionKind.DEMO
        // Demo sessions are always REPORTED, whatever the producer stated.
        val sessionOrigin =
            if (demo) SessionOrigin.REPORTED else input.reportedOrigin ?: sessionOriginOf(view, demo = false)
        val originOf = { s: CoroutineSummary -> effectiveOrigin(s, demo, sessionOrigin) }
        val (vizcore, evaluated) = view.all.partition { originOf(it) == CoroutineOrigin.VIZCORE }
        val anchoring = RoundAnchoring.of(view)
        val asOf = resolveAsOf(input)
        val evaluation = evaluatePossiblyBlocked(input)
        val incidents = incidentsOf(input, evaluated, anchoring, evaluation)
        val failuresAtLeast = view.untrackedCount > 0 || transportLossApplies(input)
        val problemRounds = HealthParts.problemRounds(incidents, view)
        val problemAnchors = problemRounds.mapTo(HashSet()) { it.anchorId }

        return SessionHealth(
            state =
                stateOf(
                    input = input,
                    problemCount = incidents.size,
                    // Spec: never claimed with origin UNKNOWN, nor when coroutines went untracked.
                    noAppCoroutines =
                        evaluated.isEmpty() && sessionOrigin == SessionOrigin.REPORTED && view.untrackedCount == 0L,
                    // Green needs something evaluated, both kinds evaluated, and complete counts.
                    green = evaluated.isNotEmpty() && evaluation.status == EvaluationStatus.EVALUATED && !failuresAtLeast,
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
            observations = HealthParts.observations(evaluated, liveWatermarkNanos(input), input),
            counts = HealthParts.originCounts(view, originOf),
            failuresAtLeast = failuresAtLeast,
            vizcoreConnection = HealthParts.vizcoreConnection(vizcore),
            transport = input.transport,
            roundCount = anchoring.anchorIds.count { id -> id in problemAnchors || view.get(id)?.let(originOf) != CoroutineOrigin.VIZCORE },
            problemRoundCount = problemRounds.size,
            problemRounds = Capped.of(problemRounds),
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

    private fun sessionOriginOf(
        view: SummaryView,
        demo: Boolean,
    ): SessionOrigin {
        val reported = view.all.any { it.origin == CoroutineOrigin.APP || it.origin == CoroutineOrigin.VIZCORE }
        return if (demo || reported) SessionOrigin.REPORTED else SessionOrigin.UNKNOWN
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
        if (mode is HealthMode.Replay) return lastNanos?.let { AsOf(AsOfKind.REPLAY_CURSOR, it, seq = mode.atSeq) }
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
