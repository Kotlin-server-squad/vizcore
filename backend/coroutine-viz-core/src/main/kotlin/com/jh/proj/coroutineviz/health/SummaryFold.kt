package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.VizEvent

/**
 * Replay fold (spec "Modes", Replay): transient summaries from the retained events up to [atSeq], with the same updater
 * as the live table. With [durable] (the session's durable summaries), coroutines whose creation event was trimmed are
 * seeded from it first (see [SummaryTable.seed]), so their ancestry and failures known at the cursor are kept. [events]
 * must be in seq order (as every event store returns them): the fold stops at the first event after [atSeq].
 */
object SummaryFold {
    fun foldTo(
        events: Iterable<VizEvent>,
        atSeq: Long,
    ): SummaryView = foldTo(events, atSeq, durable = null)

    fun foldTo(
        events: Iterable<VizEvent>,
        atSeq: Long,
        durable: SummaryView?,
    ): SummaryView = foldTo(events, atSeq, durable, SummaryLimits.DEFAULT)

    /** The fold with [limits]: the session's own, so the replay tracks and reduces exactly as its live table did. */
    fun foldTo(
        events: Iterable<VizEvent>,
        atSeq: Long,
        durable: SummaryView?,
        limits: SummaryLimits,
    ): SummaryView {
        val table = SummaryTable(limits)
        if (durable != null) table.seed(durable, retainedFrom = events.firstOrNull()?.seq ?: Long.MAX_VALUE, atSeq = atSeq)
        for (event in events) {
            if (event.seq > atSeq) break
            table.apply(event)
        }
        return table
    }
}
