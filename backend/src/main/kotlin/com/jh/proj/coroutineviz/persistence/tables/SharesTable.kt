package com.jh.proj.coroutineviz.persistence.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.time.ExperimentalTime

/**
 * Exposed table object for the `shares` table (see V1__core_schema.sql).
 *
 * Declared here in Plan 03-01 so the table object exists alongside the V1
 * migration; Plan 03-04 (sharing) uses it for the DB-backed ShareService.
 */
@OptIn(ExperimentalTime::class)
object SharesTable : Table("shares") {
    val token = varchar("token", 64)
    val sessionId = varchar("session_id", 128)
    val createdBy = varchar("created_by", 128)
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at").nullable()
    val permission = varchar("permission", 16).default("READ_ONLY")
    val accessCount = integer("access_count").default(0)
    val lastAccessedAt = timestamp("last_accessed_at").nullable()

    override val primaryKey = PrimaryKey(token)
}
