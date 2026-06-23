package com.jh.proj.coroutineviz.persistence.tables

import org.jetbrains.exposed.v1.core.Table

/**
 * Exposed table object for the `events` table (see V1__core_schema.sql).
 *
 * `payload` holds the JSON-encoded VizEvent produced by the SSE serializer
 * (PolymorphicSerializer), so round-tripped events are wire-identical.
 */
object EventsTable : Table("events") {
    val id = long("id").autoIncrement()
    val sessionId = varchar("session_id", 128)
    val seq = long("seq")
    val kind = varchar("kind", 128)
    val tsNanos = long("ts_nanos")
    val payload = text("payload")

    override val primaryKey = PrimaryKey(id)
}
