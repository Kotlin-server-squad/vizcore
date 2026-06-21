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

    override fun record(event: VizEvent) {
        val json = appJson.encodeToString(serializer, event)
        transaction(db) {
            EventsTable.insert {
                it[sessionId] = this@ExposedEventStore.sessionId
                it[seq] = event.seq
                it[kind] = event.kind
                it[tsNanos] = event.tsNanos
                it[payload] = json
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
}
