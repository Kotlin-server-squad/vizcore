package com.jh.proj.coroutineviz.persistence

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.persistence.tables.EventsTable
import com.jh.proj.coroutineviz.session.EventStoreInterface
import kotlinx.serialization.PolymorphicSerializer
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * DB-backed [EventStoreInterface] scoped to a single session (the seam is
 * per-session — each [com.jh.proj.coroutineviz.session.VizSession] owns one
 * event store).
 *
 * Payloads are (de)serialized with the EXACT serializer used by the SSE path
 * (`appJson` + `PolymorphicSerializer(VizEvent::class)`), so events read back
 * from the DB are byte-compatible with the live wire shape (Pattern 3, PERS-02).
 *
 * All queries use the Exposed DSL with bound parameters — session id, seq and
 * payload are never string-concatenated into SQL (T-03-01).
 */
class ExposedEventStore(
    private val db: Database,
    private val sessionId: String,
) : EventStoreInterface {
    private val serializer = PolymorphicSerializer(VizEvent::class)

    /**
     * The persistent store is the seq AUTHORITY in DB mode (F6). Every read
     * rebuilds the [com.jh.proj.coroutineviz.session.VizSession] with its own
     * in-memory `seqGenerator` (ADR-015 per-request materialization), so relying
     * on the provisional in-memory seq lets two runs — or concurrent emitters —
     * into the same session id overlap seqs once a prior run's async events have
     * persisted (the in-memory watermark a fresh instance rehydrates from can be
     * stale). Here `seq = MAX(seq)+1` for the session is computed and the row
     * inserted under a per-session lock, so persisted seqs are globally unique and
     * contiguous regardless of how many instances append. The event is re-stamped
     * and re-encoded so the in-memory snapshot/SSE bus (which observe `event.seq`
     * after this call in VizSession.send) and the persisted payload agree.
     */
    override fun record(event: VizEvent) {
        lockFor(sessionId).withLock {
            transaction(db) {
                val maxSeq =
                    EventsTable
                        .selectAll()
                        .where { EventsTable.sessionId eq this@ExposedEventStore.sessionId }
                        .orderBy(EventsTable.seq to SortOrder.DESC)
                        .limit(1)
                        .firstOrNull()
                        ?.get(EventsTable.seq) ?: 0L
                event.seq = maxSeq + 1
                EventsTable.insert {
                    it[sessionId] = this@ExposedEventStore.sessionId
                    it[seq] = event.seq
                    it[kind] = event.kind
                    it[tsNanos] = event.tsNanos
                    it[payload] = appJson.encodeToString(serializer, event)
                }
            }
        }
    }

    override fun all(): List<VizEvent> =
        transaction(db) {
            EventsTable
                .selectAll()
                .where { EventsTable.sessionId eq sessionId }
                .orderBy(EventsTable.seq to SortOrder.ASC)
                .map { decode(it[EventsTable.payload]) }
        }

    override fun since(seq: Long): List<VizEvent> =
        transaction(db) {
            EventsTable
                .selectAll()
                .where { (EventsTable.sessionId eq sessionId) and (EventsTable.seq greater seq) }
                .orderBy(EventsTable.seq to SortOrder.ASC)
                .map { decode(it[EventsTable.payload]) }
        }

    override fun byCoroutine(coroutineId: String): List<VizEvent> = all().filter { it is CoroutineEvent && it.coroutineId == coroutineId }

    override fun count(): Int =
        transaction(db) {
            EventsTable
                .selectAll()
                .where { EventsTable.sessionId eq sessionId }
                .count()
                .toInt()
        }

    override fun clear() {
        transaction(db) {
            EventsTable.deleteWhere { EventsTable.sessionId eq sessionId }
        }
    }

    private fun decode(json: String): VizEvent = appJson.decodeFromString(serializer, json)

    private companion object {
        /**
         * Per-session-id append lock, shared across the (per-request) store
         * instances for a session so the MAX(seq)+1 read and the insert are
         * one atomic step under READ_COMMITTED H2 (no UNIQUE(session_id, seq)
         * constraint exists, and existing dev DBs already hold dup seqs, so the
         * invariant is enforced in-process rather than by the schema).
         */
        private val appendLocks = ConcurrentHashMap<String, ReentrantLock>()

        fun lockFor(sessionId: String): ReentrantLock = appendLocks.computeIfAbsent(sessionId) { ReentrantLock() }
    }
}
