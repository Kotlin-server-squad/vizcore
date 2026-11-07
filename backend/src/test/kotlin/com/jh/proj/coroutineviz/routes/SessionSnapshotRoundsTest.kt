package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.routes.HealthEvents.completed
import com.jh.proj.coroutineviz.routes.HealthEvents.created
import com.jh.proj.coroutineviz.routes.HealthEvents.sec
import com.jh.proj.coroutineviz.routes.HealthEvents.suspended
import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `GET /api/sessions/{id}` nodes carry the server's round and effective origin (dock spec S-12, Part 6 plan): the page
 * shows one round at a time and leaves vizcore's own coroutines out, without round or origin logic of its own (D-8).
 */
class SessionSnapshotRoundsTest {
    private val store = SessionHealthUnavailableTestStore()

    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
        IngestStates.clear()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.useStore(null)
        store.clearAll()
        SessionManager.clearAll()
        IngestStates.clear()
    }

    /** The snapshot's nodes by id. */
    private fun nodesOf(body: JsonObject): Map<String, JsonObject> =
        body["coroutines"]!!.jsonArray.associate { node ->
            node.jsonObject["id"]!!.jsonPrimitive.content to node.jsonObject
        }

    private fun JsonObject.text(field: String): String? = this[field]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content

    @Test
    fun `every node carries its lifetime and creation site from the durable summary (Part 7, triage Cards)`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-snapshot-lifetimes")
            val s = SessionManager.getSession(id)!!
            val site = SuspensionPoint(function = "com.shop.Orders.place", fileName = "Orders.kt", lineNumber = 42, reason = "launch")
            s.send(created(id, "done", sec(1), creationPoint = site))
            s.send(completed(id, "done", sec(4)))
            s.send(created(id, "running", sec(2)))
            s.send(suspended(id, "running", sec(5)))

            val nodes = nodesOf(client.getJson("/api/sessions/$id"))

            assertEquals((sec(3)).toString(), nodes["done"]!!.text("lifetimeNanos"))
            assertEquals("FINISHED", nodes["done"]!!.text("lifetimeKind"))
            assertEquals("Orders.kt", nodes["done"]!!["creationLocation"]!!.jsonObject.text("fileName"))
            // No watermark from this producer: up to its own last event, at least.
            assertEquals((sec(3)).toString(), nodes["running"]!!.text("lifetimeNanos"))
            assertEquals("AT_LEAST", nodes["running"]!!.text("lifetimeKind"))
        }

    @Test
    fun `every node carries the round its anchor names`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-snapshot-rounds")
            val s = SessionManager.getSession(id)!!
            s.send(created(id, "a", sec(1)))
            s.send(created(id, "a1", sec(1), parent = "a"))
            s.send(created(id, "a2", sec(1), parent = "a"))
            s.send(created(id, "b", sec(2)))

            val nodes = nodesOf(client.getJson("/api/sessions/$id"))

            assertEquals("round:a", nodes["a"]!!.text("roundId"))
            assertEquals("round:a", nodes["a1"]!!.text("roundId"))
            assertEquals("round:a", nodes["a2"]!!.text("roundId"))
            assertEquals("round:b", nodes["b"]!!.text("roundId"))
            assertEquals("APP", nodes["a1"]!!.text("origin"))
        }

    @Test
    fun `a container root's children are rounds of their own and the root a singleton`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-snapshot-container")
            val s = SessionManager.getSession(id)!!
            s.send(created(id, "server", sec(1)))
            for (n in 1..3) s.send(created(id, "request-$n", sec(1 + n.toLong()), parent = "server"))
            s.send(created(id, "request-1-db", sec(2), parent = "request-1"))
            s.send(completed(id, "request-1-db", sec(3), parent = "request-1"))
            s.send(completed(id, "request-1", sec(3), parent = "server"))
            s.send(completed(id, "request-2", sec(4), parent = "server"))

            val nodes = nodesOf(client.getJson("/api/sessions/$id"))

            assertEquals("round:server", nodes["server"]!!.text("roundId"))
            assertEquals("round:request-1", nodes["request-1"]!!.text("roundId"))
            assertEquals("round:request-1", nodes["request-1-db"]!!.text("roundId"))
            assertEquals("round:request-2", nodes["request-2"]!!.text("roundId"))
            assertEquals("round:request-3", nodes["request-3"]!!.text("roundId"))
        }

    @Test
    fun `vizcore's own coroutines read VIZCORE`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-snapshot-vizcore")
            val s = SessionManager.getSession(id)!!
            s.send(created(id, "app-root", sec(1)))
            s.send(created(id, "cio-reader", sec(2), origin = CoroutineOrigin.VIZCORE))

            val nodes = nodesOf(client.getJson("/api/sessions/$id"))

            assertEquals("APP", nodes["app-root"]!!.text("origin"))
            assertEquals("VIZCORE", nodes["cio-reader"]!!.text("origin"))
            assertEquals("round:cio-reader", nodes["cio-reader"]!!.text("roundId"))
        }

    @Test
    fun `an app that reports no origin reads UNKNOWN everywhere`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-snapshot-unknown")
            val s = SessionManager.getSession(id)!!
            s.send(created(id, "root", sec(1), origin = null))
            s.send(created(id, "child", sec(1), parent = "root", origin = null))

            val body = client.getJson("/api/sessions/$id")
            val nodes = nodesOf(body)

            assertEquals("UNKNOWN", body.text("origin"))
            assertEquals("UNKNOWN", nodes["root"]!!.text("origin"))
            assertEquals("UNKNOWN", nodes["child"]!!.text("origin"))
            assertEquals("round:root", nodes["child"]!!.text("roundId"))
        }

    @Test
    fun `a demo's coroutines are the app's whatever the producer stated`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("scenario-snapshot-demo")
            val s = SessionManager.getSession(id)!!
            s.send(created(id, "demo-root", sec(1), origin = CoroutineOrigin.VIZCORE))

            val nodes = nodesOf(client.getJson("/api/sessions/$id"))

            assertEquals("APP", nodes["demo-root"]!!.text("origin"))
        }

    @Test
    fun `a coroutine whose parent was never captured has its origin and no round`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-snapshot-orphan")
            val s = SessionManager.getSession(id)!!
            s.send(created(id, "orphan", sec(1), parent = "gone"))

            val orphan = nodesOf(client.getJson("/api/sessions/$id"))["orphan"]!!

            assertEquals("APP", orphan.text("origin"))
            assertEquals(null, orphan.text("roundId"))
        }

    @Test
    fun `coroutines the summaries know without a node are counted, vizcore's left out`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-snapshot-summary-only")
            val s = SessionManager.getSession(id)!!
            s.send(created(id, "kept", sec(1)))
            s.send(created(id, "trimmed", sec(1)))
            s.send(created(id, "viz-trimmed", sec(1), origin = CoroutineOrigin.VIZCORE))

            assertEquals(
                0,
                client
                    .getJson("/api/sessions/$id")["summaryOnlyCount"]!!
                    .jsonPrimitive.content
                    .toInt(),
            )
            // A database session reloaded after a trim: its nodes rebuilt from retained events, its summaries restored.
            val full = ReadCut.of(s)
            val reloaded = ReadCut(full.summaries, full.events, full.nodes.filter { it.id == "kept" }, full.seq)
            val body = snapshotResponseOf(s, full.events, reloaded, IngestStates.snapshot(id))
            assertEquals(1, body.summaryOnlyCount)
            assertEquals(listOf("kept"), body.coroutines.map { it.id })
        }

    @Test
    fun `a page of rounds says the app time of the last event it reflects`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-snapshot-as-of")
            val s = SessionManager.getSession(id)!!
            s.send(created(id, "a", sec(1)))
            s.send(created(id, "b", sec(3)))

            val page = client.getJson("/api/sessions/$id/rounds")

            assertEquals(sec(3), page["asOfAppNanos"]!!.jsonPrimitive.content.toLong())
        }

    @Test
    fun `a page of rounds is as of the app's latest poll, which can pass the last event`() =
        testApplication {
            application { module() }
            val ws = createClient { install(WebSockets) }
            val id = ws.createSessionNamed("app-as-of-watermark")
            ws.webSocket("/api/sessions/$id/ingest") {
                send(Frame.Text(created(id, "a", sec(1)).frameText()))
                send(Frame.Text(HealthEvents.watermark(id, at = sec(9)).frameText()))
                eventually { IngestStates.snapshot(id).watermark != null }

                val page = ws.getJson("/api/sessions/$id/rounds")

                assertEquals(sec(9), page["asOfAppNanos"]!!.jsonPrimitive.content.toLong())
            }
        }

    @Test
    fun `find leaves vizcore's own matches out before it counts and caps them, unless asked`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-find-vizcore")
            val s = SessionManager.getSession(id)!!
            s.send(created(id, "request", sec(1)))
            s.send(created(id, "worker-app", sec(1), parent = "request"))
            s.send(created(id, "worker-sdk", sec(2), parent = "request", origin = CoroutineOrigin.VIZCORE))
            s.send(created(id, "worker-orphan-sdk", sec(2), parent = "gone", origin = CoroutineOrigin.VIZCORE))

            val off = client.getJson("/api/sessions/$id/rounds?q=worker")
            val offMatches =
                off["items"]!!
                    .jsonArray
                    .single()
                    .jsonObject["matches"]!!
                    .jsonObject
            assertEquals(listOf("worker-app"), offMatches["items"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(1, offMatches["total"]!!.jsonPrimitive.content.toInt())
            assertEquals(
                0,
                off["ungroupedMatches"]!!
                    .jsonObject["total"]!!
                    .jsonPrimitive.content
                    .toInt(),
            )

            val on = client.getJson("/api/sessions/$id/rounds?q=worker&includeVizcore=true")
            val onMatches =
                on["items"]!!
                    .jsonArray
                    .single()
                    .jsonObject["matches"]!!
                    .jsonObject
            assertEquals(2, onMatches["total"]!!.jsonPrimitive.content.toInt())
            assertEquals(
                1,
                on["ungroupedMatches"]!!
                    .jsonObject["total"]!!
                    .jsonPrimitive.content
                    .toInt(),
            )
        }

    @Test
    fun `without usable summaries the nodes carry neither and the rest is unchanged`() =
        testApplication {
            application {
                module()
                SessionManager.useStore(store)
            }
            startApplication()
            val session = store.createSession("app-no-summaries")
            session.send(created("app-no-summaries", "a", sec(1)))

            val body = client.getJson("/api/sessions/app-no-summaries")
            val node = nodesOf(body)["a"]!!

            assertEquals(JsonNull, node["roundId"])
            assertEquals(JsonNull, node["origin"])
            assertEquals("a", node.text("label"))
            assertEquals(1, body["coroutineCount"]!!.jsonPrimitive.content.toInt())
            assertEquals(0, body["summaryOnlyCount"]!!.jsonPrimitive.content.toInt())
        }
}
