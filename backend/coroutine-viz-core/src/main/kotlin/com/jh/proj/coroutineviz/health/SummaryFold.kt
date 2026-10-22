package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.VizEvent

/**
 * Replay fold (spec "Modes", Replay): transient summaries from the retained events up to [atSeq], with
 * the same updater as the live table. Ancestry and failures from trimmed events come from the durable
 * summaries in the backend (PR 2), not from here. [events] must be in seq order (as every event store returns
 * them): the fold stops at the first event after [atSeq].
 */
object SummaryFold {
    fun foldTo(
        events: Iterable<VizEvent>,
        atSeq: Long,
    ): SummaryTable {
        val table = SummaryTable()
        for (event in events) {
            if (event.seq > atSeq) break
            table.apply(event)
        }
        return table
    }
}
