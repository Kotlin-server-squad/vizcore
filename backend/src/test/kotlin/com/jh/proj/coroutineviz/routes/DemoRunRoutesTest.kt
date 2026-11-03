package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.scenarios.DemoRuns
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.plugin
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * MAX_CONCURRENT_DEMOS caps how many demos run on the server at once, across all clients: a
 * public instance must not let visitors start an unbounded number of real coroutine workloads.
 */
class DemoRunRoutesTest {
    @AfterEach
    fun tearDown() = SessionManager.clearAll()

    private lateinit var app: Application

    private fun ApplicationTestBuilder.demoApp(maxConcurrent: Int) {
        environment { config = MapApplicationConfig("scenarios.maxConcurrent" to "$maxConcurrent") }
        application {
            module()
            app = this
        }
    }

    private val demoRuns: DemoRuns get() = app.attributes[DemoRunsKey]

    private suspend fun HttpResponse.error(): String =
        Json
            .parseToJsonElement(bodyAsText())
            .jsonObject["error"]!!
            .jsonPrimitive.content

    private suspend fun HttpResponse.sessionId(): String =
        Json
            .parseToJsonElement(bodyAsText())
            .jsonObject["sessionId"]!!
            .jsonPrimitive.content

    @Test
    fun `a demo started while every slot is taken is refused at once with Retry-After`() =
        testApplication {
            demoApp(maxConcurrent = 1)
            startApplication()
            assertNotNull(demoRuns.tryStart())
            val sessionsBefore = SessionManager.listSessions().size

            val response = client.post("/api/scenarios/nested")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertNotNull(response.headers[HttpHeaders.RetryAfter]?.toIntOrNull(), "Retry-After must be a number of seconds")
            assertTrue(response.error().contains("too many demos running"), "got ${response.error()}")
            assertEquals(sessionsBefore, SessionManager.listSessions().size, "a refused demo must not create a session")
        }

    @Test
    fun `a demo that answers at once keeps its slot until its coroutines end`() =
        testApplication {
            demoApp(maxConcurrent = 1)

            // The nested scenario answers right away and runs for about 5 s on the session's scope.
            val first = client.post("/api/scenarios/nested")
            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/api/scenarios/parallel").status)

            // Deleting the session cancels its coroutines, which frees the slot.
            assertEquals(HttpStatusCode.OK, client.delete("/api/sessions/${first.sessionId()}").status)
            withTimeout(5.seconds) {
                while (demoRuns.active != 0) delay(20)
            }
            assertEquals(HttpStatusCode.OK, client.post("/api/scenarios/flow/simple").status)
        }

    @Test
    fun `lists, sessions, health and the live stream are never limited`() =
        testApplication {
            demoApp(maxConcurrent = 1)
            startApplication()
            assertNotNull(demoRuns.tryStart())

            listOf(
                "/api/scenarios",
                "/api/scenarios/flow",
                "/api/scenarios/patterns",
                "/api/sync/scenarios",
                "/api/sessions",
                "/health",
            ).forEach { path ->
                assertEquals(HttpStatusCode.OK, client.get(path).status, "$path must not count as a demo")
            }
            assertEquals(HttpStatusCode.Created, client.post("/api/sessions?name=not-a-demo").status)
        }

    @Test
    fun `demos run one after another are never refused`() =
        testApplication {
            demoApp(maxConcurrent = 1)
            // Each Flow demo ends before it answers, so it gives its slot back before the next.
            repeat(3) {
                assertEquals(HttpStatusCode.OK, client.post("/api/scenarios/flow/simple").status)
            }
        }

    /** Method and path of every endpoint; null for routes with a path parameter. */
    private fun RoutingNode.endpoint(): Pair<HttpMethod, String>? {
        val method = (selector as? HttpMethodRouteSelector)?.method
        val ancestors = generateSequence(parent) { it.parent }.map { it.selector }.toList().reversed()
        val hasParameter = ancestors.any { it.toString().startsWith("{") }
        val path = "/" + ancestors.filterIsInstance<PathSegmentConstantRouteSelector>().joinToString("/") { it.value }
        return if (method == null || hasParameter) null else method to path
    }

    @Test
    fun `every demo endpoint is limited, so a new one cannot escape the limit`() =
        testApplication {
            demoApp(maxConcurrent = 1)
            startApplication()
            assertNotNull(demoRuns.tryStart())
            // Lists of demos, and endpoints under the demo paths that start none.
            val notDemos =
                setOf(
                    "GET /api/scenarios",
                    "GET /api/scenarios/flow",
                    "GET /api/scenarios/patterns",
                    "GET /api/sync/scenarios",
                    "GET /api/tests/run-all",
                )
            val demoPaths = listOf("/api/scenarios", "/api/sync", "/api/viz", "/api/examples", "/api/tests")

            val endpoints =
                app
                    .plugin(RoutingRoot)
                    .getAllRoutes()
                    .mapNotNull { it.endpoint() }
                    .filter { (_, path) -> demoPaths.any { path == it || path.startsWith("$it/") } }
                    .distinct()
            assertTrue(endpoints.size >= 40, "found only ${endpoints.size} demo endpoints: $endpoints")

            endpoints.forEach { (method, path) ->
                val name = "${method.value} $path"
                val status = client.request(path) { this.method = method }.status
                if (name in notDemos) {
                    assertEquals(HttpStatusCode.OK, status, "$name lists demos and must not be limited")
                } else {
                    assertEquals(HttpStatusCode.ServiceUnavailable, status, "$name runs a demo and must be limited")
                }
            }
        }
}
