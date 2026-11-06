package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.health.TransportStatus
import com.jh.proj.coroutineviz.health.WatermarkObservation
import kotlinx.serialization.Serializable

/**
 * Where [IngestStates] keeps what must survive a backend restart (database mode only; spec C-3 "ingest open" marker, C-4
 * watermark column). Every write sets the state and the marker together.
 */
interface IngestStatePersistence {
    /** The session's stored state, null when the row has none (a row from before Part 5b-2) or does not exist. */
    fun load(sessionId: String): PersistedIngestState?

    /**
     * Writes [state] and the "ingest open" marker [openSince] (the earliest open connection's first seq, null when none
     * is open) in one statement. Throws when it could not.
     */
    fun save(
        sessionId: String,
        state: PersistedIngestState,
        openSince: Long?,
    )
}

/** What the backend knows about a session's ingest, as stored (plan E-13). */
@Serializable
data class PersistedIngestState(
    val watermark: WatermarkObservation? = null,
    val lastIngestEpochMs: Long? = null,
    val transport: TransportStatus = TransportStatus(),
    val firstConnectionSeq: Long? = null,
)

/** The ingest state could not be stored when a connection opened: the socket is refused before any event is accepted. */
class IngestStateNotStoredException(
    cause: Throwable,
) : IllegalStateException("ingest state could not be stored", cause)
