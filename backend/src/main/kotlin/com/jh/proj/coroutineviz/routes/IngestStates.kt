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
 * database store builds a fresh `VizSession` on every read), and process-local like it. In database mode [persistence]
 * keeps it on the session row (spec C-4, plan E-13): a state is seeded from the row, written under its lock when a
 * connection opens (with the "ingest open" marker), at most every 5 s while frames arrive, and when a
 * connection closes; reads of a session this process holds no state for read the row.
 */
object IngestStates {
    private const val WATERMARK_BUFFER = 16

    /** Database mode: where the state survives a restart. Null in memory mode. */
    @Volatile
    var persistence: IngestStatePersistence? = null

    @Volatile
    internal var clock: () -> Long = System::currentTimeMillis

    // Internal so tests can check a session holds no state.
    internal val states = ConcurrentHashMap<String, State>()

    /** Guarded by itself. */
    internal class State {
        var watermark: WatermarkObservation? = null
        var lastIngestEpochMs: Long? = null
        var transport = TransportStatus()
        var firstConnectionSeq: Long? = null

        /** First seqs of this process's open connections: the "ingest open" marker is the earliest. */
        val openFirstSeqs = mutableListOf<Long>()
        var lastSavedAtMs = Long.MIN_VALUE
        var savedRevision = -1L

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

    /**
     * A socket opened. In database mode the state and the "ingest open" marker are written before any event is accepted;
     * when that fails the connection is not opened ([IngestStateNotStoredException]) and the route refuses the socket.
     */
    fun connectionOpened(
        sessionId: String,
        firstSeq: Long,
        inheritedDoubt: TransportStatus? = null,
    ): Connection {
        val state = stateOf(sessionId)
        synchronized(state) {
            // What no recorded connection vouched for so far (a row from before persisted ingest state, events stored with no
            // connection) becomes part of the stored state before the first write, so a clean close cannot erase it.
            inheritedDoubt?.takeIf { it.deliveryUncertain }?.let { doubt ->
                val t = state.transport
                state.updateTransport(t.copy(deliveryUncertain = true, sinceSeq = earliest(t.sinceSeq, doubt.sinceSeq ?: 1)))
            }
            val firstBefore = state.firstConnectionSeq
            if (state.firstConnectionSeq == null) state.firstConnectionSeq = firstSeq
            state.openFirstSeqs += firstSeq
            persistence?.let { p ->
                runCatching { state.saveTo(p, sessionId) }.onFailure { e ->
                    state.openFirstSeqs.remove(firstSeq)
                    state.firstConnectionSeq = firstBefore
                    throw IngestStateNotStoredException(e)
                }
            }
        }
        return Connection(sessionId, firstSeq, state)
    }

    /** An event frame arrived; with several sockets a frame received earlier can finish later, so the time never moves back. */
    fun eventReceived(
        connection: Connection,
        receivedEpochMs: Long,
    ) {
        val state = connection.state
        synchronized(state) {
            state.lastIngestEpochMs = maxOf(state.lastIngestEpochMs ?: Long.MIN_VALUE, receivedEpochMs)
            state.saveIfDue(connection.sessionId)
        }
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
            state.saveIfDue(connection.sessionId)
        }
        if (watermark.final) connection.finalWatermarkReceived = true
        state.watermarks.tryEmit(observation)
    }

    /** A frame the backend could not read may have been an event: nothing vouches for this connection's events any more. */
    fun frameSkipped(connection: Connection) = markUncertain(connection)

    /** Without a final watermark nothing says every event of the connection arrived (spec C-7, H-3). */
    fun connectionClosed(connection: Connection) {
        if (!connection.finalWatermarkReceived) markUncertain(connection)
        val state = connection.state
        synchronized(state) {
            state.openFirstSeqs.remove(connection.firstSeq)
            // A failed write keeps the old marker: after a restart the session reads uncertain, never wrongly clean.
            persistence?.let { p ->
                runCatching { state.saveTo(p, connection.sessionId) }.onFailure { logWriteFailure(connection.sessionId, it) }
            }
        }
    }

    fun snapshot(sessionId: String): IngestSnapshot {
        val state = states[sessionId] ?: return persistence?.load(sessionId)?.toSnapshot() ?: IngestSnapshot()
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
        persistence = null
        clock = System::currentTimeMillis
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
        return states.computeIfAbsent(sessionId) { State().also { s -> persistence?.load(sessionId)?.let(s::seedFrom) } }
    }
}

/** Delivery is uncertain from [connection]'s first seq on; sticky, like every transport fact. */
private fun markUncertain(connection: IngestStates.Connection) {
    val state = connection.state
    synchronized(state) {
        val t = state.transport
        state.updateTransport(t.copy(deliveryUncertain = true, sinceSeq = earliest(t.sinceSeq, connection.firstSeq)))
        state.saveIfDue(connection.sessionId)
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

private val ingestStateLogger = org.slf4j.LoggerFactory.getLogger(IngestStates::class.java)

private fun logWriteFailure(
    sessionId: String,
    e: Throwable,
) = ingestStateLogger.warn("Ingest state of session {} could not be stored", sessionId, e)

/** Writes this state and its marker. Caller holds the state's lock, so writes land in the order the state changed. */
private fun IngestStates.State.saveTo(
    persistence: IngestStatePersistence,
    sessionId: String,
) {
    persistence.save(
        sessionId,
        PersistedIngestState(watermark, lastIngestEpochMs, transport, firstConnectionSeq),
        openFirstSeqs.minOrNull(),
    )
    lastSavedAtMs = IngestStates.clock()
    savedRevision = transport.revision
}

/**
 * At most every few seconds while frames arrive, but at once when the transport changed (its facts are sticky and rare); a
 * failure is logged (the marker written at open stays set). Caller holds the lock.
 */
private fun IngestStates.State.saveIfDue(sessionId: String) {
    val p = IngestStates.persistence ?: return
    if (transport.revision == savedRevision && IngestStates.clock() - lastSavedAtMs < SAVE_INTERVAL) return
    runCatching { saveTo(p, sessionId) }.onFailure { logWriteFailure(sessionId, it) }
}

private const val SAVE_INTERVAL = 5_000L

private fun IngestStates.State.seedFrom(stored: PersistedIngestState) {
    watermark = stored.watermark
    lastIngestEpochMs = stored.lastIngestEpochMs
    transport = stored.transport
    firstConnectionSeq = stored.firstConnectionSeq
}

private fun PersistedIngestState.toSnapshot() = IngestSnapshot(watermark, lastIngestEpochMs, transport, firstConnectionSeq)
