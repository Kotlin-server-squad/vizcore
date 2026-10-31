package com.jh.proj.coroutineviz.health

/**
 * Failed-round and ungrouped-failure incidents (spec "Incidents and the headline count"). [build]'s
 * `evaluated` are the coroutines health is about (vizcore's own excluded by the caller). Counting does not
 * depend on the order terminal events were recorded in (#244).
 */
internal object FailureIncidents {
    /** Returns the incidents in no particular order; the derivation sorts them. */
    fun build(
        evaluated: List<CoroutineSummary>,
        anchoring: RoundAnchoring,
    ): List<Incident> {
        val byAnchor = HashMap<String, MutableList<CoroutineSummary>>()
        val ungrouped = ArrayList<Incident>()
        for (s in evaluated) {
            // Only members that finished abnormally are read by [failedRound].
            if (!s.failed && !s.cancelled) continue
            val anchor = anchoring.anchorOf(s.id)
            when {
                anchor != null -> byAnchor.getOrPut(anchor) { mutableListOf() } += s
                s.failed -> ungrouped += UngroupedFailureIncident(id = "failure:${s.id}", failure = entry(s), sortSeq = terminalSeq(s))
            }
        }
        val rounds = byAnchor.mapNotNull { (anchor, members) -> failedRound(anchor, members) }
        return rounds + ungrouped
    }

    /** The [FailureEntry] for a failed coroutine; details are null when its summary was reduced to a stub. */
    fun entry(s: CoroutineSummary): FailureEntry =
        FailureEntry(
            coroutineId = s.id,
            name = s.name,
            exceptionType = s.failure?.exceptionType,
            message = s.failure?.message,
            failedAtSeq = terminalSeq(s),
            detailsDropped = s.reduced,
        )

    private fun failedRound(
        anchor: String,
        members: List<CoroutineSummary>,
    ): FailedRoundIncident? {
        // Every list is newest first (spec C-3).
        val failures = members.filter { it.failed }.sortedByDescending(::terminalSeq)
        if (failures.isEmpty()) return null
        val firstFailureSeq = terminalSeq(failures.last())
        val cancelledAfter =
            members
                .filter { it.cancelled && !it.failed && terminalSeq(it) > firstFailureSeq }
                .sortedByDescending(::terminalSeq)
                .map { it.id }
        val roundId = RoundAnchoring.roundId(anchor)
        return FailedRoundIncident(
            id = "failed:$roundId",
            roundId = roundId,
            anchorId = anchor,
            failures = Capped.of(failures.map(::entry)),
            cancelledAfter = Capped.of(cancelledAfter),
            firstFailureSeq = firstFailureSeq,
            sortSeq = terminalSeq(failures.first()),
        )
    }

    /**
     * The seq of the coroutine's terminal event. The [CoroutineSummary.lastModifiedSeq] fallback is defensive:
     * FAILED and CANCELLED summaries always have a `terminal`, and stubs keep it.
     */
    fun terminalSeq(s: CoroutineSummary): Long = s.terminal?.seq ?: s.lastModifiedSeq
}
