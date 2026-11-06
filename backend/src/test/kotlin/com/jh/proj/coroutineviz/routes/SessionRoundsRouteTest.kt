package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.health.IncidentKind
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `GET /api/sessions/{id}/rounds` (spec C-3, T-4; plan E-8, E-9, E-17). */
class SessionRoundsRouteTest {
    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
        IngestStates.clear()
        HealthCuts.clear()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.configure(maxEventsPerSession = 10_000)
        SessionManager.clearAll()
        IngestStates.clear()
        HealthCuts.clear()
    }

    private suspend fun HttpClient.rounds(
        id: String,
        query: String = "",
    ): RoundsPage {
        val response = get("/api/sessions/$id/rounds$query")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return appJson.decodeFromString(RoundsPage.serializer(), response.bodyAsText())
    }

    private suspend fun HttpClient.health(id: String): SessionHealth =
        appJson.decodeFromString(SessionHealth.serializer(), get("/api/sessions/$id/health").bodyAsText())

    /** r1 (1 s, a child created in App.kt), r2 (2 s, a child that failed), r3 (3 s), a vizcore root (4 s). */
    private fun threeRounds(id: String) {
        val s = SessionManager.getSession(id)!!
        s.send(created(id, "r1", sec(1)))
        s.send(created(id, "r1-child", sec(1), parent = "r1", creationPoint = SuspensionPoint("load", "App.kt", 7, "launch")))
        s.send(completed(id, "r1-child", sec(2), parent = "r1"))
        s.send(completed(id, "r1", sec(2)))
        s.send(created(id, "r2", sec(2)))
        s.send(created(id, "r2-child", sec(2), parent = "r2"))
        s.send(failed(id, "r2-child", sec(3), parent = "r2"))
        s.send(completed(id, "r2", sec(3)))
        s.send(created(id, "r3", sec(3)))
        s.send(created(id, "viz", sec(4), origin = CoroutineOrigin.VIZCORE))
    }

    @Test
    fun `rounds list problem rounds first, then newest anchor first`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-rounds")
            threeRounds(id)
            val page = client.rounds(id)
            assertEquals(listOf("r2", "r3", "r1"), page.items.map { it.anchorId })
            val r2 = page.items.first()
            assertTrue(r2.problem)
            assertEquals(listOf(IncidentKind.FAILED), r2.problemKinds)
            assertEquals(mapOf(CoroutineState.FAILED to 1, CoroutineState.COMPLETED to 1), r2.countsByState)
            assertEquals(3, page.total)
            assertNull(page.nextCursor)
            assertEquals(SessionManager.getSession(id)!!.lastSeq, page.asOfSeq)
            assertEquals(0, page.unknownAtSeq)
        }

    @Test
    fun `pages follow the cursor`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-rounds-pages")
            threeRounds(id)
            val first = client.rounds(id, "?limit=1")
            val second = client.rounds(id, "?limit=1&cursor=${first.nextCursor}")
            val third = client.rounds(id, "?limit=1&cursor=${second.nextCursor}")
            assertEquals(listOf("r2", "r3", "r1"), (first.items + second.items + third.items).map { it.anchorId })
            assertNull(third.nextCursor)
        }

    @Test
    fun `rounds whose anchors tie in time page by id without skipping or repeating`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-rounds-ties")
            val s = SessionManager.getSession(id)!!
            for (r in listOf("e", "b", "d", "a", "c")) s.send(created(id, r, sec(1)))
            val seen = mutableListOf<String>()
            var cursor: String? = null
            do {
                val page = client.rounds(id, "?limit=2" + (cursor?.let { "&cursor=$it" } ?: ""))
                seen += page.items.map { it.anchorId }
                cursor = page.nextCursor
            } while (cursor != null)
            assertEquals(5, seen.size)
            assertEquals(seen.toSet().size, seen.size)
        }

    @Test
    fun `find matches names and creation locations, case-insensitively`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-rounds-find")
            threeRounds(id)
            assertEquals(listOf("r3"), client.rounds(id, "?q=R3").items.map { it.anchorId })
            val byFile = client.rounds(id, "?q=app.kt").items
            assertEquals(listOf("r1"), byFile.map { it.anchorId })
            assertEquals(listOf("r1-child"), byFile.single().matches!!.items)
            assertEquals(3, client.rounds(id, "?q=%20%20").total, "a blank q does not filter")
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/sessions/$id/rounds?q=${"x".repeat(201)}").status)
        }

    @Test
    fun `find also reports matches that belong to no round, healthy or failed`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-rounds-orphans")
            val s = SessionManager.getSession(id)!!
            // Their parent was never captured: no round (spec "Rounds", orphans).
            s.send(created(id, "orphan-ok", sec(1), parent = "gone"))
            s.send(created(id, "orphan-bad", sec(2), parent = "gone"))
            s.send(failed(id, "orphan-bad", sec(3), parent = "gone"))
            val page = client.rounds(id, "?q=orphan")
            assertEquals(0, page.total)
            assertEquals(listOf("orphan-bad", "orphan-ok"), page.ungroupedMatches!!.items)
            assertNull(client.rounds(id).ungroupedMatches, "only with q")
        }

    @Test
    fun `problems only, and vizcore rounds only on request`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-rounds-filter")
            threeRounds(id)
            assertEquals(listOf("r2"), client.rounds(id, "?problemsOnly=true").items.map { it.anchorId })
            assertEquals(listOf("r2", "viz", "r3", "r1"), client.rounds(id, "?includeVizcore=true").items.map { it.anchorId })
        }

    @Test
    fun `a replay cursor shows the rounds that existed then`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-rounds-replay")
            threeRounds(id)
            assertEquals(listOf("r2", "r1"), client.rounds(id, "?atSeq=8").items.map { it.anchorId })
        }

    @Test
    fun `bad parameters and unknown sessions`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-rounds-bad")
            for (q in listOf("?cursor=bad", "?limit=0", "?atSeq=1&asOfSeq=1", "?asOfWatermark=5")) {
                assertEquals(HttpStatusCode.BadRequest, client.get("/api/sessions/$id/rounds$q").status, q)
            }
            assertEquals(HttpStatusCode.NotFound, client.get("/api/sessions/nope/rounds").status)
        }

    /** A store of 6 events: "root" (created, started, suspended at seqs 1..3) is trimmed out by 6 more events. */
    private fun trimmed(id: String) {
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
    fun `a bound read answers from its health's cut, is stale once the cut is gone, and replay stays lenient`() =
        testApplication {
            application { module() }
            startApplication() // module() configures the store size: configure after it
            SessionManager.configure(maxEventsPerSession = 6)
            val id = client.createSessionNamed("app-rounds-stale")
            trimmed(id)
            assertTrue(
                SessionManager
                    .getSession(id)!!
                    .store
                    .all()
                    .first()
                    .seq > 3,
                "root's events were trimmed",
            )
            val health = client.health(id)
            SessionManager.getSession(id)!!.send(started(id, "root", sec(9))) // root changes after the health

            val bound = client.rounds(id, "?asOfSeq=${health.asOfSeq}&asOfTransport=${health.transport.revision}")
            val root = bound.items.single { it.anchorId == "root" }
            assertEquals(mapOf(CoroutineState.SUSPENDED to 1), root.countsByState)

            HealthCuts.clear()
            val stale = client.get("/api/sessions/$id/rounds?asOfSeq=${health.asOfSeq}")
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertTrue(stale.bodyAsText().contains("\"code\":\"stale\""))

            val replay = client.rounds(id, "?atSeq=${health.asOfSeq}")
            assertEquals(1, replay.unknownAtSeq)
            assertEquals(1, replay.items.single { it.anchorId == "root" }.unknownMembers)
        }

    @Test
    fun `a busy app's view makes progress from health to rounds`() =
        testApplication {
            application { module() }
            startApplication()
            SessionManager.configure(maxEventsPerSession = 50)
            val id = client.createSessionNamed("app-rounds-busy")
            val s = SessionManager.getSession(id)!!
            s.send(created(id, "server", sec(1)))
            s.send(started(id, "server", sec(1)))
            runBlocking {
                val writer =
                    launch(Dispatchers.Default) {
                        var n = 0L
                        while (isActive) {
                            s.send(if (n % 2 == 0L) suspended(id, "server", sec(2) + n) else started(id, "server", sec(2) + n))
                            s.send(created(id, "req$n", sec(2) + n))
                            n++
                            delay(5)
                        }
                    }
                try {
                    while (s.store
                            .all()
                            .first()
                            .seq <= 2
                    ) {
                        delay(5) // the server's creation is trimmed
                    }
                    repeat(20) {
                        val health = client.health(id)
                        val response =
                            client.get("/api/sessions/$id/rounds?asOfSeq=${health.asOfSeq}&asOfTransport=${health.transport.revision}")
                        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                    }
                } finally {
                    writer.cancel()
                }
            }
        }
}
