package com.jh.proj.coroutineviz.persistence

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.health.SummaryLimits
import com.jh.proj.coroutineviz.health.SummaryTable
import com.jh.proj.coroutineviz.health.SummaryTableState
import com.jh.proj.coroutineviz.health.toState
import com.jh.proj.coroutineviz.persistence.tables.EventsTable
import com.jh.proj.coroutineviz.persistence.tables.SessionsTable
import kotlinx.serialization.SerializationException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory

/**
 * Summary checkpoints on the session row (spec C-4, plan E-12): the trim folds the events it is about to delete into the
 * session's checkpoint, so a database session's summaries never lose what the deleted events told. Call inside a
 * transaction.
 */
internal object SummaryCheckpoints {
    private val logger = LoggerFactory.getLogger(SummaryCheckpoints::class.java)

    /** The session's checkpoint, or null (none, or one that does not decode, which is logged and treated as none). */
    fun read(sessionId: String): SummaryTableState? {
        val json =
            SessionsTable
                .select(SessionsTable.summaries)
                .where { SessionsTable.id eq sessionId }
                .firstOrNull()
                ?.get(SessionsTable.summaries) ?: return null
        return runCatching { appJson.decodeFromString(SummaryTableState.serializer(), json) }
            .onFailure { logger.error("Summary checkpoint of session {} does not decode", sessionId, it) }
            .getOrNull()
    }

    /**
     * Folds the session's stored events below [keepFromSeq] into its checkpoint, so they can be deleted. Returns false,
     * and clears any checkpoint, when that is not possible: a session trimmed before checkpoints existed (no checkpoint,
     * oldest stored seq > 1: folding what is left would lose ancestry), or an event that does not decode or apply. The
     * events are deleted next either way, and a checkpoint that does not cover them must not stay, or a read would take
     * the gap for complete history (H-3): the session then reads as trimmed without a checkpoint (503 history-trimmed).
     * [limits] are the ones the store's sessions are built with, so the checkpoint tracks and reduces as their tables do.
     */
    fun foldBelow(
        sessionId: String,
        keepFromSeq: Long,
        limits: SummaryLimits = SummaryLimits.DEFAULT,
    ): Boolean {
        val written =
            try {
                fold(sessionId, keepFromSeq, limits)
            } catch (e: SerializationException) {
                failed(sessionId, e) // a stored payload that does not decode
            } catch (e: IllegalArgumentException) {
                failed(sessionId, e)
            } catch (e: IllegalStateException) {
                failed(sessionId, e) // an event the summary table cannot apply
            }
        if (!written) clear(sessionId)
        return written
    }

    private fun failed(
        sessionId: String,
        e: Exception,
    ): Boolean {
        logger.error("Summary checkpoint of session {} failed; its health becomes unavailable", sessionId, e)
        return false
    }

    private fun fold(
        sessionId: String,
        keepFromSeq: Long,
        limits: SummaryLimits,
    ): Boolean {
        val current = read(sessionId)
        val after = current?.lastEventSeq ?: 0L
        val rows =
            EventsTable
                .selectAll()
                .where { (EventsTable.sessionId eq sessionId) and (EventsTable.seq greater after) and (EventsTable.seq less keepFromSeq) }
                .orderBy(EventsTable.seq to SortOrder.ASC)
                .toList()
        return when {
            rows.isEmpty() -> current != null
            current == null && rows.first()[EventsTable.seq] > 1 -> false
            else -> {
                val table = current?.let { SummaryTable.restore(it, limits) } ?: SummaryTable(limits)
                rows.forEach { table.apply(decodeStoredEvent(it[EventsTable.payload])) }
                write(sessionId, table.toState())
                true
            }
        }
    }

    private fun write(
        sessionId: String,
        state: SummaryTableState,
    ) {
        SessionsTable.update({ SessionsTable.id eq sessionId }) {
            it[summaries] = appJson.encodeToString(SummaryTableState.serializer(), state)
            it[summariesSeq] = state.lastEventSeq
        }
    }

    private fun clear(sessionId: String) {
        SessionsTable.update({ SessionsTable.id eq sessionId }) {
            it[summaries] = null
            it[summariesSeq] = null
        }
    }
}
