package com.jh.proj.coroutineviz.health

import kotlinx.serialization.Serializable

/**
 * A checkpoint of a [SummaryTable] (spec C-4): restored by [SummaryTable.restore], it goes on exactly as the table it was
 * taken from. [summaries] are in first-observation order (the table's own).
 */
@Serializable
data class SummaryTableState(
    val version: Int = 1,
    val summaries: List<CoroutineSummary>,
    val untrackedCount: Long,
    val lastEventSeq: Long,
    val latestEventAppNanos: Long?,
    val firstEventSeq: Long,
    val earliestEventAppNanos: Long?,
)

/** This view as a checkpoint (spec C-4). */
fun SummaryView.toState(): SummaryTableState =
    SummaryTableState(
        summaries = all.toList(),
        untrackedCount = untrackedCount,
        lastEventSeq = lastEventSeq,
        latestEventAppNanos = latestEventAppNanos,
        firstEventSeq = firstEventSeq,
        earliestEventAppNanos = earliestEventAppNanos,
    )
