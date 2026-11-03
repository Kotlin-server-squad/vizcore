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

    private suspend fun ApplicationTestBuilder.newSession(id: String): String =
        Json
            .parseToJsonElement(client.post("/api/sessions?name=scenario-$id").bodyAsText())
            .jsonObject["sessionId"]!!
            .jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.startUrl(
        id: String,
        sessionId: String,
    ): String {
        val endpoint = catalog().single { it.text("id") == id }.text("endpoint")
        return endpoint + (if ('?' in endpoint) '&' else '?') + "sessionId=$sessionId"
    }

    private suspend fun awaitNoDemoRunning() =
        withTimeout(15.seconds) {
            while (demoRuns.active != 0) delay(20)
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shortDemoIds")
    fun `each demo runs to its end in the session the dialog opened`(id: String) =
        testApplication {
            catalogApp()
            val sessionId = newSession(id)

            val response = client.post(startUrl(id, sessionId))

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("true", body.text("success"), body.toString())
            assertEquals(sessionId, body.text("sessionId"), "$id must run in the session it was given")
            // Some demos answer at once and keep running; the slot is free once they end.
            awaitNoDemoRunning()
            assertTrue(coroutinesCreated(sessionId) > 0, "$id started no coroutine in session $sessionId")
            assertEquals(listOf(sessionId), SessionManager.listSessions().map { it.sessionId }, "$id must not open a session of its own")
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("longDemoIds")
    fun `each real-life demo starts in the session the dialog opened, and deleting the session stops it`(id: String) =
        testApplication {
            catalogApp()
            val sessionId = newSession(id)
            // These run for 15-35 s and answer only when they end: wait for the first coroutine,
            // then delete the session, which cancels the demo and ends the request.
            coroutineScope {
                val run = async { client.post(startUrl(id, sessionId)) }
                try {
                    withTimeout(15.seconds) {
                        while (coroutinesCreated(sessionId) == 0) delay(20)
                    }
                    assertEquals(listOf(sessionId), SessionManager.listSessions().map { it.sessionId })
                } finally {
                    client.delete("/api/sessions/$sessionId")
                }
                val response = withTimeout(15.seconds) { run.await() }
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                assertEquals(sessionId, Json.parseToJsonElement(response.bodyAsText()).jsonObject.text("sessionId"))
            }
            awaitNoDemoRunning()
        }

    private suspend fun ApplicationTestBuilder.coroutinesCreated(sessionId: String): Int {
        val response = client.get("/api/sessions/$sessionId/events")
        if (response.status != HttpStatusCode.OK) return 0
        return Json
            .parseToJsonElement(response.bodyAsText())
            .jsonArray
            .count { it.jsonObject["type"]?.jsonPrimitive?.content == "CoroutineCreated" }
    }

    companion object {
        private val prettyJson = Json { prettyPrint = true }

        @JvmStatic
        fun shortDemoIds(): List<String> = demoCatalog.filter { it.category != "realistic" }.map { it.id }

        @JvmStatic
        fun longDemoIds(): List<String> = demoCatalog.filter { it.category == "realistic" }.map { it.id }
    }
}
