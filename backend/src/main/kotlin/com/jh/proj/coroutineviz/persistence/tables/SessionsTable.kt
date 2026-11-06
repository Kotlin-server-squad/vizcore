package com.jh.proj.coroutineviz.persistence.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.time.ExperimentalTime

/**
 * Exposed table object for the `sessions` table (see V1__core_schema.sql).
 *
 * The Exposed column definitions mirror the Flyway DDL exactly. Flyway owns
 * schema creation; this object is used only for type-safe DSL queries.
 */
@OptIn(ExperimentalTime::class)
object SessionsTable : Table("sessions") {
    val id = varchar("id", 128)
    val name = varchar("name", 256).nullable()
    val createdAt = timestamp("created_at")
    val scenario = varchar("scenario", 256).nullable()
    val tenantId = varchar("tenant_id", 128).nullable()
    val metadata = text("metadata").nullable()

    /** The summary checkpoint the trim wrote (V2, spec C-4): a `SummaryTableState` as JSON, and its last seq. */
    val summaries = text("summaries").nullable()
    val summariesSeq = long("summaries_seq").nullable()

    /** What the backend knows about the session's ingest (V2, spec C-4), and the "ingest open" marker (spec C-3). */
    val ingestState = text("ingest_state").nullable()
    val ingestOpenSince = long("ingest_open_since").nullable()

    /** The last checks run (V2, spec C-5). */
    val checks = text("checks").nullable()

    override val primaryKey = PrimaryKey(id)
}
