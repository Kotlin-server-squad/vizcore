package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineOrigin

internal object HealthParts {
    private const val NANOS_PER_MS = 1_000_000L

    /**
     * Long-lived coroutines (spec "Lifetimes"), newest first by first observation: an active coroutine's age ends at the
     * latest watermark when there is one ([watermarkNanos], which observes every coroutine of a live app), otherwise at its
     * own last observation, never at another coroutine's activity.
     */
    fun longLived(
        evaluated: List<CoroutineSummary>,
        watermarkNanos: Long?,
        input: HealthInput,
    ): List<ObservedAge> {
        val leakNanos = input.leakThresholdMs * NANOS_PER_MS
        return ages(evaluated, watermarkNanos, { it.firstObserved.seq }, { it.firstObserved.appNanos }) { _, age -> age > leakNanos }
    }

    /** Long-suspended coroutines, newest first by their suspension; ages end as in [longLived]. */
    fun longSuspended(
        evaluated: List<CoroutineSummary>,
        watermarkNanos: Long?,
        input: HealthInput,
    ): List<ObservedAge> =
        ages(evaluated, watermarkNanos, { it.lastTransition.seq }, { it.lastTransition.appNanos }) { s, age ->
            s.lastTransition.kind == TransitionKind.SUSPENDED && age > input.longSuspendedThresholdNanos
        }

    private fun ages(
        evaluated: List<CoroutineSummary>,
        watermarkNanos: Long?,
        seqOf: (CoroutineSummary) -> Long,
        sinceOf: (CoroutineSummary) -> Long,
        keep: (CoroutineSummary, Long) -> Boolean,
    ): List<ObservedAge> {
        val end = { s: CoroutineSummary -> maxOf(watermarkNanos ?: Long.MIN_VALUE, s.lastObservedNanos) }
        return evaluated
            .filter { !it.isTerminal && keep(it, end(it) - sinceOf(it)) }
            .sortedByDescending(seqOf)
            .map { ObservedAge(it.id, it.name, end(it) - sinceOf(it)) }
    }

    fun originCounts(
        view: SummaryView,
        originOf: (CoroutineSummary) -> CoroutineOrigin,
    ): OriginCounts {
        val byOrigin = view.all.groupingBy(originOf).eachCount()
        return OriginCounts(
            app = byOrigin[CoroutineOrigin.APP] ?: 0,
            vizcore = byOrigin[CoroutineOrigin.VIZCORE] ?: 0,
            unknown = byOrigin[CoroutineOrigin.UNKNOWN] ?: 0,
            untracked = view.untrackedCount,
        )
    }

    fun vizcoreConnection(vizcore: List<CoroutineSummary>): VizcoreConnectionHealth {
        val failed = vizcore.filter { it.failed }.sortedByDescending(FailureIncidents::terminalSeq)
        return VizcoreConnectionHealth(
            captured = vizcore.size,
            failed = failed.size,
            failures = Capped.of(failed.map(FailureIncidents::entry)),
        )
    }

    /** Rounds holding at least one incident, newest anchor first, with the incident kinds they hold. */
    fun problemRounds(
        incidents: List<Incident>,
        view: SummaryView,
    ): List<ProblemRound> {
        val kindsByAnchor = LinkedHashMap<String, MutableSet<IncidentKind>>()
        for (incident in incidents) {
            val anchor =
                when (incident) {
                    is FailedRoundIncident -> incident.anchorId
                    is PossiblyBlockedIncident -> incident.anchorId
                    is UngroupedFailureIncident -> null
                } ?: continue
            kindsByAnchor.getOrPut(anchor) { sortedSetOf() } += incident.kind
        }
        return kindsByAnchor
            .map { (anchor, kinds) ->
                ProblemRound(
                    roundId = RoundAnchoring.roundId(anchor),
                    anchorId = anchor,
                    anchorCreatedNanos = view.get(anchor)?.firstObserved?.appNanos ?: 0L,
                    kinds = kinds.toList(),
                )
            }.sortedWith(
                compareByDescending<ProblemRound> { it.anchorCreatedNanos }
                    .thenByDescending { view.get(it.anchorId)?.firstObserved?.seq ?: 0L },
            )
    }
}
