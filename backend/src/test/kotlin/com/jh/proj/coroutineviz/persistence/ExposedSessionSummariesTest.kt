package com.jh.proj.coroutineviz.persistence

import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ExposedSessionSummariesTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var store: ExposedSessionStore

    @BeforeEach
    fun setUp() {
        val name = "test_${UUID.randomUUID().toString().replace("-", "")}"
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = "jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
                    driverClassName = "org.h2.Driver"
                    username = "sa"
                    password = ""
                    maximumPoolSize = 4
                    isAutoCommit = false
                },
            )
        val db: Database = DatabaseFactory.init(dataSource as DataSource)
        store = ExposedSessionStore(db)
    }

    @AfterEach
    fun tearDown() = dataSource.close()

    @Test
    fun `a rebuilt database session folds its summaries from the stored events`() {
        val sessionId = runBlocking { store.createSession("db-summaries") }.sessionId
        store.getSession(sessionId)!!.ingest(CoroutineCreated(sessionId, 0, 5, "c1", "j", null, "s", "c1"))

        val rebuilt = assertNotNull(store.getSession(sessionId))

        assertEquals(listOf("c1"), rebuilt.readSummaries { view -> view.all.map { it.id } })
    }

    @Test
    fun `a rebuilt database session keeps the row's creation time`() {
        val created = runBlocking { store.createSession("db-created") }
        Thread.sleep(5)

        val first = assertNotNull(store.getSession(created.sessionId)).createdAtMs
        Thread.sleep(5)
        val second = assertNotNull(store.getSession(created.sessionId)).createdAtMs

        assertEquals(created.createdAtMs, first)
        assertEquals(first, second)
    }
}
