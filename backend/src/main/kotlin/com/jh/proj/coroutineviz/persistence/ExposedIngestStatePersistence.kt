package com.jh.proj.coroutineviz.persistence

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.health.TransportStatus
import com.jh.proj.coroutineviz.persistence.tables.SessionsTable
import com.jh.proj.coroutineviz.routes.IngestStatePersistence
import com.jh.proj.coroutineviz.routes.PersistedIngestState
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory

/** The session row's `ingest_state` and `ingest_open_since` columns (V2; spec C-3, C-4; plan E-13). */
class ExposedIngestStatePersistence(
    private val db: Database,
) : IngestStatePersistence {
    private val logger = LoggerFactory.getLogger(ExposedIngestStatePersistence::class.java)

    override fun load(sessionId: String): PersistedIngestState? =
        transaction(db) {
            SessionsTable
                .select(SessionsTable.ingestState)
                .where { SessionsTable.id eq sessionId }
                .firstOrNull()
                ?.get(SessionsTable.ingestState)
                ?.let { decode(sessionId, it) }
        }

    override fun save(
        sessionId: String,
        state: PersistedIngestState,
        openSince: Long?,
    ) {
        transaction(db) {
            SessionsTable.update({ SessionsTable.id eq sessionId }) {
                it[ingestState] = appJson.encodeToString(PersistedIngestState.serializer(), state)
                it[ingestOpenSince] = openSince
            }
        }
    }

    /**
     * At startup: a row whose "ingest open" marker is still set had a connection when the previous process stopped without
     * running its close handler (a crash), so nothing vouches that its events arrived (spec C-3, H-3): its transport
     * becomes delivery-uncertain since that connection's first seq, and the marker is cleared, one statement per row.
     */
    fun sweepOpenMarkers(): Int =
        transaction(db) {
            val open =
                SessionsTable
                    .select(SessionsTable.id, SessionsTable.ingestState, SessionsTable.ingestOpenSince)
                    .where { SessionsTable.ingestOpenSince.isNotNull() }
                    .map { Triple(it[SessionsTable.id], it[SessionsTable.ingestState], it[SessionsTable.ingestOpenSince]!!) }
            for ((id, json, since) in open) {
                val state = json?.let { decode(id, it) } ?: PersistedIngestState()
                val t = state.transport
                val uncertain = t.copy(deliveryUncertain = true, sinceSeq = minOf(t.sinceSeq ?: Long.MAX_VALUE, since))
                val next = if (uncertain == t) t else uncertain.copy(revision = t.revision + 1)
                SessionsTable.update({ SessionsTable.id eq id }) {
                    it[ingestState] = appJson.encodeToString(PersistedIngestState.serializer(), state.copy(transport = next))
                    it[ingestOpenSince] = null
                }
            }
            if (open.isNotEmpty()) logger.warn("{} session(s) had an open ingest connection when the backend stopped", open.size)
            open.size
        }

    /** A stored state that does not decode vouches for nothing: delivery uncertain from the first seq. */
    private fun decode(
        sessionId: String,
        json: String,
    ): PersistedIngestState =
        runCatching { appJson.decodeFromString(PersistedIngestState.serializer(), json) }.getOrElse {
            logger.error("Ingest state of session {} does not decode", sessionId, it)
            PersistedIngestState(transport = TransportStatus(deliveryUncertain = true, sinceSeq = 1))
        }
}
