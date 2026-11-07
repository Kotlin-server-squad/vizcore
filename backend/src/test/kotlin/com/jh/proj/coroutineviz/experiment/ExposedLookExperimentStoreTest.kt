package com.jh.proj.coroutineviz.experiment

import com.jh.proj.coroutineviz.persistence.DatabaseFactory
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class ExposedLookExperimentStoreTest : LookExperimentStoreContract() {
    private val dataSources = mutableListOf<HikariDataSource>()

    private fun dataSource(name: String): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
                driverClassName = "org.h2.Driver"
                username = "sa"
                maximumPoolSize = 4
                isAutoCommit = false
            },
        ).also { dataSources += it }

    override fun newStore(maxVisitors: Int): LookExperimentStore {
        val name = "look_${UUID.randomUUID().toString().replace("-", "")}"
        return ExposedLookExperimentStore(DatabaseFactory.init(dataSource(name)), maxVisitors)
    }

    @AfterEach
    fun tearDown() {
        dataSources.forEach { it.close() }
    }

    @Test
    fun `the data outlives the store, as it outlives a restart`() {
        val name = "look_${UUID.randomUUID().toString().replace("-", "")}"
        val db = DatabaseFactory.init(dataSource(name))
        val id = "00000000-0000-4000-8000-000000000009"
        ExposedLookExperimentStore(db, 10).putVote(id, "a", 4, "kept", at = 5)

        val again = ExposedLookExperimentStore(DatabaseFactory.init(dataSource(name)), 10)

        assertEquals(listOf(VoteRow(id, "a", 4, "kept", 5)), again.snapshot().votes)
    }
}
