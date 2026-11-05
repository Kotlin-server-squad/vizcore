package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.VizEvent

/** Whether a coroutine's summary at a past seq is known (spec C-3 `STALE`, plan E-1). */
enum class RebuildLevel { EXACT, UNKNOWN }

/** The summaries at a past seq and which of them are not known; see [SummaryBinding.bindAt]. */
class BoundSummaries internal constructor(
    val view: SummaryView,
    /** Coroutines whose summary at the seq is a guess (the view holds the replay fold's value for them). */
    val unknownIds: Set<String>,
) {
    fun levelOf(id: String): RebuildLevel = if (id in unknownIds) RebuildLevel.UNKNOWN else RebuildLevel.EXACT

    companion object {
        /** Every summary of [view] known: the current state, or a kept cut. */
        fun exact(view: SummaryView): BoundSummaries = BoundSummaries(view, emptySet())
    }
}

object SummaryBinding {
    /**
     * The summaries as they were at [atSeq] (plan E-1): [durable] itself when nothing came after it; else the replay fold
     * of [retained] (in seq order) seeded from [durable], in which the durable summary replaces the fold's for every
     * coroutine whose summary did not change after [atSeq] or that had finished by then (after its terminal a summary
     * changes only by reduction to a stub, which says so). A coroutine whose creation is retained keeps its fold value:
     * every event it had up to [atSeq] is retained. Every other coroutine is unknown at [atSeq].
     */
    fun bindAt(
        durable: SummaryView,
        retained: List<VizEvent>,
        atSeq: Long,
    ): BoundSummaries {
        if (atSeq >= durable.lastEventSeq) return BoundSummaries.exact(durable)
        val fold = SummaryFold.foldTo(retained, atSeq, durable)
        val oldestRetained = retained.firstOrNull()?.seq ?: Long.MAX_VALUE
        val overrides = HashMap<String, CoroutineSummary>()
        val unknown = HashSet<String>()
        for (f in fold.all) {
            val d = durable.get(f.id) ?: continue
            when {
                d.lastModifiedSeq <= atSeq -> overrides[f.id] = d
                d.terminal?.let { it.seq <= atSeq } == true -> overrides[f.id] = d
                f.firstObserved.seq >= oldestRetained -> Unit
                else -> unknown += f.id
            }
        }
        return BoundSummaries(if (overrides.isEmpty()) fold else OverlaidView(fold, overrides), unknown)
    }
}

/** [base] with some summaries replaced, in [base]'s order. */
private class OverlaidView(
    private val base: SummaryView,
    private val overrides: Map<String, CoroutineSummary>,
) : SummaryView by base {
    override fun get(id: String): CoroutineSummary? = overrides[id] ?: base.get(id)

    override val all: Collection<CoroutineSummary> = base.all.map { overrides[it.id] ?: it }
}
