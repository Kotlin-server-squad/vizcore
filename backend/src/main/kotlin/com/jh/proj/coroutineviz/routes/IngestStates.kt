package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.events.PollWatermark
import com.jh.proj.coroutineviz.health.TransportStatus
import com.jh.proj.coroutineviz.health.WatermarkObservation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * What [IngestStates] knows about one session; the defaults are "nothing ingested". [firstConnectionSeq] is the first
 * seq of the first connection this process saw for the session: events before it arrived through a connection nobody
 * here watched.
 */
data class IngestSnapshot(
    val watermark: WatermarkObservation? = null,
    val lastIngestEpochMs: Long? = null,
    val transport: TransportStatus = TransportStatus(),
    val firstConnectionSeq: Long? = null,
)

/**
 * What this backend process knows about each session's ingest (spec 2026-10-21, C-3 and C-7): the latest poll
 * watermark, when the last event arrived, and the transport status, which is sticky for the rest of the session,
 * across reconnects.
 *
 * A watermark is transport state, not a session event: it is neither stored nor numbered, so one per second does not
 * evict real events from the bounded store. Keyed by session id for the same reason as [IngestConnections] (the
 * database store builds a fresh `VizSession` on every read), and process-local like it. Nothing here is persisted yet:
 * after a restart a database session has no ingest state until its app connects again (Part 5b, spec C-4); the health
 * reads treat what this process did not watch as uncertain.
 */
object IngestStates {
    private const val WATERMARK_BUFFER = 16

    // Internal so tests can check a session holds no state.
    internal val states = ConcurrentHashMap<String, State>()

    /** Guarded by itself. */
    internal class State {
        var watermark: WatermarkObservation? = null
        var lastIngestEpochMs: Long? = null
        var transport = TransportStatus()
        var firstConnectionSeq: Long? = null

        // Only the newest watermark matters to a stream that fell behind.
        val watermarks =
            MutableSharedFlow<WatermarkObservation>(
                extraBufferCapacity = WATERMARK_BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
    }

    /**
     * One ingest socket. Its events get seqs from [firstSeq] on; it closed cleanly only once a final watermark arrived.
     * It writes to the state it was opened with, so a frame that arrives after the session was deleted (and forgotten)
     * cannot bring the session's state back.
     */
    class Connection internal constructor(
        val sessionId: String,
        val firstSeq: Long,
        internal val state: State,
    ) {
        @Volatile
        internal var finalWatermarkReceived = false
    }

    fun connectionOpened(
        sessionId: String,
        firstSeq: Long,
    ): Connection {
        val state = stateOf(sessionId)
        synchronized(state) { if (state.firstConnectionSeq == null) state.firstConnectionSeq = firstSeq }
        return Connection(sessionId, firstSeq, state)
    }

    /** An event frame arrived; with several sockets a frame received earlier can finish later, so the time never moves back. */
    fun eventReceived(
        connection: Connection,
        receivedEpochMs: Long,
    ) {
        val state = connection.state
        synchronized(state) { state.lastIngestEpochMs = maxOf(state.lastIngestEpochMs ?: Long.MIN_VALUE, receivedEpochMs) }
    }

    /**
     * A poll watermark arrived (spec C-7). The newest poll (by app clock) is kept, so a delayed frame from an overlapping
     * socket cannot move it back; every frame's loss facts count. The loss count is cumulative per client, so the largest
     * one is kept; a loss or an uncertain delivery dates from this connection's first seq at the latest.
     */
    fun watermarkReceived(
        connection: Connection,
        watermark: PollWatermark,
        receivedEpochMs: Long,
    ) {
        val state = connection.state
        val observation = WatermarkObservation(watermark.tsNanos, watermark.pollEpochMs, receivedEpochMs)
        synchronized(state) {
            val current = state.watermark
            if (current == null || observation.appNanos >= current.appNanos) state.watermark = observation
            val doubt = watermark.lostEvents > 0 || watermark.deliveryUncertain
            val t = state.transport
            state.updateTransport(
                t.copy(
                    lostEvents = maxOf(t.lostEvents, watermark.lostEvents),
                    deliveryUncertain = t.deliveryUncertain || watermark.deliveryUncertain,
                    sinceSeq = if (doubt) earliest(t.sinceSeq, connection.firstSeq) else t.sinceSeq,
                ),
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
        return synchronized(state) {
            IngestSnapshot(state.watermark, state.lastIngestEpochMs, state.transport, state.firstConnectionSeq)
        }
    }

    /** The session's watermarks as they arrive, for its SSE streams; earlier ones are not replayed. */
    fun watermarks(sessionId: String): Flow<WatermarkObservation> = stateOf(sessionId).watermarks

    /** Drops a deleted session's state; its open connections keep writing only to their own, now unreachable, copy. */
    fun forget(sessionId: String) {
        states.remove(sessionId)
    }

    internal fun clear() {
        states.clear()
        beforeStateAllocatedForTest = null
    }

    /**
     * Test seam: runs just before a session's state is first allocated, so a test can delete the session in the window
     * where the delete's [forget] has already run and the allocation comes after it.
     */
    @Volatile
    internal var beforeStateAllocatedForTest: ((String) -> Unit)? = null

    private fun stateOf(sessionId: String): State {
        states[sessionId]?.let { return it }
        beforeStateAllocatedForTest?.invoke(sessionId)
        return states.computeIfAbsent(sessionId) { State() }
    }
}

/** Delivery is uncertain from [connection]'s first seq on; sticky, like every transport fact. */
private fun markUncertain(connection: IngestStates.Connection) {
    val state = connection.state
    synchronized(state) {
        val t = state.transport
        state.updateTransport(t.copy(deliveryUncertain = true, sinceSeq = earliest(t.sinceSeq, connection.firstSeq)))
    }
}

private fun earliest(
    current: Long?,
    candidate: Long,
): Long = minOf(current ?: Long.MAX_VALUE, candidate)

/** Replaces the transport facts with [next]'s, bumping the revision only when they differ. Caller holds the state's lock. */
private fun IngestStates.State.updateTransport(next: TransportStatus) {
    val current = transport
    val changed =
        next.lostEvents != current.lostEvents ||
            next.deliveryUncertain != current.deliveryUncertain ||
            next.sinceSeq != current.sinceSeq
    if (changed) transport = next.copy(revision = current.revision + 1)
}
