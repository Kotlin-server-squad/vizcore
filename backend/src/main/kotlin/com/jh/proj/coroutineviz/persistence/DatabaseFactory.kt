package com.jh.proj.coroutineviz.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.ApplicationConfig
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * Builds the JDBC persistence stack for `storage.type=database`:
 * a HikariCP pool, an Exposed [Database] handle, and a Flyway-migrated schema.
 *
 * Connection-pool sizing follows ADR-015 (maximumPoolSize=10, minimumIdle=2,
 * connectionTimeout=30s). The DB password is never logged (T-03-02).
 */
object DatabaseFactory {
    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)

    private const val MAX_POOL_SIZE = 10
    private const val MIN_IDLE = 2
    private const val CONNECTION_TIMEOUT_MS = 30_000L

    /**
     * Initialize the database from `storage.database.*` config:
     * build a Hikari [DataSource], connect Exposed, run Flyway migrations.
     *
     * @return the connected Exposed [Database] handle.
     */
    fun init(config: ApplicationConfig): Database {
        val url = config.property("storage.database.url").getString()
        val driver = config.propertyOrNull("storage.database.driver")?.getString()
        val username = config.propertyOrNull("storage.database.username")?.getString().orEmpty()
        val password = config.propertyOrNull("storage.database.password")?.getString().orEmpty()

        val dataSource = buildDataSource(url, driver, username, password)
        migrate(dataSource)
        // Never log the password; the URL is safe (credentials are passed separately).
        logger.info("Database persistence initialized (url=$url)")
        return Database.connect(dataSource)
    }

    /**
     * Init from an already-built [DataSource]. Useful for tests that supply an
     * H2 in-memory/file pool directly.
     */
    fun init(dataSource: DataSource): Database {
        migrate(dataSource)
        return Database.connect(dataSource)
    }

    private fun buildDataSource(
        url: String,
        driver: String?,
        username: String,
        password: String,
    ): HikariDataSource {
        val hikari =
            HikariConfig().apply {
                jdbcUrl = url
                if (!driver.isNullOrBlank()) driverClassName = driver
                this.username = username
                this.password = password
                maximumPoolSize = MAX_POOL_SIZE
                minimumIdle = MIN_IDLE
                connectionTimeout = CONNECTION_TIMEOUT_MS
                isAutoCommit = false
                validate()
            }
        return HikariDataSource(hikari)
    }

    /**
     * Portable migrations live in `db/migration/common`; one that cannot be portable has a copy per vendor in
     * `db/migration/h2` and `db/migration/postgresql` (see the README in `common/`). V1 is such a migration:
     * PostgreSQL has no `clob`.
     */
    private fun migrate(dataSource: DataSource) {
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/common", "classpath:db/migration/${vendorFolder(dataSource)}")
            .baselineOnMigrate(true)
            .load()
            .migrate()
    }

    /**
     * The vendor migration folder for the connected database. Plain Flyway has no `{vendor}` location placeholder (that
     * is Spring Boot's), so the JDBC metadata decides. Any other database fails here, with its name, rather than
     * half-way through a migration it cannot run.
     */
    internal fun vendorFolder(dataSource: DataSource): String {
        val product = dataSource.connection.use { it.metaData.databaseProductName }
        return when (product) {
            "H2" -> "h2"
            "PostgreSQL" -> "postgresql"
            else -> error("Unsupported database for storage.type=database: $product (supported: H2, PostgreSQL)")
        }
    }
}
