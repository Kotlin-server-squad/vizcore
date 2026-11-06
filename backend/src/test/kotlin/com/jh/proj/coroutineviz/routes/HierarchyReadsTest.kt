package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.health.SessionHealth
import com.jh.proj.coroutineviz.models.CoroutineState
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.routes.HealthEvents.completed
import com.jh.proj.coroutineviz.routes.HealthEvents.created
import com.jh.proj.coroutineviz.routes.HealthEvents.failed
import com.jh.proj.coroutineviz.routes.HealthEvents.sec
import com.jh.proj.coroutineviz.routes.HealthEvents.started
import com.jh.proj.coroutineviz.routes.HealthEvents.suspended
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hierarchy with rounds and summaries, hierarchy and timeline at a seq (spec C-3; plan E-6, E-7). */
class HierarchyReadsTest {
    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
        IngestStates.clear()
        HealthCuts.clear()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.configure(maxEventsPerSession = 10_000)
        SessionManager.useStore(null)
        SessionManager.clearAll()
        IngestStates.clear()
        HealthCuts.clear()
    }

    private suspend fun HttpClient.nodes(
        id: String,
        query: String = "",
    ): List<HierarchyNodeDto> {
        val response = get("/api/sessions/$id/hierarchy$query")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return appJson.decodeFromString(ListSerializer(HierarchyNodeDto.serializer()), response.bodyAsText())
    }

    /** r1 (completed), r2 with a child that fails (seqs 4..6), r3. */
    private fun rounds(id: String) {
        val s = SessionManager.getSession(id)!!
        s.send(created(id, "r1", sec(1)))
        s.send(completed(id, "r1", sec(2)))
        s.send(created(id, "r2", sec(2)))
        s.send(created(id, "r2-c", sec(2), parent = "r2"))
        s.send(started(id, "r2-c", sec(2)))
        s.send(failed(id, "r2-c", sec(3), parent = "r2"))
        s.send(created(id, "r3", sec(4)))
    }

    @Test
    fun `nodes carry their round, summary and summary state, and keep every projection field`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-tree")
            rounds(id)
            val nodes = client.nodes(id)
            assertEquals(listOf("r1", "r2", "r2-c", "r3"), nodes.map { it.id })
            val child = nodes.single { it.id == "r2-c" }
            assertEquals("round:r2", child.roundId)
            assertEquals(CoroutineState.FAILED, child.summaryState)
            assertNotNull(child.summary)
            val raw =
                appJson
                    .parseToJsonElement(client.get("/api/sessions/$id/hierarchy").bodyAsText())
                    .jsonArray
                    .first()
                    .jsonObject
            for (field in listOf("scopeId", "jobId", "state", "children", "createdAtNanos")) assertTrue(field in raw, field)
        }

    @Test
    fun `one round at a time`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-tree-round")
            rounds(id)
            assertEquals(listOf("r2", "r2-c"), client.nodes(id, "?roundId=round:r2").map { it.id })
            assertEquals(emptyList(), client.nodes(id, "?roundId=round:none"))
        }

    @Test
    fun `at a replay cursor the nodes are those of that moment`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-tree-replay")
            rounds(id)
            val nodes = client.nodes(id, "?atSeq=5")
            val child = nodes.single { it.id == "r2-c" }
            assertEquals("RUNNING", child.state)
            assertEquals(CoroutineState.ACTIVE, child.summaryState)
            assertNull(nodes.firstOrNull { it.id == "r3" })
        }

    @Test
    fun `projection state and summary state differ by design for a failure with a cancellation exception`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-tree-cancel")
            val s = SessionManager.getSession(id)!!
            s.send(created(id, "c", sec(1)))
            s.send(started(id, "c", sec(1)))
            s.send(
                CoroutineFailed(
                    sessionId = id,
                    seq = 0,
                    tsNanos = sec(2),
                    coroutineId = "c",
                    jobId = "job-c",
                    parentCoroutineId = null,
                    scopeId = "scope",
                    label = "c",
                    exceptionType = "kotlinx.coroutines.JobCancellationException",
                    message = "cancelled",
                    stackTrace = emptyList(),
                ),
            )
            val node = client.nodes(id).single()
            assertEquals("FAILED", node.state)
            assertEquals(CoroutineState.CANCELLED, node.summaryState)
        }

    @Test
    fun `every node of a bound read describes the response's seq while events keep arriving`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-tree-busy")
            val s = SessionManager.getSession(id)!!
            s.send(created(id, "server", sec(1)))
            runBlocking {
                val writer =
                    launch(Dispatchers.Default) {
                        var n = 0L
                        while (isActive) {
                            s.send(created(id, "req$n", sec(2) + n, parent = "server"))
                            n++
                            delay(3)
                        }
                    }
                try {
                    repeat(20) {
                        val health =
                            appJson.decodeFromString(
                                SessionHealth.serializer(),
                                client.get("/api/sessions/$id/health").bodyAsText(),
                            )
                        val response =
                            client.get(
                                "/api/sessions/$id/hierarchy?asOfSeq=${health.asOfSeq}&asOfTransport=${health.transport.revision}",
                            )
                        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                        val seq = response.headers[AS_OF_SEQ_HEADER]!!.toLong()
                        assertEquals(health.asOfSeq, seq)
                        val nodes = appJson.decodeFromString(ListSerializer(HierarchyNodeDto.serializer()), response.bodyAsText())
                        assertTrue(nodes.all { it.summary != null && it.summary!!.lastModifiedSeq <= seq })
                        assertTrue(nodes.all { it.summary!!.firstObserved.seq <= seq })
                    }
                } finally {
                    writer.cancel()
                }
            }
        }

    /** A store of 6 events: "root" (created, started, suspended at seqs 1..3) is trimmed out by 6 more events. */
    private fun trimmedRoot(id: String) {
        val s = SessionManager.getSession(id)!!
        s.send(created(id, "root", sec(1)))
        s.send(started(id, "root", sec(1)))
        s.send(suspended(id, "root", sec(2)))
        for (i in 1..3) {
            s.send(created(id, "o$i", sec(3)))
            s.send(completed(id, "o$i", sec(4)))
        }
    }

    @Test
    fun `a trimmed creation is a summary-only node, partial in replay, stale when bound and unknown`() =
        testApplication {
            application { module() }
            startApplication()
            SessionManager.configure(maxEventsPerSession = 6)
            val id = client.createSessionNamed("app-tree-trimmed")
            trimmedRoot(id)
            val root = client.nodes(id).single { it.id == "root" }
            assertTrue(root.summaryOnly)
            assertEquals("SUSPENDED", root.state)
            assertNull(root.scopeId)

            val seq = SessionManager.getSession(id)!!.lastSeq
            SessionManager.getSession(id)!!.send(started(id, "root", sec(9)))
            assertEquals(HttpStatusCode.Conflict, client.get("/api/sessions/$id/hierarchy?asOfSeq=$seq").status)
            assertTrue(client.nodes(id, "?atSeq=$seq").single { it.id == "root" }.partial)
        }

    @Test
    fun `without summaries the current hierarchy still answers, without rounds`() =
        testApplication {
            application { module() }
            startApplication()
            val store = SessionHealthUnavailableTestStore()
            SessionManager.useStore(store)
            val session = store.create("app-tree-nosummaries")
            session.send(created(session.sessionId, "a", sec(1)))
            val node = client.nodes(session.sessionId).single()
            assertNull(node.roundId)
            assertNull(node.summary)
        }

    @Test
    fun `a timeline at a seq lists only events up to it, describes that moment, and does not extrapolate`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-timeline")
            rounds(id)
            val first = client.get("/api/sessions/$id/coroutines/r2-c/timeline?atSeq=5")
            assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
            val body = appJson.parseToJsonElement(first.bodyAsText()).jsonObject
            assertTrue(body["events"]!!.jsonArray.all { it.jsonObject["seq"]!!.jsonPrimitive.long <= 5 })
            assertEquals("RUNNING", body["state"]!!.jsonPrimitive.content)
            assertEquals(null, body["openPhase"]?.takeUnless { it.toString() == "null" })
            assertEquals(null, body["observedAtEpochMs"]?.takeUnless { it.toString() == "null" })
            assertFalse(body["eventsTrimmed"]!!.jsonPrimitive.boolean)
            Thread.sleep(1_100)
            assertEquals(first.bodyAsText(), client.get("/api/sessions/$id/coroutines/r2-c/timeline?atSeq=5").bodyAsText())
        }

    @Test
    fun `a trimmed creation's timeline keeps its retained events, with unknown durations, and is partial or stale`() =
        testApplication {
            application { module() }
            startApplication()
            SessionManager.configure(maxEventsPerSession = 6)
            val id = client.createSessionNamed("app-timeline-trimmed")
            trimmedRoot(id)
            val seq = SessionManager.getSession(id)!!.lastSeq
            val body =
                appJson
                    .parseToJsonElement(
                        client.get("/api/sessions/$id/coroutines/root/timeline?atSeq=$seq").bodyAsText(),
                    ).jsonObject
            assertTrue(body["eventsTrimmed"]!!.jsonPrimitive.boolean)
            assertEquals("null", body["activeDuration"].toString())
            assertEquals("SUSPENDED", body["state"]!!.jsonPrimitive.content)

            SessionManager.getSession(id)!!.send(started(id, "root", sec(9)))
            assertEquals(HttpStatusCode.Conflict, client.get("/api/sessions/$id/coroutines/root/timeline?asOfSeq=$seq").status)
            val replay =
                appJson
                    .parseToJsonElement(
                        client.get("/api/sessions/$id/coroutines/root/timeline?atSeq=$seq").bodyAsText(),
                    ).jsonObject
            assertTrue(replay["partial"]!!.jsonPrimitive.boolean)
            val events =
                SessionManager
                    .getSession(
                        id,
                    )!!
                    .let { client.get("/api/sessions/$id/coroutines/root/timeline?atSeq=${it.lastSeq}") }
            assertTrue(
                (appJson.parseToJsonElement(events.bodyAsText()) as JsonObject)["events"]!!.jsonArray.isNotEmpty(),
                "its retained start",
            )
        }

    @Test
    fun `a trimmed coroutine with no retained event has an empty timeline, never a made-up creation`() =
        testApplication {
            application { module() }
            startApplication()
            SessionManager.configure(maxEventsPerSession = 6)
            val id = client.createSessionNamed("app-timeline-empty")
            trimmedRoot(id) // root's three events are all trimmed
            val seq = SessionManager.getSession(id)!!.lastSeq
            val body =
                appJson
                    .parseToJsonElement(
                        client.get("/api/sessions/$id/coroutines/root/timeline?atSeq=$seq").bodyAsText(),
                    ).jsonObject
            assertTrue(body["eventsTrimmed"]!!.jsonPrimitive.boolean)
            assertEquals(0, body["events"]!!.jsonArray.size)
        }

    @Test
    fun `a timeline rejects a watermark or transport without asOfSeq`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-timeline-bad")
            rounds(id)
            for (q in listOf("?asOfWatermark=5", "?asOfTransport=0", "?atSeq=1&asOfSeq=1")) {
                assertEquals(HttpStatusCode.BadRequest, client.get("/api/sessions/$id/coroutines/r2-c/timeline$q").status, q)
            }
        }
}
