package com.jh.proj.coroutineviz.health

/**
 * Possibly blocked incidents (spec "Problem kinds"): a coroutine whose last observed transition is a
 * start or resume, and whose time from that transition to the watermark (both app clock) is over
 * [HealthThresholds.POSSIBLY_BLOCKED_NANOS]. Only called when possibly blocked is evaluated.
 */
internal object PossiblyBlockedIncidents {
    fun build(
        evaluated: List<CoroutineSummary>,
        anchoring: RoundAnchoring,
        watermarkNanos: Long,
        transitionsFor: (String) -> List<Transition>,
    ): List<PossiblyBlockedIncident> = evaluated.mapNotNull { incident(it, anchoring, watermarkNanos, transitionsFor) }

    private fun incident(
        s: CoroutineSummary,
        anchoring: RoundAnchoring,
        watermarkNanos: Long,
        transitionsFor: (String) -> List<Transition>,
    ): PossiblyBlockedIncident? {
        val run = runStartIfRunning(s) ?: return null
        val runningFor = watermarkNanos - run.appNanos
        return if (runningFor <= HealthThresholds.POSSIBLY_BLOCKED_NANOS) {
            null
        } else {
            incidentFor(s, run, runningFor, anchoring, transitionsFor)
        }
    }

    /** The latest start/resume, when that is the last observed transition of an unfinished coroutine. */
    private fun runStartIfRunning(s: CoroutineSummary): Transition? {
        val running = s.lastTransition.kind == TransitionKind.STARTED || s.lastTransition.kind == TransitionKind.RESUMED
        return s.lastRunStart?.takeIf { running && !s.isTerminal }
    }

    private fun incidentFor(
        s: CoroutineSummary,
        run: Transition,
        runningFor: Long,
        anchoring: RoundAnchoring,
        transitionsFor: (String) -> List<Transition>,
    ): PossiblyBlockedIncident {
        val currentThread = s.lastThread?.takeIf { it.seq > run.seq }
        val clue =
            currentThread?.let { thread ->
                HealthThresholds.EVENT_LOOP_THREAD_PREFIXES.firstOrNull { thread.threadName.startsWith(it) }
            }
        val kept = transitionsFor(s.id).takeLast(HealthThresholds.TRANSITIONS_CAP)
        val anchor = anchoring.anchorOf(s.id)
        return PossiblyBlockedIncident(
            id = "blocked:${s.id}",
            coroutineId = s.id,
            name = s.name,
            roundId = anchor?.let(RoundAnchoring::roundId),
            anchorId = anchor,
            lastThread = currentThread,
            dispatcher = s.dispatcher,
            lastSuspension = s.lastSuspension,
            lastSuspensionProvenance = s.lastSuspensionProvenance,
            lastRunStart = run,
            runningForNanos = runningFor,
            transitions = kept,
            transitionsGap = kept.firstOrNull().let { it?.kind != TransitionKind.CREATED || it.seq != s.firstObserved.seq },
            firstObservedRunning = !s.everSuspended,
            eventLoopClue = clue,
            sortSeq = run.seq,
        )
    }
}
