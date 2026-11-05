package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.events.PollWatermark
import com.jh.proj.coroutineviz.health.TransportStatus
import com.jh.proj.coroutineviz.health.WatermarkObservation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.ConcurrentHashMap

/** What [IngestStates] knows about one session; the defaults are "nothing ingested". */
data class IngestSnapshot(
    val watermark: WatermarkObservation? = null,
    val lastIngestEpochMs: Long? = null,
    val transport: TransportStatus = TransportStatus(),
)

/**
 * What this backend process knows about each session's ingest (spec 2026-10-21, C-3 and C-7): the latest poll
 * watermark, when the last event arrived, and the transport status, which is sticky for the rest of the session,
 * across reconnects.
 *
 * A watermark is transport state, not a session event: it is neither stored nor numbered, so one per second does not
 * evict real events from the bounded store. Keyed by session id for the same reason as [IngestConnections] (the
 * database store builds a fresh `VizSession` on every read), and process-local like it. Nothing here is persisted yet:
 * after a restart a database session has no ingest state until its app connects again (Part 5b, spec C-4).
 */
object IngestStates {
    private const val WATERMARK_BUFFER = 16

    private val states = ConcurrentHashMap<String, State>()

    private class State {
        var watermark: WatermarkObservation? = null
        var lastIngestEpochMs: Long? = null
        var transport = TransportStatus()

        // Only the newest watermark matters to a stream that fell behind.
        val watermarks =
            MutableSharedFlow<WatermarkObservation>(
                extraBufferCapacity = WATERMARK_BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
    }

    /** One ingest socket. Its events get seqs from [firstSeq] on; it closed cleanly only once a final watermark arrived. */
    class Connection internal constructor(
        val sessionId: String,
        val firstSeq: Long,
    ) {
        @Volatile
        internal var finalWatermarkReceived = false
    }

    fun connectionOpened(
        sessionId: String,
        firstSeq: Long,
    ): Connection {
        stateOf(sessionId)
        return Connection(sessionId, firstSeq)
    }

    /** An event frame arrived; with several sockets a frame received earlier can finish later, so the time never moves back. */
    fun eventReceived(
        sessionId: String,
        receivedEpochMs: Long,
    ) {
        val state = stateOf(sessionId)
        synchronized(state) { state.lastIngestEpochMs = maxOf(state.lastIngestEpochMs ?: Long.MIN_VALUE, receivedEpochMs) }
    }

    /**
     * A poll watermark arrived (spec C-7). Its loss count is cumulative per client, so the largest one is kept; a loss or
     * an uncertain delivery dates from this connection's first seq at the latest.
     */
    fun watermarkReceived(
        connection: Connection,
        watermark: PollWatermark,
        receivedEpochMs: Long,
    ) {
        val state = stateOf(connection.sessionId)
        val observation = WatermarkObservation(watermark.tsNanos, watermark.pollEpochMs, receivedEpochMs)
        synchronized(state) {
            state.watermark = observation
            val doubt = watermark.lostEvents > 0 || watermark.deliveryUncertain
            val t = state.transport
            state.transport =
                t.copy(
                    lostEvents = maxOf(t.lostEvents, watermark.lostEvents),
                    deliveryUncertain = t.deliveryUncertain || watermark.deliveryUncertain,
                    sinceSeq = if (doubt) earliest(t.sinceSeq, connection.firstSeq) else t.sinceSeq,
                )
        }
        if (watermark.final) connection.finalWatermarkReceived = true
        state.watermarks.tryEmit(observation)
    }

    /** A frame the backend could not read may have been an event: nothing vouches for this connection's events any more. */
    fun frameSkipped(connection: Connection) = markUncertain(connection)

    /** Without a final watermark nothing says every event of the connection arrived (spec C-7, H-3). */
    fun connectionClosed(connection: Connection) {
        if (!connection.finalWatermarkReceived) markUncertain(connection)
    }

    fun snapshot(sessionId: String): IngestSnapshot {
        val state = states[sessionId] ?: return IngestSnapshot()
        return synchronized(state) { IngestSnapshot(state.watermark, state.lastIngestEpochMs, state.transport) }
    }

    /** The session's watermarks as they arrive, for its SSE streams; earlier ones are not replayed. */
    fun watermarks(sessionId: String): Flow<WatermarkObservation> = stateOf(sessionId).watermarks

    /** Drops a deleted session's state. */
    fun forget(sessionId: String) {
        states.remove(sessionId)
    }

    internal fun clear() = states.clear()

    private fun markUncertain(connection: Connection) {
        // A deleted (forgotten) session must not get state back from a socket that closes late.
        val state = states[connection.sessionId] ?: return
        synchronized(state) {
            val t = state.transport
            state.transport = t.copy(deliveryUncertain = true, sinceSeq = earliest(t.sinceSeq, connection.firstSeq))
        }
    }

    private fun stateOf(sessionId: String): State = states.computeIfAbsent(sessionId) { State() }

    private fun earliest(
        current: Long?,
        candidate: Long,
    ): Long = minOf(current ?: Long.MAX_VALUE, candidate)
}
