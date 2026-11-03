package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.persistence.DatabaseFactory
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.scenarios.DemoRuns
import com.jh.proj.coroutineviz.session.SessionManager
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Deleting a session frees the slot of a demo still running in it, in database mode too. There a
 * delete only removes the row and the running demo's session object is never closed, so the
 * limiter has to listen for the delete itself.
 */
class DemoRunDatabaseModeTest {
    private lateinit var dataSource: HikariDataSource

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
        SessionManager.useStore(ExposedSessionStore(DatabaseFactory.init(dataSource as DataSource)))
    }

    @AfterEach
    fun tearDown() {
        SessionManager.useStore(null)
        dataSource.close()
    }

    @Test
    fun `deleting a database session frees the slot of a demo still running in it`() =
        testApplication {
            // No time limit, so only the delete can free the slot.
            environment {
                config =
                    MapApplicationConfig(
                        "scenarios.maxConcurrent" to "1",
                        "scenarios.maxRunSeconds" to "0",
                    )
            }
            lateinit var app: Application
            application {
                module()
                app = this
            }
            val forever =
                """{"name":"forever","root":{"id":"root","label":"root",""" +
                    """"actions":[{"type":"delay","params":{"durationMs":"${Long.MAX_VALUE}"}}]}}"""

            val started =
                client.post("/api/scenarios/custom") {
                    contentType(ContentType.Application.Json)
                    setBody(forever)
                }
            assertEquals(HttpStatusCode.OK, started.status, started.bodyAsText())
            val sessionId =
                Json
                    .parseToJsonElement(started.bodyAsText())
                    .jsonObject["sessionId"]!!
                    .jsonPrimitive.content
            val demoRuns: DemoRuns = app.attributes[DemoRunsKey]
            assertEquals(1, demoRuns.active)

            assertEquals(HttpStatusCode.OK, client.delete("/api/sessions/$sessionId").status)

            withTimeout(10.seconds) {
                while (demoRuns.active != 0) delay(20)
            }
        }
}
