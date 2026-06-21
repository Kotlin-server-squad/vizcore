package com.jh.proj.coroutineviz.persistence

import com.jh.proj.coroutineviz.persistence.tables.EventsTable
import com.jh.proj.coroutineviz.persistence.tables.SessionsTable
import com.jh.proj.coroutineviz.persistence.tables.SharesTable
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * PERS-03 coverage for [DbRetentionPolicy] against H2:
 * - max-age delete removes sessions older than maxAgeDays; recent ones survive.
 * - per-session event trim caps a session's events to maxEventsPerSession.
 * - the active-share guard (Pitfall 6 / ADR-019) preserves a session that has a
 *   non-expired (incl. never-expiring) share, even when it is otherwise expired.
 * Uses an injectable clock (epoch millis) for deterministic age control.
 */
@OptIn(ExperimentalTime::class)
class DbRetentionPolicyTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var db: Database

    // Fixed "now": 2026-06-21T00:00:00Z (arbitrary but stable).
    private val nowMs = 1_781_999_999_000L
    private val dayMs = 86_400_000L

    @BeforeEach
    fun setUp() {
        val name = "test_${UUID.randomUUID().toString().replace("-", "")}"
        dataSource = h2DataSource("jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE")
        db = DatabaseFactory.init(dataSource as DataSource)
    }

    @AfterEach
    fun tearDown() {
        dataSource.close()
    }

    private fun policy(maxAgeDays: Int = 30, maxEvents: Int = 100_000) =
        DbRetentionPolicy(
            db = db,
            maxAgeDays = maxAgeDays,
            maxEventsPerSession = maxEvents,
            clock = { nowMs },
        )

    private fun insertSession(id: String, createdAtMs: Long, tenant: String? = null) {
        transaction(db) {
            SessionsTable.insert {
                it[SessionsTable.id] = id
                it[name] = id
                it[createdAt] = Instant.fromEpochMilliseconds(createdAtMs)
                it[scenario] = null
                it[tenantId] = tenant
                it[metadata] = null
            }
        }
    }

    private fun insertEvent(sessionId: String, seq: Long) {
        transaction(db) {
            EventsTable.insert {
                it[EventsTable.sessionId] = sessionId
                it[EventsTable.seq] = seq
                it[kind] = "test"
                it[tsNanos] = seq
                it[payload] = "{}"
            }
        }
    }

    private fun insertShare(token: String, sessionId: String, expiresAtMs: Long?) {
        transaction(db) {
            SharesTable.insert {
                it[SharesTable.token] = token
                it[SharesTable.sessionId] = sessionId
                it[createdBy] = "tester"
                it[createdAt] = Instant.fromEpochMilliseconds(nowMs - dayMs)
                it[expiresAt] = expiresAtMs?.let { ms -> Instant.fromEpochMilliseconds(ms) }
            }
        }
    }

    private fun sessionExists(id: String): Boolean =
        transaction(db) {
            SessionsTable.selectAll().where { SessionsTable.id eq id }.any()
        }

    private fun eventCount(sessionId: String): Int =
        transaction(db) {
            EventsTable.selectAll().where { EventsTable.sessionId eq sessionId }.count().toInt()
        }

    @Test
    fun `cleanup deletes a session older than maxAgeDays and keeps a recent one`() {
        insertSession("old", createdAtMs = nowMs - 40 * dayMs) // 40d old, > 30d
        insertSession("recent", createdAtMs = nowMs - 5 * dayMs) // 5d old

        val removed = policy(maxAgeDays = 30).cleanup()

        assertFalse(sessionExists("old"), "old session should be deleted")
        assertTrue(sessionExists("recent"), "recent session should be kept")
        assertTrue(removed >= 1)
    }

    @Test
    fun `cleanup trims a session beyond maxEventsPerSession to the cap (oldest first)`() {
        insertSession("s", createdAtMs = nowMs - dayMs)
        // 10 events, seq 1..10; cap at 4 → oldest 6 (seq 1..6) trimmed, seq 7..10 kept.
        for (seq in 1L..10L) insertEvent("s", seq)

        policy(maxEvents = 4).cleanup()

        assertEquals(4, eventCount("s"), "events should be trimmed to the cap")
        // The retained events are the newest (highest seq).
        val seqs =
            transaction(db) {
                EventsTable.selectAll().where { EventsTable.sessionId eq "s" }
                    .map { it[EventsTable.seq] }.sorted()
            }
        assertEquals(listOf(7L, 8L, 9L, 10L), seqs)
    }

    @Test
    fun `cleanup does NOT delete an old session that has a non-expired share`() {
        insertSession("shared-old", createdAtMs = nowMs - 60 * dayMs)
        // Share expires in the future → active → must be preserved.
        insertShare("tok1", "shared-old", expiresAtMs = nowMs + 10 * dayMs)

        policy(maxAgeDays = 30).cleanup()

        assertTrue(sessionExists("shared-old"), "session with an active share must be preserved (Pitfall 6)")
    }

    @Test
    fun `a never-expiring share (null expiry) also preserves an old session`() {
        insertSession("perma-shared-old", createdAtMs = nowMs - 100 * dayMs)
        insertShare("tok2", "perma-shared-old", expiresAtMs = null) // never expires = always active

        policy(maxAgeDays = 30).cleanup()

        assertTrue(sessionExists("perma-shared-old"), "session with a never-expiring share must be preserved")
    }

    @Test
    fun `once the share has expired the next cleanup deletes the old session`() {
        insertSession("was-shared-old", createdAtMs = nowMs - 60 * dayMs)
        insertShare("tok3", "was-shared-old", expiresAtMs = nowMs - dayMs) // already expired

        policy(maxAgeDays = 30).cleanup()

        assertFalse(sessionExists("was-shared-old"), "expired-share old session should be deleted")
    }

    private fun h2DataSource(url: String): HikariDataSource {
        val config =
            HikariConfig().apply {
                jdbcUrl = url
                driverClassName = "org.h2.Driver"
                username = "sa"
                password = ""
                maximumPoolSize = 4
                isAutoCommit = false
            }
        return HikariDataSource(config)
    }
}
