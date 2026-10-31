package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.auth.ApiKeyStore
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.delay
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The full application ([module]) configured with API keys only (no users, no JWT secret),
 * reached the way the SDK reaches it: the key travels as `Authorization: Bearer <key>` on the
 * session-create POST and on the ingest WebSocket upgrade (#260).
 */
class IngestApiKeyBearerTest {
    private val key = "ingest-test-key"

    @BeforeEach
    fun setUp() = SessionManager.clearAll()

    @AfterEach
    fun tearDown() = SessionManager.clearAll()

    private fun ApplicationTestBuilder.keysOnlyBackend() {
        environment {
            config =
                MapApplicationConfig(
                    "auth.keys.size" to "1",
                    "auth.keys.0.name" to "sdk",
                    "auth.keys.0.hash" to ApiKeyStore.sha256Hex(key),
                    "auth.keys.0.role" to "runner",
                )
        }
        application { module() }
    }

    private fun ApplicationTestBuilder.wsClient(): HttpClient = createClient { install(WebSockets) }

    private suspend fun HttpClient.createSession(auth: HttpRequestBuilder.() -> Unit): String {
        val response = post("/api/sessions?name=sdk-app", auth)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val id =
            Json
                .parseToJsonElement(response.bodyAsText())
                .jsonObject["sessionId"]
                ?.jsonPrimitive
                ?.content
        return assertNotNull(id)
    }

    private fun frame(
        sessionId: String,
        label: String,
    ): String {
        val event: VizEvent =
            CoroutineCreated(
                sessionId = sessionId,
                seq = 1L,
                tsNanos = 0L,
                coroutineId = "c-$label",
                jobId = "j-1",
                parentCoroutineId = null,
                scopeId = "scope-1",
                label = label,
            )
        return appJson.encodeToString(PolymorphicSerializer(VizEvent::class), event)
    }

    private suspend fun HttpClient.eventsBody(sessionId: String): String {
        val response = get("/api/sessions/$sessionId/events") { header("X-API-Key", key) }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.bodyAsText()
    }

    private suspend fun HttpClient.awaitEvent(
        sessionId: String,
        label: String,
    ) {
        repeat(100) {
            if (eventsBody(sessionId).contains(label)) return
            delay(20)
        }
        error("event '$label' never reached session $sessionId: ${eventsBody(sessionId)}")
    }

    /** Opens the ingest socket with [auth] on the upgrade; returns the failure, or null when it opened. */
    private suspend fun HttpClient.ingest(
        sessionId: String,
        label: String,
        query: String = "",
        auth: HttpRequestBuilder.() -> Unit,
    ): Throwable? =
        runCatching {
            webSocket("/api/sessions/$sessionId/ingest$query", request = auth) {
                send(Frame.Text(frame(sessionId, label)))
                close()
            }
        }.exceptionOrNull()

    @Test
    fun `session create and ingest succeed with the key as Bearer`() =
        testApplication {
            keysOnlyBackend()
            val client = wsClient()
            val sessionId = client.createSession { header("Authorization", "Bearer $key") }

            val failure = client.ingest(sessionId, "via-bearer") { header("Authorization", "Bearer $key") }
            assertEquals(null, failure, "the ingest upgrade with the key as Bearer must open")
            client.awaitEvent(sessionId, "via-bearer")
        }

    @Test
    fun `ingest with the key in X-API-Key still works`() =
        testApplication {
            keysOnlyBackend()
            val client = wsClient()
            val sessionId = client.createSession { header("X-API-Key", key) }

            val failure = client.ingest(sessionId, "via-header") { header("X-API-Key", key) }
            assertEquals(null, failure, "the ingest upgrade with X-API-Key must open")
            client.awaitEvent(sessionId, "via-header")
        }

    @Test
    fun `a wrong key as Bearer is refused on session create and on the ingest upgrade`() =
        testApplication {
            keysOnlyBackend()
            val client = wsClient()
            val created = client.post("/api/sessions?name=sdk-app") { header("Authorization", "Bearer wrong-key") }
            assertEquals(HttpStatusCode.Unauthorized, created.status)

            val sessionId = client.createSession { header("X-API-Key", key) }
            val failure = client.ingest(sessionId, "wrong-key") { header("Authorization", "Bearer wrong-key") }
            assertTrue(failure != null, "an ingest upgrade with a wrong Bearer key must be refused")
            assertTrue(
                Json.parseToJsonElement(client.eventsBody(sessionId)).jsonArray.isEmpty(),
                "a refused upgrade must not write",
            )
        }

    @Test
    fun `the key is not accepted as a ?token= query parameter on the ingest upgrade`() =
        testApplication {
            keysOnlyBackend()
            val client = wsClient()
            val sessionId = client.createSession { header("X-API-Key", key) }

            val failure = client.ingest(sessionId, "via-query", query = "?token=$key") {}
            assertTrue(failure != null, "an API key in the URL must not open the ingest socket")
            assertTrue(Json.parseToJsonElement(client.eventsBody(sessionId)).jsonArray.isEmpty())
        }
}
