package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineOrigin

internal object HealthParts {
    private const val NANOS_PER_MS = 1_000_000L

    /**
     * Long-lived and long-suspended observations (spec "Lifetimes"): an active coroutine's age ends at the
     * latest watermark when there is one ([watermarkNanos], which observes every coroutine of a live app),
     * otherwise at its own last observation, never at another coroutine's activity.
     */
    fun observations(
        evaluated: List<CoroutineSummary>,
        watermarkNanos: Long?,
        input: HealthInput,
    ): Observations {
        val leakNanos = input.leakThresholdMs * NANOS_PER_MS
        val active = evaluated.filter { !it.isTerminal }
        val end = { s: CoroutineSummary -> maxOf(watermarkNanos ?: Long.MIN_VALUE, s.lastObservedNanos) }
        // Newest first (spec C-3): by when the coroutine was first observed / last suspended.
        val longLived = cappedAges(active.filter { end(it) - it.firstObserved.appNanos > leakNanos }, { it.firstObserved.seq }) {
            end(it) - it.firstObserved.appNanos
        }
        val longSuspended =
            cappedAges(
                active.filter {
                    it.lastTransition.kind == TransitionKind.SUSPENDED &&
                        end(it) - it.lastTransition.appNanos > input.longSuspendedThresholdNanos
                },
                { it.lastTransition.seq },
            ) { end(it) - it.lastTransition.appNanos }
        return Observations(longLived, longSuspended)
    }

    /** Newest first by [seqOf]; ages are built only for the entries that survive the cap. */
    private fun cappedAges(
        matching: List<CoroutineSummary>,
        seqOf: (CoroutineSummary) -> Long,
        ageOf: (CoroutineSummary) -> Long,
    ): Capped<ObservedAge> =
        Capped(
            items = matching.sortedByDescending(seqOf).take(HealthThresholds.LIST_CAP).map { ObservedAge(it.id, it.name, ageOf(it)) },
            total = matching.size,
        )

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
