package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.auth.ApiKeyStore
import com.jh.proj.coroutineviz.auth.JwtConfig
import com.jh.proj.coroutineviz.auth.Role
import com.jh.proj.coroutineviz.routes.DemoRunsKey
import com.jh.proj.coroutineviz.routes.demoCatalog
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.parametersOf
import io.ktor.server.application.Application
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.close
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.Socket
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Public demo mode (`publicDemo.enabled`, env `PUBLIC_DEMO_MODE`) over HTTP, on the full [module]:
 * what a visitor may and may not do, what an admin still may, and that nothing changes with the
 * mode off.
 */
class PublicDemoModeTest {
    private val adminKey = "admin-key"
    private val runnerKey = "runner-key"
    private val jwtSecret = "public-demo-test-secret"

    /** A built-in demo that ends within a fraction of a second at the tests' DEMO_TIME_SCALE. */
    private val demoId = "nested"

    private val customScenario =
        """{"name":"mine","root":{"id":"root","label":"root","actions":[{"type":"delay","params":{"durationMs":"1"}}]}}"""

    @BeforeEach
    fun setUp() = SessionManager.clearAll()

    @AfterEach
    fun tearDown() = SessionManager.clearAll()

    private fun ApplicationTestBuilder.backend(vararg settings: Pair<String, String>) {
        environment { config = MapApplicationConfig(*settings) }
        application { module() }
    }

    private fun ApplicationTestBuilder.publicDemo(vararg settings: Pair<String, String>) =
        backend("publicDemo.enabled" to "true", *settings)

    /** An admin key, a runner key and a JWT secret with a seeded user, so both kinds of credential are checked. */
    private val credentials =
        arrayOf(
            "auth.keys.size" to "2",
            "auth.keys.0.name" to "owner",
            "auth.keys.0.hash" to ApiKeyStore.sha256Hex(adminKey),
            "auth.keys.0.role" to "admin",
            "auth.keys.1.name" to "pilot",
            "auth.keys.1.hash" to ApiKeyStore.sha256Hex(runnerKey),
            "auth.keys.1.role" to "runner",
            "auth.users.size" to "1",
            "auth.users.0.username" to "owner",
            "auth.users.0.passwordHash" to "unused",
            "auth.users.0.role" to "admin",
            "auth.jwt.secret" to jwtSecret,
        )

    private fun jwt(role: Role): String =
        JwtConfig.fromConfig(MapApplicationConfig("auth.jwt.secret" to jwtSecret)).sign("owner", role).token

    private fun HttpRequestBuilder.key(value: String) = header("X-API-Key", value)

    private suspend fun HttpResponse.assertRefusedAsPublicDemo(what: String) {
        val body = bodyAsText()
        assertEquals(HttpStatusCode.Forbidden, status, "$what: $body")
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("public-demo", json["code"]?.jsonPrimitive?.content, "$what: $body")
        val message = assertNotNull(json["error"]?.jsonPrimitive?.content, "$what: $body")
        assertTrue(message.startsWith("This is a public demo"), "$what: $message")
    }

    private suspend fun HttpResponse.sessionId(): String {
        val body = bodyAsText()
        assertEquals(HttpStatusCode.Created, status, body)
        return Json
            .parseToJsonElement(body)
            .jsonObject["sessionId"]!!
            .jsonPrimitive.content
    }

    private suspend fun HttpClient.demoSession(): String = post("/api/sessions?name=scenario-$demoId&demoId=$demoId").sessionId()

    private suspend fun HttpClient.sessionIds(request: HttpRequestBuilder.() -> Unit = {}): List<String> =
        Json
            .parseToJsonElement(get("/api/sessions", request).bodyAsText())
            .jsonArray
            .map { it.jsonObject["sessionId"]!!.jsonPrimitive.content }

    /** Opens the ingest socket; the failure, or null when it opened. */
    private suspend fun HttpClient.ingest(
        sessionId: String,
        request: HttpRequestBuilder.() -> Unit = {},
    ): Throwable? = runCatching { webSocket("/api/sessions/$sessionId/ingest", request = request) { close() } }.exceptionOrNull()

    private fun ApplicationTestBuilder.wsClient(): HttpClient = createClient { install(WebSockets) }

    private suspend fun ApplicationTestBuilder.awaitNoDemoRunning() =
        withContext(Dispatchers.Default) {
            withTimeout(15.seconds) {
                while (application.attributes[DemoRunsKey].active != 0) delay(20)
            }
        }

    // -- What a visitor may do ------------------------------------------------------------------

    @Test
    fun `health says whether this is a public demo`() =
        testApplication {
            publicDemo()
            val health = Json.parseToJsonElement(client.get("/api/health").bodyAsText()).jsonObject
            assertEquals("true", health["publicDemo"]?.jsonPrimitive?.content)
        }

    @Test
    fun `a visitor starts a built-in demo, runs it and reads everything about it`() =
        testApplication {
            publicDemo()
            assertEquals(HttpStatusCode.OK, client.get("/api/scenarios").status)
            val sessionId = client.demoSession()

            val run = client.post("/api/scenarios/$demoId?sessionId=$sessionId")
            assertEquals(HttpStatusCode.OK, run.status, run.bodyAsText())
            awaitNoDemoRunning()

            assertEquals(listOf(sessionId), client.sessionIds())
            for (read in listOf("", "/events", "/hierarchy", "/metrics", "/threads", "/health", "/rounds")) {
                val response = client.get("/api/sessions/$sessionId$read")
                assertEquals(HttpStatusCode.OK, response.status, "GET $read: ${response.bodyAsText()}")
            }
            assertTrue(client.get("/api/sessions/$sessionId/events").bodyAsText().contains("CoroutineCreated"))
            assertEquals(HttpStatusCode.OK, client.post("/api/validate/session/$sessionId").status)
            assertEquals(HttpStatusCode.OK, client.get("/api/validate/session/$sessionId").status)
            assertEquals(HttpStatusCode.OK, client.get("/api/sessions/compare?a=$sessionId&b=$sessionId").status)
            withContext(Dispatchers.Default) {
                withTimeout(10.seconds) {
                    client.prepareGet("/api/sessions/$sessionId/stream").execute { assertEquals(HttpStatusCode.OK, it.status) }
                }
            }
        }

    @Test
    fun `a visitor's demo run with no session opens one of its own`() =
        testApplication {
            publicDemo()
            val run = client.post("/api/scenarios/$demoId")
            assertEquals(HttpStatusCode.OK, run.status, run.bodyAsText())
            assertEquals(1, client.sessionIds().size)
        }

    @Test
    fun `the public routes stay outside the visitors' rules`() =
        testApplication {
            publicDemo()
            assertEquals(HttpStatusCode.OK, client.get("/api/live").status)
            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            // The login endpoint answers for itself (no user is configured here), not with the demo's 403.
            val login =
                client.post("/api/auth/token") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"x","password":"y"}""")
                }
            assertNotEquals(HttpStatusCode.Forbidden, login.status, login.bodyAsText())
        }

    // -- What a visitor may not do --------------------------------------------------------------

    @Test
    fun `a visitor cannot open a session for an app`() =
        testApplication {
            publicDemo()
            client.post("/api/sessions?name=my-app").assertRefusedAsPublicDemo("a session with no demo")
            client.post("/api/sessions").assertRefusedAsPublicDemo("a session with no name")
            client.post("/api/sessions?demoId=$demoId&correlation=tok-1").assertRefusedAsPublicDemo("a session the connect wizard waits on")
            assertEquals(emptyList(), client.sessionIds())
            // The route's own answer for a demo it does not know is unchanged.
            assertEquals(HttpStatusCode.BadRequest, client.post("/api/sessions?demoId=no-such-demo").status)
        }

    @Test
    fun `a visitor cannot open an ingest connection`() =
        testApplication {
            publicDemo()
            val ws = wsClient()
            val sessionId = ws.demoSession()

            assertNotNull(ws.ingest(sessionId), "the ingest socket must not open")
            assertEquals("[]", ws.get("/api/sessions/$sessionId/events").bodyAsText())
        }

    /** The test client hides how an upgrade was refused, so this one asks a real server over a plain socket. */
    @Test
    fun `the ingest upgrade is answered with 403 and the reason, however the path is spelled`() {
        val server =
            embeddedServer(
                Netty,
                environment = applicationEnvironment { config = MapApplicationConfig("publicDemo.enabled" to "true") },
                configure = {
                    connector {
                        host = "127.0.0.1"
                        port = 0
                    }
                },
                module = Application::module,
            ).start(wait = false)
        try {
            val port =
                runBlocking {
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                }
            val sessionId = runBlocking { SessionManager.createSession("scenario-$demoId") }.sessionId
            for (path in listOf("ingest", "%69ngest")) {
                val answer =
                    Socket("127.0.0.1", port).use { socket ->
                        socket.soTimeout = 10_000
                        socket.getOutputStream().write(
                            (
                                "GET /api/sessions/$sessionId/$path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                                    "Connection: Upgrade\r\nUpgrade: websocket\r\n" +
                                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
                            ).toByteArray(),
                        )
                        val buffer = ByteArray(4096)
                        String(buffer, 0, socket.getInputStream().read(buffer).coerceAtLeast(0))
                    }
                assertTrue(answer.startsWith("HTTP/1.1 403"), "$path: $answer")
                assertTrue("public-demo" in answer, "$path: $answer")
            }
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)
        }
    }

    @Test
    fun `a visitor cannot delete, reset or change a session`() =
        testApplication {
            publicDemo()
            val sessionId = client.demoSession()

            client.delete("/api/sessions/$sessionId").assertRefusedAsPublicDemo("delete")
            client.delete("/api/sessions/%73essions-no-such").assertRefusedAsPublicDemo("delete of a session that is not there")
            // Reset is delete-then-create and Clear events only empties the page's own list: the server has no route
            // for either, and no other method reaches a session.
            for (response in listOf(
                client.put("/api/sessions/$sessionId"),
                client.patch("/api/sessions/$sessionId"),
                client.delete("/api/sessions/$sessionId/events"),
            )) {
                assertTrue(
                    response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.MethodNotAllowed,
                    "${response.status}",
                )
            }
            assertEquals(listOf(sessionId), client.sessionIds(), "the session must still be there")
        }

    @Test
    fun `a visitor cannot post a scenario of their own`() =
        testApplication {
            publicDemo()
            val custom =
                client.post("/api/scenarios/custom") {
                    contentType(ContentType.Application.Json)
                    setBody(customScenario)
                }
            custom.assertRefusedAsPublicDemo("custom scenario")
            client.post("/api/scenarios/%63ustom").assertRefusedAsPublicDemo("custom scenario, spelled with an escape")
            assertEquals(HttpStatusCode.NotFound, client.post("/api/scenarios/not-in-the-catalog").status)
            assertEquals(emptyList(), client.sessionIds())
        }

    @Test
    fun `a visitor cannot create, list or revoke share links`() =
        testApplication {
            publicDemo()
            val sessionId = client.demoSession()

            // Sharing needs the database, which public demo mode does not run with; a visitor is still told why.
            client.post("/api/sessions/$sessionId/share") { setBody("""{"expiresIn":"1d"}""") }.assertRefusedAsPublicDemo("share")
            client.get("/api/sessions/$sessionId/shares").assertRefusedAsPublicDemo("list shares")
            client.delete("/api/sessions/$sessionId/shares/some-token").assertRefusedAsPublicDemo("revoke")
        }

    @Test
    fun `a visitor cannot call the developer endpoints or the self-starting sync runs`() =
        testApplication {
            publicDemo()
            for (path in listOf(
                "/api/examples/dispatcher-scenario",
                "/api/viz/run-scenario-with-data",
                "/api/tests/run-all",
                "/api/sync/mutex/counter",
                "/api/sync/semaphore/connection-pool",
                "/api/sync/combined/ecommerce",
            )) {
                client.get(path).assertRefusedAsPublicDemo("GET $path")
            }
            assertEquals(emptyList(), client.sessionIds())
            // The list of sync demos is a read like any other.
            assertEquals(HttpStatusCode.OK, client.get("/api/sync/scenarios").status)
        }

    @Test
    fun `a visitor's demo runs once in a session`() =
        testApplication {
            publicDemo()
            val sessionId = client.demoSession()
            assertEquals(HttpStatusCode.OK, client.post("/api/scenarios/$demoId?sessionId=$sessionId").status)
            awaitNoDemoRunning()

            val again = client.post("/api/scenarios/$demoId?sessionId=$sessionId")

            assertEquals(HttpStatusCode.Forbidden, again.status)
            assertTrue(again.bodyAsText().contains("Run again"), again.bodyAsText())
            assertEquals(
                "public-demo",
                Json
                    .parseToJsonElement(again.bodyAsText())
                    .jsonObject["code"]
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    @Test
    fun `every demo in the catalog is open to a visitor, and nothing else under scenarios is`() {
        for (demo in demoCatalog) {
            val path = demo.endpoint.substringBefore('?')
            assertEquals(
                VisitorRequest.DemoRun(sessionId = "s-1"),
                classifyVisitorRequest(HttpMethod.Post, path, parametersOf("sessionId", "s-1")),
                "POST $path",
            )
        }
        for (path in listOf("/api/scenarios/custom", "/api/scenarios", "/api/scenarios/nested/extra", "/api/scenarios/%zz")) {
            val refused = classifyVisitorRequest(HttpMethod.Post, path, Parameters.Empty)
            assertTrue(refused is VisitorRequest.Refused, "POST $path: $refused")
        }
    }

    @Test
    fun `two runs started in one session at the same moment - only one starts`() =
        testApplication {
            publicDemo()
            val sessionId = client.demoSession()

            val answers =
                withContext(Dispatchers.Default) {
                    List(6) { async { client.post("/api/scenarios/$demoId?sessionId=$sessionId").status } }.awaitAll()
                }

            assertEquals(1, answers.count { it == HttpStatusCode.OK }, "$answers")
            assertEquals(5, answers.count { it == HttpStatusCode.Forbidden }, "$answers")
        }

    @Test
    fun `a demo refused because the server is busy can be started again in the same session`() =
        testApplication {
            publicDemo("scenarios.maxConcurrent" to "1")
            val sessionId = client.demoSession()
            // The only demo slot is taken, as by another visitor's demo.
            val slot = assertNotNull(application.attributes[DemoRunsKey].tryStart())

            assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/api/scenarios/$demoId?sessionId=$sessionId").status)
            slot.handlerEnded()

            assertEquals(HttpStatusCode.OK, client.post("/api/scenarios/$demoId?sessionId=$sessionId").status)
        }

    // -- With credentials configured --------------------------------------------------------------

    @Test
    fun `with credentials configured a visitor without one is still let in, and still restricted`() =
        testApplication {
            publicDemo(*credentials)
            val sessionId = client.demoSession()

            assertEquals(listOf(sessionId), client.sessionIds())
            client.delete("/api/sessions/$sessionId").assertRefusedAsPublicDemo("delete")
            client.post("/api/sessions?name=my-app").assertRefusedAsPublicDemo("app session")
        }

    @Test
    fun `an admin may still do what a visitor may not`() =
        testApplication {
            publicDemo(*credentials)
            val ws = wsClient()

            val appSession = ws.post("/api/sessions?name=my-app&correlation=tok-1") { key(adminKey) }.sessionId()
            assertEquals(null, ws.ingest(appSession) { key(adminKey) }, "an admin's ingest upgrade must open")
            val custom =
                ws.post("/api/scenarios/custom") {
                    key(adminKey)
                    contentType(ContentType.Application.Json)
                    setBody(customScenario)
                }
            assertNotEquals(HttpStatusCode.Forbidden, custom.status, custom.bodyAsText())
            assertEquals(HttpStatusCode.OK, ws.get("/api/tests/run-all") { key(adminKey) }.status)
            // The same as a JWT, sent as Bearer: the api-key provider does not know it, the jwt provider does.
            val asUser = ws.delete("/api/sessions/$appSession") { header("Authorization", "Bearer ${jwt(Role.ADMIN)}") }
            assertEquals(HttpStatusCode.OK, asUser.status, asUser.bodyAsText())
            assertTrue(appSession !in ws.sessionIds())
        }

    @Test
    fun `a credential that is not an admin's is restricted like a visitor`() =
        testApplication {
            publicDemo(*credentials)
            val ws = wsClient()
            val sessionId = ws.demoSession()

            ws.delete("/api/sessions/$sessionId") { key(runnerKey) }.assertRefusedAsPublicDemo("delete as runner")
            ws.post("/api/sessions?name=my-app") { key(runnerKey) }.assertRefusedAsPublicDemo("app session as runner")
            ws
                .delete("/api/sessions/$sessionId") { header("Authorization", "Bearer ${jwt(Role.VIEWER)}") }
                .assertRefusedAsPublicDemo("delete as viewer")
            assertNotNull(ws.ingest(sessionId) { key(runnerKey) }, "a runner's ingest socket must not open")
        }

    @Test
    fun `a wrong credential is refused with 401, not let in as a visitor`() =
        testApplication {
            publicDemo(*credentials)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/sessions") { key("wrong") }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/sessions") { header("Authorization", "Bearer wrong") }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/sessions?token=wrong").status)
        }

    // -- The mode off -----------------------------------------------------------------------------

    @Test
    fun `with the mode off and no credentials everything is open as before`() =
        testApplication {
            backend()
            val ws = wsClient()
            val health = Json.parseToJsonElement(ws.get("/api/health").bodyAsText()).jsonObject
            assertEquals("false", health["publicDemo"]?.jsonPrimitive?.content)

            val sessionId = ws.post("/api/sessions?name=my-app&correlation=tok-1").sessionId()
            assertEquals(null, ws.ingest(sessionId), "ingest must open")
            assertEquals(HttpStatusCode.OK, ws.get("/api/tests/run-all").status)
            assertEquals(HttpStatusCode.OK, ws.get("/api/viz/run-scenario-with-data").status)
            assertEquals(HttpStatusCode.OK, ws.get("/api/sync/mutex/counter").status)
            val custom =
                ws.post("/api/scenarios/custom") {
                    contentType(ContentType.Application.Json)
                    setBody(customScenario)
                }
            assertEquals(HttpStatusCode.OK, custom.status, custom.bodyAsText())
            // A second run into the same session is allowed, as it always was.
            assertEquals(HttpStatusCode.OK, ws.post("/api/scenarios/$demoId?sessionId=$sessionId").status)
            assertEquals(HttpStatusCode.OK, ws.post("/api/scenarios/$demoId?sessionId=$sessionId").status)
            assertEquals(HttpStatusCode.OK, ws.delete("/api/sessions/$sessionId").status)
        }

    @Test
    fun `with the mode off and credentials configured a request without one still gets 401`() =
        testApplication {
            backend(*credentials)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/sessions").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/sessions?name=x&demoId=$demoId").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/sessions") { key("wrong") }.status)
            // A runner is not an admin, and with the mode off needs not be.
            val sessionId = client.post("/api/sessions?name=my-app") { key(runnerKey) }.sessionId()
            assertEquals(HttpStatusCode.OK, client.delete("/api/sessions/$sessionId") { key(runnerKey) }.status)
        }

    // -- Sessions do not pile up ------------------------------------------------------------------

    @Test
    fun `visitors' sessions never outnumber SESSION_MAX_COUNT, the oldest goes first`() =
        testApplication {
            // A sweep too rare to run in this test: the bound must hold at the moment a session is added.
            publicDemo("session.maxCount" to "3", "session.checkIntervalMs" to "3600000")
            val created = mutableListOf<String>()
            repeat(5) {
                created += client.demoSession()
                delay(5)
                assertTrue(client.sessionIds().size <= 3, "after ${created.size} sessions: ${client.sessionIds()}")
            }
            assertEquals(created.takeLast(3).sorted(), client.sessionIds().sorted())

            // A run that opens a session of its own is counted too.
            assertEquals(HttpStatusCode.OK, client.post("/api/scenarios/$demoId").status)
            assertEquals(3, client.sessionIds().size)
        }

    @Test
    fun `a request that adds no session removes none`() =
        testApplication {
            publicDemo("session.maxCount" to "2", "session.checkIntervalMs" to "3600000", "scenarios.maxConcurrent" to "1")
            val first = client.demoSession()
            delay(5)
            val second = client.demoSession()
            val slot = assertNotNull(application.attributes[DemoRunsKey].tryStart())

            // Refused for want of a demo slot: it opens no session, so the oldest one stays.
            assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/api/scenarios/$demoId").status)
            assertEquals(HttpStatusCode.BadRequest, client.post("/api/sessions?demoId=no-such-demo").status)

            assertEquals(listOf(first, second).sorted(), client.sessionIds().sorted())
            slot.handlerEnded()
        }

    @Test
    fun `the periodic sweep removes visitors' idle sessions (SESSION_MAX_AGE_MS)`() =
        testApplication {
            publicDemo("session.maxAgeMs" to "200", "session.checkIntervalMs" to "50")
            val sessionId = client.demoSession()
            assertEquals(HttpStatusCode.OK, client.post("/api/scenarios/$demoId?sessionId=$sessionId").status)
            awaitNoDemoRunning()

            withContext(Dispatchers.Default) {
                withTimeout(10.seconds) {
                    while (client.sessionIds().isNotEmpty()) delay(50)
                }
            }
        }

    @Test
    fun `public demo mode refuses to start with the database`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                testApplication {
                    publicDemo("storage.type" to "database")
                    startApplication()
                }
            }
        assertTrue(failure.message.orEmpty().contains("STORAGE_TYPE=memory"), failure.message)
    }
}
