package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.scenarios.DemoRuns
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `GET /api/scenarios` is the one list the "New demo session" dialog offers. It must name every
 * demo the server can run into a session, grouped as the dialog shows them, and each entry must
 * actually start.
 */
class DemoCatalogTest {
    @AfterEach
    fun tearDown() = SessionManager.clearAll()

    private lateinit var app: Application

    private fun ApplicationTestBuilder.catalogApp() =
        application {
            module()
            app = this
        }

    private val demoRuns: DemoRuns get() = app.attributes[DemoRunsKey]

    private suspend fun ApplicationTestBuilder.catalog(): List<JsonObject> =
        Json
            .parseToJsonElement(client.get("/api/scenarios").bodyAsText())
            .jsonObject["scenarios"]!!
            .jsonArray
            .map { it.jsonObject }

    private fun JsonObject.text(key: String): String = this[key]!!.jsonPrimitive.content

    @Test
    fun `the catalog lists all 32 demos, group by group in the order the dialog shows them`() =
        testApplication {
            catalogApp()
            val entries = catalog()

            assertEquals(32, entries.size, entries.map { it.text("id") }.toString())
            assertEquals(
                listOf(
                    "real-life" to 3,
                    "basics" to 7,
                    "channels" to 3,
                    "flow" to 4,
                    "patterns" to 5,
                    "sync-mutex" to 4,
                    "sync-semaphore" to 5,
                    "combined" to 1,
                ),
                entries.map { it.text("group") }.runs(),
                "each group once, in this order",
            )
            assertEquals(entries.size, entries.map { it.text("id") }.toSet().size, "ids must be unique")
            entries.forEach { entry ->
                listOf("name", "description", "groupLabel", "duration", "category").forEach { key ->
                    assertTrue(entry.text(key).isNotBlank(), "${entry.text("id")} needs a $key")
                }
                assertEquals("POST", entry.text("method"), entry.text("id"))
                assertTrue(
                    entry.text("endpoint").startsWith("/api/scenarios/${entry.text("id")}"),
                    "${entry.text("id")} must start at /api/scenarios/<id>, got ${entry.text("endpoint")}",
                )
            }
        }

    /**
     * The SPA's tests render the dialog from a copy of this catalog, so they cover the real 32
     * demos; this keeps the copy equal to what the server serves. Regenerate it with
     * `UPDATE_DEMO_CATALOG_FIXTURE=true ./gradlew test --tests '*DemoCatalogTest*'`.
     */
    @Test
    fun `the SPA's copy of the catalog matches the one the server serves`() =
        testApplication {
            catalogApp()
            val served = client.get("/api/scenarios").bodyAsText()
            val fixture = File("../frontend/src/test/demo-catalog.json")
            if (System.getenv("UPDATE_DEMO_CATALOG_FIXTURE") == "true") {
                fixture.writeText(prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(served)) + "\n")
            }
            assertTrue(fixture.exists(), "missing ${fixture.absolutePath}")
            assertEquals(
                Json.parseToJsonElement(served),
                Json.parseToJsonElement(fixture.readText()),
                "frontend/src/test/demo-catalog.json is out of date; regenerate it (see this test's comment)",
            )
        }

    /** Consecutive equal values collapsed into (value, count). */
    private fun List<String>.runs(): List<Pair<String, Int>> =
        fold(mutableListOf()) { runs, value ->
            if (runs.lastOrNull()?.first == value) {
                runs[runs.lastIndex] = value to runs.last().second + 1
            } else {
                runs += value to 1
            }
            runs
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
    fun `every demo that runs into a session is in the catalog, and every entry is such a demo`() =
        testApplication {
            catalogApp()
            startApplication()
            val routes =
                app
                    .plugin(RoutingRoot)
                    .getAllRoutes()
                    .mapNotNull { it.endpoint() }
                    .toSet()
            // "Build your own" takes a scenario definition in its body; the dialog does not list it.
            val sessionDemos =
                routes
                    .filter { (method, path) -> method == HttpMethod.Post && path.startsWith("/api/scenarios/") }
                    .map { it.second }
                    .toSet() - "/api/scenarios/custom"

            val listed = catalog().map { it.text("endpoint").substringBefore('?') }.toSet()

            assertEquals(sessionDemos.sorted(), listed.sorted())
            // The older GET /api/sync/... routes start each Mutex and Semaphore demo in a session of
            // their own; the dialog reaches the same demos through their session-aware aliases.
            val syncDemos = routes.filter { (method, path) -> method == HttpMethod.Get && path.startsWith("/api/sync/") }
            val unreachable =
                syncDemos
                    .map { it.second }
                    .filter { it != "/api/sync/scenarios" }
                    .filterNot { "/api/scenarios/sync/" + it.removePrefix("/api/sync/") in listed }
            assertEquals(emptyList(), unreachable, "sync demos the dialog cannot start")
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("demoIds")
    fun `each demo in the catalog starts in the session the dialog opened and records its coroutines`(id: String) =
        testApplication {
            catalogApp()
            val entry = catalog().single { it.text("id") == id }
            val sessionId =
                Json
                    .parseToJsonElement(client.post("/api/sessions?name=scenario-$id").bodyAsText())
                    .jsonObject["sessionId"]!!
                    .jsonPrimitive.content
            val endpoint = entry.text("endpoint")
            val separator = if ('?' in endpoint) '&' else '?'

            // The realistic demos answer only when they end, after up to 35 s: start the run, wait
            // for its first coroutine in the session, then end it by deleting the session.
            coroutineScope {
                val run = async { client.post("$endpoint${separator}sessionId=$sessionId") }
                withTimeout(15.seconds) {
                    while (eventCount(sessionId) == 0 && !run.isCompleted) delay(20)
                }
                if (run.isCompleted) {
                    assertEquals(HttpStatusCode.OK, run.await().status, run.await().bodyAsText())
                }
                assertTrue(eventCount(sessionId) > 0, "$id recorded nothing in session $sessionId")

                client.delete("/api/sessions/$sessionId")
                val status = withTimeout(15.seconds) { run.await().status }
                assertTrue(
                    status !in setOf(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed, HttpStatusCode.ServiceUnavailable),
                    "$id answered $status",
                )
                withTimeout(15.seconds) {
                    while (demoRuns.active != 0) delay(20)
                }
            }
        }

    private suspend fun ApplicationTestBuilder.eventCount(sessionId: String): Int {
        val response = client.get("/api/sessions/$sessionId/events")
        if (response.status != HttpStatusCode.OK) return 0
        return Json.parseToJsonElement(response.bodyAsText()).jsonArray.size
    }

    companion object {
        private val prettyJson = Json { prettyPrint = true }

        @JvmStatic
        fun demoIds(): List<String> = demoCatalog.map { it.id }
    }
}
