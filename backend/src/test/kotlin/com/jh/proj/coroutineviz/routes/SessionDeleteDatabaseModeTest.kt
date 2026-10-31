package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.dbRetentionPolicy
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.persistence.DatabaseFactory
import com.jh.proj.coroutineviz.persistence.DbRetentionPolicy
import com.jh.proj.coroutineviz.persistence.ExposedSessionStore
import com.jh.proj.coroutineviz.session.SessionManager
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import javax.sql.DataSource
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Deleting a session through the API must end its open SSE stream and close its open ingest
 * socket with "Session not found" when sessions are stored in a database, as it already does in
 * memory mode. In database mode every read builds a fresh session object and the delete goes
 * straight to the store, so nothing ended either connection: the SPA kept showing the session as
 * connected, and the SDK kept streaming into a session that no longer existed.
 */
class SessionDeleteDatabaseModeTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var db: Database

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
        db = DatabaseFactory.init(dataSource as DataSource)
        SessionManager.useStore(ExposedSessionStore(db))
    }

    @AfterEach
    fun tearDown() {
        SessionManager.useStore(null)
        dataSource.close()
    }

    private fun ApplicationTestBuilder.oneSecondHeartbeats() {
        environment {
            config = MapApplicationConfig("sse.heartbeatSeconds" to "1")
        }
        application { module() }
    }

    private suspend fun createSession(client: HttpClient): String {
        val resp = client.post("/api/sessions?name=db-delete")
        assertEquals(HttpStatusCode.Created, resp.status)
        return Json
            .parseToJsonElement(resp.bodyAsText())
            .jsonObject["sessionId"]!!
            .jsonPrimitive.content
    }

    /** Reads lines until one equals `event: [name]`; returns the next `data:` value, or null at end of stream. */
    private suspend fun ByteReadChannel.dataOfNext(name: String): String? {
        var inEvent = false
        var line = readUTF8Line()
        while (line != null && !(inEvent && line.startsWith("data:"))) {
            if (line.trim() == "event: $name") inEvent = true
            line = readUTF8Line()
        }
        return line?.substringAfter(':')?.trim()
    }

    /**
     * Opens [sessionId]'s stream, waits for a heartbeat (the stream is attached), runs [delete],
     * then requires a final "Session not found" error frame and the end of the stream.
     */
    private suspend fun ApplicationTestBuilder.assertStreamEndsWhenDeleted(
        sessionId: String,
        delete: suspend () -> Unit,
    ) {
        val client = createClient {}
        withContext(Dispatchers.Default) {
            client.prepareGet("/api/sessions/$sessionId/stream").execute { response ->
                assertEquals(HttpStatusCode.OK, response.status)
                val channel = response.bodyAsChannel()
                withTimeout(3_000) { channel.dataOfNext("heartbeat") }

                delete()

                val error = withTimeout(5_000) { channel.dataOfNext("error") }
                assertTrue(error.orEmpty().contains("Session not found"), "got $error")
                // Times out unless the server ends the stream after the error frame.
                val trailing = withTimeout(5_000) { channel.remainingLines() }
                assertTrue(trailing.all { it.isBlank() }, "nothing may follow the error frame: $trailing")
            }
        }
    }

    private suspend fun ByteReadChannel.remainingLines(): List<String> {
        val lines = mutableListOf<String>()
        var line = readUTF8Line()
        while (line != null) {
            lines += line
            line = readUTF8Line()
        }
        return lines
    }

    @Test
    fun `deleting a database session ends its open stream with session not found`() =
        testApplication {
            oneSecondHeartbeats()
            val client = createClient {}
            val sessionId = createSession(client)

            assertStreamEndsWhenDeleted(sessionId) {
                assertEquals(HttpStatusCode.OK, client.delete("/api/sessions/$sessionId").status)
            }
        }

    @Test
    fun `retention deleting a database session ends its open stream`() =
        testApplication {
            oneSecondHeartbeats()
            val sessionId = createSession(createClient {})
            // Every session is past a zero-day age limit, so one cleanup pass deletes it.
            val retention =
                DbRetentionPolicy(
                    db = db,
                    maxAgeDays = 0,
                    clock = { System.currentTimeMillis() + 1_000 },
                    onSessionDeleted = SessionManager::notifySessionDeleted,
                )

            assertStreamEndsWhenDeleted(sessionId) {
                assertEquals(1, retention.cleanup())
            }
        }

    @Test
    fun `a session deleted before its watcher starts is still reported as removed`() =
        testApplication {
            application { module() }
            val sessionId = createSession(createClient {})
            // Resolved first, deleted next, watched last: the gap a request can fall into.
            val session = assertNotNull(SessionManager.getSession(sessionId))
            assertTrue((SessionManager.backingStore() as ExposedSessionStore).deleteSession(sessionId))

            withTimeout(5.seconds) { awaitSessionRemoved(session) }
        }

    @Test
    fun `deleting a database session closes its open ingest socket as session not found`() =
        testApplication {
            application { module() }
            val client = createClient { install(WebSockets) }
            val sessionId = createSession(client)
            val event: VizEvent =
                CoroutineCreated(
                    sessionId = sessionId,
                    seq = 1L,
                    tsNanos = 0L,
                    coroutineId = "c-1",
                    jobId = "j-1",
                    parentCoroutineId = null,
                    scopeId = "scope-1",
                    label = "before-delete",
                )

            client.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(appJson.encodeToString(PolymorphicSerializer(VizEvent::class), event)))
                // Once the frame is stored the socket is attached, so the close below can only come
                // from the delete, not from the handshake's own "Session not found" check.
                withTimeout(5.seconds) {
                    while (Json.parseToJsonElement(client.get("/api/sessions/$sessionId/events").bodyAsText()).jsonArray.isEmpty()) {
                        delay(20)
                    }
                }
                assertEquals(HttpStatusCode.OK, client.delete("/api/sessions/$sessionId").status)

                val reason = withTimeout(5.seconds) { closeReason.await() }
                assertEquals(CloseReason.Codes.VIOLATED_POLICY, reason?.knownReason)
                assertEquals("Session not found", reason?.message)
            }
        }

    @Test
    fun `a socket whose session is deleted while it waits to register closes as session not found`() =
        testApplication {
            application { module() }
            val client = createClient { install(WebSockets) }
            val sessionId = createSession(client)
            val store = SessionManager.backingStore() as ExposedSessionStore
            val decisionStarted = CountDownLatch(1)
            // A retention decision holds registration while it deletes the session (#261).
            val decision =
                thread {
                    IngestConnections.whileNoSocketRegisters {
                        decisionStarted.countDown()
                        val deadline = System.nanoTime() + 5_000_000_000L
                        while (!IngestConnections.registrationsWaiting && System.nanoTime() < deadline) Thread.sleep(1)
                        store.deleteSession(sessionId)
                    }
                }
            decisionStarted.await()
            try {
                client.webSocket("/api/sessions/$sessionId/ingest") {
                    // Queued while the handler waits to register; it must not be stored into a deleted session.
                    send(Frame.Text(appJson.encodeToString(PolymorphicSerializer(VizEvent::class), createdEvent(sessionId))))
                    val reason = withTimeout(10.seconds) { closeReason.await() }
                    assertEquals(CloseReason.Codes.VIOLATED_POLICY, reason?.knownReason)
                    assertEquals("Session not found", reason?.message)
                }
            } finally {
                decision.join(10_000)
            }
            assertFalse(IngestConnections.isConnected(sessionId))
        }

    private fun createdEvent(sessionId: String): VizEvent =
        CoroutineCreated(
            sessionId = sessionId,
            seq = 1L,
            tsNanos = 0L,
            coroutineId = "c-1",
            jobId = "j-1",
            parentCoroutineId = null,
            scopeId = "scope-1",
            label = "queued",
        )

    @Test
    fun `retention keeps a database session while an app streams into it, and deletes it once the app is gone`() =
        testApplication {
            // Every session is past a zero-day age limit, so only the open socket can keep it (#261).
            environment { config = MapApplicationConfig("storage.retention.maxAgeDays" to "0") }
            lateinit var retention: DbRetentionPolicy
            application {
                module()
                // The policy exactly as the application builds it, so the test covers the wiring.
                retention = dbRetentionPolicy(db)
            }
            startApplication()
            val client = createClient { install(WebSockets) }
            val sessionId = createSession(client)
            val event: VizEvent =
                CoroutineCreated(
                    sessionId = sessionId,
                    seq = 1L,
                    tsNanos = 0L,
                    coroutineId = "c-1",
                    jobId = "j-1",
                    parentCoroutineId = null,
                    scopeId = "scope-1",
                    label = "still-streaming",
                )

            client.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(appJson.encodeToString(PolymorphicSerializer(VizEvent::class), event)))
                withTimeout(5.seconds) {
                    while (Json.parseToJsonElement(client.get("/api/sessions/$sessionId/events").bodyAsText()).jsonArray.isEmpty()) {
                        delay(20)
                    }
                }

                assertEquals(0, retention.cleanup(), "a session an app is streaming into must not be deleted")
                assertEquals(HttpStatusCode.OK, client.get("/api/sessions/$sessionId").status)
            }

            // The socket is closed now; the next pass applies the age limit again.
            withTimeout(5.seconds) {
                while (IngestConnections.isConnected(sessionId)) delay(20)
            }
            assertEquals(1, retention.cleanup())
            assertEquals(HttpStatusCode.NotFound, client.get("/api/sessions/$sessionId").status)
        }
}
