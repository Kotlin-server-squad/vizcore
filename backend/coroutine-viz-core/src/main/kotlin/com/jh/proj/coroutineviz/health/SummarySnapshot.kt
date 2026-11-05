package com.jh.proj.coroutineviz.health

/**
 * A detached copy of this view, safe to read outside the owner's lock: summaries are immutable values, so copying the
 * map is enough. The backend takes it inside `VizSession.readSummaries` and derives health outside the lock.
 */
fun SummaryView.snapshot(): SummaryView = SummarySnapshot(this)

private class SummarySnapshot(
    source: SummaryView,
) : SummaryView {
    private val byId: Map<String, CoroutineSummary> = source.all.associateByTo(LinkedHashMap(source.all.size)) { it.id }

    override fun get(id: String): CoroutineSummary? = byId[id]

    override val all: Collection<CoroutineSummary> = byId.values
    override val untrackedCount: Long = source.untrackedCount
    override val lastEventSeq: Long = source.lastEventSeq
    override val latestEventAppNanos: Long? = source.latestEventAppNanos
    override val firstEventSeq: Long = source.firstEventSeq
    override val earliestEventAppNanos: Long? = source.earliestEventAppNanos
}
