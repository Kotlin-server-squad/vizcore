package com.jh.proj.coroutineviz.persistence

import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.persistence.tables.EventsTable
import com.jh.proj.coroutineviz.persistence.tables.SessionsTable
import com.jh.proj.coroutineviz.persistence.tables.SharesTable
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.DriverManager
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The migrations on a real PostgreSQL, the production target of ADR-015. H2 accepts SQL that PostgreSQL does not
 * (V1 once declared `clob` columns, which a fresh PostgreSQL database rejected), so the H2 tests cannot vouch for it.
 *
 * Runs only when `VIZCORE_PG_URL` is set (CI's backend workflow starts a PostgreSQL service for it). Locally, for example:
 * `docker run --rm -e POSTGRES_PASSWORD=test -p 5432:5432 postgres:16-alpine`, then
 * `VIZCORE_PG_URL=jdbc:postgresql://localhost:5432/postgres VIZCORE_PG_USER=postgres VIZCORE_PG_PASSWORD=test ./gradlew test`.
 * Each test migrates its own fresh schema and drops it afterwards, so any database the user may create schemas in works.
 */
@EnabledIfEnvironmentVariable(named = "VIZCORE_PG_URL", matches = ".+")
class PostgresMigrationTest {
    private val url = System.getenv("VIZCORE_PG_URL")
    private val user = System.getenv("VIZCORE_PG_USER") ?: "postgres"
    private val password = System.getenv("VIZCORE_PG_PASSWORD") ?: ""
    private val schemaName = "vizcore_migration_${UUID.randomUUID().toString().replace("-", "")}"

    private lateinit var dataSource: HikariDataSource
    private lateinit var db: Database

    @BeforeEach
    fun setUp() {
        DriverManager.getConnection(url, user, password).use { it.createStatement().use { s -> s.execute("create schema $schemaName") } }
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    // In the URL, so every connection the driver opens uses the test's schema (Flyway and Exposed alike).
                    jdbcUrl = url + (if ('?' in url) "&" else "?") + "currentSchema=$schemaName"
                    username = user
                    password = this@PostgresMigrationTest.password
                    maximumPoolSize = 2
                    isAutoCommit = false
                },
            )
        db = DatabaseFactory.init(dataSource as DataSource)
    }

    // setUp may fail half-way (a migration that PostgreSQL rejects is the case this test exists for): close what was
    // opened, and drop the schema even then, without hiding setUp's own failure.
    @AfterEach
    fun tearDown() {
        try {
            if (::dataSource.isInitialized) dataSource.close()
        } finally {
            DriverManager.getConnection(url, user, password).use {
                it.createStatement().use { s -> s.execute("drop schema if exists $schemaName cascade") }
            }
        }
    }

    @Test
    fun `every migration applies to a fresh PostgreSQL database`() {
        transaction(db) {
            assertEquals(0, SessionsTable.selectAll().count())
            assertEquals(0, EventsTable.selectAll().count())
            assertEquals(0, SharesTable.selectAll().count())
        }
    }

    @Test
    fun `a session and a large event payload round-trip`() {
        val store = ExposedSessionStore(db)
        val session = runBlocking { store.createSession("pg") }
        val label = "x".repeat(1_000_000)

        session.send(
            CoroutineCreated(
                sessionId = session.sessionId,
                seq = 1,
                tsNanos = 1_000,
                coroutineId = "c1",
                jobId = "j1",
                parentCoroutineId = null,
                scopeId = "s1",
                label = label,
            ),
        )

        val loaded = assertNotNull(store.getSession(session.sessionId))
        assertEquals(
            label,
            loaded.snapshot.coroutines
                .getValue("c1")
                .label,
        )
    }
}
