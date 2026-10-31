package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.auth.ApiKeyStore
import com.jh.proj.coroutineviz.auth.JwtConfig
import com.jh.proj.coroutineviz.auth.Role
import com.jh.proj.coroutineviz.client.OutboundBuffer
import com.jh.proj.coroutineviz.client.SessionGoneException
import com.jh.proj.coroutineviz.client.VizcoreClient
import com.jh.proj.coroutineviz.client.createSession
import com.jh.proj.coroutineviz.client.stream
import com.jh.proj.coroutineviz.session.SessionManager
import com.password4j.Password
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.Application
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import io.ktor.client.HttpClient as KtorClient

/**
 * The SDK's public entry point, [VizcoreClient.start], against the real backend [module] on a
 * real port, for each way the backend can be secured (#260). The SDK sends its `token` as
 * `Authorization: Bearer`; session create and the ingest WebSocket both have to accept it.
 * Ingest is checked end to end: a coroutine the app launches shows up in the backend session.
 */
class SdkBackendAuthTest {
    private val apiKey = "sdk-backend-test-key"
    private val jwtSecret = "sdk-backend-test-jwt-secret"

    private var server: EmbeddedServer<*, *>? = null
    private var client: VizcoreClient? = null
    private val app = Job()

    @BeforeEach
    fun setUp() = SessionManager.clearAll()

    @AfterEach
    fun tearDown() {
        app.cancel()
        client?.stop()
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)
        SessionManager.clearAll()
    }

    private fun startBackend(vararg config: Pair<String, String>): String {
        val started =
            embeddedServer(
                Netty,
                environment = applicationEnvironment { this.config = MapApplicationConfig(*config) },
                configure = {
                    connector {
                        host = "127.0.0.1"
                        port = 0
                    }
                },
                module = Application::module,
            ).start(wait = false)
        server = started
        val port =
            runBlocking {
                started.engine
                    .resolvedConnectors()
                    .first()
                    .port
            }
        return "http://127.0.0.1:$port"
    }

    private fun keysOnly() =
        arrayOf(
            "auth.keys.size" to "1",
            "auth.keys.0.name" to "sdk",
            "auth.keys.0.hash" to ApiKeyStore.sha256Hex(apiKey),
            "auth.keys.0.role" to "runner",
        )

    private fun jwtOnly() =
        arrayOf(
            "auth.users.size" to "1",
            "auth.users.0.username" to "alice",
            "auth.users.0.passwordHash" to Password.hash("pw").withArgon2().result,
            "auth.users.0.role" to "runner",
            "auth.jwt.secret" to jwtSecret,
        )

    private fun jwt(): String = JwtConfig.fromConfig(MapApplicationConfig("auth.jwt.secret" to jwtSecret)).sign("alice", Role.RUNNER).token

    /** Starts the SDK, launches a named app coroutine and waits for the backend session to show it. */
    private fun assertSdkStreams(
        backendUrl: String,
        token: String,
        readHeader: Pair<String, String>?,
    ) {
        val started = VizcoreClient.start(appName = "sdk-auth", backendUrl = backendUrl, token = token)
        client = started
        val label = "sdk-auth-marker"
        CoroutineScope(Dispatchers.Default + app).launch(CoroutineName(label)) { awaitCancellation() }

        // A client the test closes, and one overall deadline that also bounds a stalled request.
        var body = ""
        val arrived =
            KtorClient(CIO).use { http ->
                runBlocking {
                    withTimeoutOrNull(15_000) {
                        while (!body.contains(label)) {
                            body =
                                http
                                    .get("$backendUrl/api/sessions/${started.currentSessionId}/events") {
                                        readHeader?.let { (name, value) -> header(name, value) }
                                    }.bodyAsText()
                            if (!body.contains(label)) delay(50)
                        }
                    }
                }
            }
        if (arrived != null) return
        error("the app's coroutine never reached the backend session; last events body: ${body.take(500)}")
    }

    @Test
    fun `the SDK creates a session and streams into a backend secured with API keys only`() {
        val backendUrl = startBackend(*keysOnly())
        assertSdkStreams(backendUrl, token = apiKey, readHeader = "X-API-Key" to apiKey)
    }

    @Test
    fun `the SDK's session create and ingest are refused when its key is wrong`() {
        val backendUrl = startBackend(*keysOnly())
        // The SDK's own session-create and ingest calls (the ones VizcoreClient.start makes), on a
        // client the test owns and closes.
        KtorClient(CIO) { install(WebSockets) }.use { http ->
            val failure =
                assertFailsWith<IllegalStateException> {
                    runBlocking { createSession(http, backendUrl, "sdk-auth", "wrong-key") }
                }
            assertContains(failure.message.orEmpty(), "HTTP 401")

            val sessionId = runBlocking { createSession(http, backendUrl, "sdk-auth", apiKey) }
            val refused =
                runBlocking { runCatching { stream(http, backendUrl, sessionId, "wrong-key", OutboundBuffer()) } }
                    .exceptionOrNull()
            assertNotNull(refused, "the ingest upgrade with a wrong key must be refused")
            assertFalse(refused is SessionGoneException, "a refused key must not read as a lost session: $refused")
        }
    }

    @Test
    fun `the SDK still works with a JWT against a backend secured with users and JWT`() {
        val backendUrl = startBackend(*jwtOnly())
        val token = jwt()
        assertSdkStreams(backendUrl, token = token, readHeader = "Authorization" to "Bearer $token")
    }

    @Test
    fun `the SDK still works against a backend with auth off`() {
        val backendUrl = startBackend()
        assertSdkStreams(backendUrl, token = "anything", readHeader = null)
    }
}
