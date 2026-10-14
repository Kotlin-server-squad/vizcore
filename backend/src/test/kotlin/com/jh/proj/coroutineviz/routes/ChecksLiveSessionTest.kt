package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.persistence.DatabaseFactory
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.session.SessionManager
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Checks on a session fed by a running app: a coroutine without a terminal event is still
 * running while the app's ingest socket is open, and only counts as "never terminated" once
 * the app disconnects. Covered in both storage modes, because the database store builds a
 * fresh session object on every lookup, so the connection state cannot live on that object.
 */
class ChecksLiveSessionTest {
    private var dataSource: HikariDataSource? = null

    @AfterEach
    fun tearDown() {
        SessionManager.useStore(null)
        SessionManager.clearAll()
        dataSource?.close()
    }

    private fun useDatabaseStore() {
        val name = "test_${UUID.randomUUID().toString().replace("-", "")}"
        val ds =
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
        dataSource = ds
        SessionManager.useStore(ExposedSessionStore(DatabaseFactory.init(ds as DataSource)))
    }

    @Test
    fun `memory store - running coroutines are not failures while the app is connected`() = assertChecksFollowConnection()

    @Test
    fun `database store - running coroutines are not failures while the app is connected`() {
        useDatabaseStore()
        assertChecksFollowConnection()
    }

    private fun assertChecksFollowConnection() =
        testApplication {
            application { module() }
            val client = createClient { install(WebSockets) }
            val created = client.post("/api/sessions?name=live-app")
            assertEquals(HttpStatusCode.Created, created.status)
            val sessionId =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["sessionId"]!!
                    .jsonPrimitive.content

            client.webSocket("/api/sessions/$sessionId/ingest") {
                send(frame(created(sessionId)))
                send(frame(started(sessionId)))
                withTimeout(5.seconds) {
                    while (storedEventCount(client, sessionId) < 2) delay(20)
                }

                val live = validate(client, sessionId)
                assertEquals(true, live["appConnected"]!!.jsonPrimitive.boolean)
                assertEquals(1, live["stillRunning"]!!.jsonPrimitive.int)
                assertEquals("Pass", startedHasTerminalType(live))
            }

            // The server releases the socket in a finally block after the client closes.
            val ended =
                withTimeout(5.seconds) {
                    var result = validate(client, sessionId)
                    while (result["appConnected"]!!.jsonPrimitive.boolean) {
                        delay(20)
                        result = validate(client, sessionId)
                    }
                    result
                }
            assertEquals(1, ended["stillRunning"]!!.jsonPrimitive.int)
            assertEquals("Fail", startedHasTerminalType(ended))
        }

    private suspend fun storedEventCount(
        client: HttpClient,
        sessionId: String,
    ): Int =
        Json
            .parseToJsonElement(client.get("/api/sessions/$sessionId/events").bodyAsText())
            .jsonArray.size

    private suspend fun validate(
        client: HttpClient,
        sessionId: String,
    ): JsonObject {
        val response = client.post("/api/validate/session/$sessionId")
        assertEquals(HttpStatusCode.OK, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun startedHasTerminalType(body: JsonObject): String =
        body["results"]!!
            .jsonArray
            .map { it.jsonObject }
            .first { it["ruleName"]?.jsonPrimitive?.content == "StartedHasTerminal" }["type"]!!
            .jsonPrimitive.content

    private fun frame(event: VizEvent) = Frame.Text(appJson.encodeToString(PolymorphicSerializer(VizEvent::class), event))

    private fun created(sessionId: String): VizEvent =
        CoroutineCreated(
            sessionId = sessionId,
            seq = 1L,
            tsNanos = 1_000L,
            coroutineId = "c-1",
            jobId = "j-1",
            parentCoroutineId = null,
            scopeId = "scope-1",
            label = "sse-stream",
        )

    private fun started(sessionId: String): VizEvent =
        CoroutineStarted(
            sessionId = sessionId,
            seq = 2L,
            tsNanos = 2_000L,
            coroutineId = "c-1",
            jobId = "j-1",
            parentCoroutineId = null,
            scopeId = "scope-1",
            label = "sse-stream",
        )
}
