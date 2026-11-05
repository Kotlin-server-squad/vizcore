package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.auth.ApiKeyStore
import com.jh.proj.coroutineviz.client.VizcoreClient
import com.jh.proj.coroutineviz.routes.IngestStates
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.engine.cio.CIO
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.ktor.client.HttpClient as KtorClient

/**
 * Triage PR 3 end to end in one JVM: [VizcoreClient] against the real backend [module], secured with an API key (so the
 * SDK's `GET …/health` capability check runs with a runner key). While connected the SDK's watermarks make possibly
 * blocked evaluated and origins reported; after `stop()` the close was clean, so nothing reads "events may have been lost".
 */
class SdkSessionHealthTest {
    private val apiKey = "sdk-health-test-key"
    private var server: EmbeddedServer<*, *>? = null
    private var client: VizcoreClient? = null
    private val app = Job()

    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
        IngestStates.clear()
    }

    @AfterEach
    fun tearDown() {
        app.cancel()
        client?.stop()
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)
        SessionManager.clearAll()
        IngestStates.clear()
    }

    private fun startBackend(): String {
        val started =
            embeddedServer(
                Netty,
                environment =
                    applicationEnvironment {
                        config =
                            MapApplicationConfig(
                                "auth.keys.size" to "1",
                                "auth.keys.0.name" to "sdk",
                                "auth.keys.0.hash" to ApiKeyStore.sha256Hex(apiKey),
                                "auth.keys.0.role" to "runner",
                            )
                    },
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

    private suspend fun KtorClient.json(url: String): JsonObject =
        Json.parseToJsonElement(get(url) { header("X-API-Key", apiKey) }.bodyAsText()).jsonObject

    private fun JsonObject.str(vararg path: String): String? {
        var node: JsonObject = this
        path.dropLast(1).forEach { node = node[it]?.jsonObject ?: return null }
        return node[path.last()]?.jsonPrimitive?.content
    }

    @Test
    fun `possibly blocked is evaluated while connected and the close is clean`() {
        val backendUrl = startBackend()
        val started = VizcoreClient.start(appName = "sdk-health", backendUrl = backendUrl, token = apiKey)
        client = started
        CoroutineScope(Dispatchers.Default + app).launch(CoroutineName("health-app-work")) { awaitCancellation() }
        val id = started.currentSessionId
        val health = "$backendUrl/api/sessions/$id/health"
        KtorClient(CIO).use { http ->
            runBlocking {
                // The exact state asserted below: a watermark has been applied and an app coroutine counted.
                var connected = JsonObject(emptyMap())
                withTimeoutOrNull(15_000) {
                    while (true) {
                        connected = http.json(health)
                        val ready =
                            connected.str("possiblyBlocked", "status") == "EVALUATED" &&
                                // The health was derived with an SDK watermark. (asOf may still name a later event: this JVM is
                                // the backend too, and its own coroutines keep producing events after each poll.)
                                connected["asOfWatermark"] is JsonObject &&
                                connected["counts"]!!.jsonObject["app"]!!.jsonPrimitive.int > 0
                        if (ready) break
                        delay(100)
                    }
                } ?: error("never evaluated with a watermark while connected; last health: $connected")
                assertEquals("REPORTED", connected.str("sessionOrigin"))

                started.stop()
                client = null
                withTimeout(15_000) {
                    while (http.json("$backendUrl/api/sessions/$id")["appConnected"]!!.jsonPrimitive.boolean) delay(50)
                }
                val after = http.json(health)
                val transport = after["transport"]!!.jsonObject
                assertFalse(transport["deliveryUncertain"]!!.jsonPrimitive.boolean, "a clean close: $transport")
                assertEquals(0L, transport["lostEvents"]!!.jsonPrimitive.long)
                assertFalse(after["failuresAtLeast"]!!.jsonPrimitive.boolean)
                assertEquals("EVALUATED", after.str("possiblyBlocked", "status"))
                assertTrue(after["counts"]!!.jsonObject["app"]!!.jsonPrimitive.int > 0)
            }
        }
    }
}
