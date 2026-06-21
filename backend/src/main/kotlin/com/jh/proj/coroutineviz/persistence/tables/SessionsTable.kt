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

    override val primaryKey = PrimaryKey(id)
}
