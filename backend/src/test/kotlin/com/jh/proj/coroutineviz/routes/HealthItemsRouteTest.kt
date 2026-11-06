package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.health.Incident
import com.jh.proj.coroutineviz.health.MemberRole
import com.jh.proj.coroutineviz.health.PossiblyBlockedIncident
import com.jh.proj.coroutineviz.health.SessionHealth
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.routes.HealthEvents.completed
import com.jh.proj.coroutineviz.routes.HealthEvents.created
import com.jh.proj.coroutineviz.routes.HealthEvents.failed
import com.jh.proj.coroutineviz.routes.HealthEvents.sec
import com.jh.proj.coroutineviz.routes.HealthEvents.started
import com.jh.proj.coroutineviz.routes.HealthEvents.suspended
import com.jh.proj.coroutineviz.routes.HealthEvents.watermark
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `GET /api/sessions/{id}/health/items` (spec C-3; plan E-10, E-17). */
class HealthItemsRouteTest {
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

    private suspend fun HttpClient.health(id: String): SessionHealth {
        val response = get("/api/sessions/$id/health")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return appJson.decodeFromString(SessionHealth.serializer(), response.bodyAsText())
    }

    private suspend fun HttpClient.incidents(
        id: String,
        query: String,
    ): IncidentsPage {
        val response = get("/api/sessions/$id/health/items?list=incidents$query")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return appJson.decodeFromString(IncidentsPage.serializer(), response.bodyAsText())
    }

    private fun SessionHealth.binding(): String =
        "&asOfSeq=$asOfSeq&asOfTransport=${transport.revision}" + (asOfWatermark?.let { "&asOfWatermark=${it.appNanos}" } ?: "")

    @Test
    fun `incidents past the first 50 page on from the health's own`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-items")
            val s = SessionManager.getSession(id)!!
            for (i in 1..60) {
                s.send(created(id, "r$i", sec(i.toLong())))
                s.send(created(id, "r$i-c", sec(i.toLong()), parent = "r$i"))
                s.send(failed(id, "r$i-c", sec(i.toLong()) + 1, parent = "r$i"))
            }
            val health = client.health(id)
            assertEquals(60, health.incidents.total)
            val first = client.incidents(id, health.binding())
            assertEquals(health.incidents.items, first.items)
            val rest = client.incidents(id, health.binding() + "&cursor=${first.nextCursor}")
            assertEquals(10, rest.items.size)
            assertNull(rest.nextCursor)
            assertEquals(60, (first.items + rest.items).map { it.id }.toSet().size)
        }

    @Test
    fun `round members, long-lived and long-suspended`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-items-lists")
            val s = SessionManager.getSession(id)!!
            s.send(created(id, "big", sec(1)))
            for (i in 1..4) s.send(created(id, "big-$i", sec(1), parent = "big"))
            s.send(failed(id, "big-1", sec(2), parent = "big"))
            s.send(HealthEvents.cancelled(id, "big-2", sec(3), parent = "big"))
            s.send(failed(id, "big-3", sec(4), parent = "big"))
            // The root finishes by failing, so it is one round again (spec "Rounds": a failed root is not a container).
            s.send(failed(id, "big", sec(5)))
            s.send(created(id, "idle", sec(1)))
            s.send(started(id, "idle", sec(1)))
            s.send(suspended(id, "idle", sec(3))) // observed for 2 s: past a 1 s threshold, ages frozen at its last event
            s.send(created(id, "late", sec(50)))

            val members = client.get("/api/sessions/$id/health/items?list=round-members&incidentId=failed:round:big").bodyAsText()
            val page = appJson.decodeFromString(RoundMembersPage.serializer(), members)
            assertEquals(listOf("big", "big-3", "big-1", "big-2"), page.items.map { it.coroutineId })
            assertEquals(
                listOf(MemberRole.FAILED, MemberRole.FAILED, MemberRole.FAILED, MemberRole.CANCELLED_AFTER),
                page.items.map { it.role },
            )
            val missing = client.get("/api/sessions/$id/health/items?list=round-members&incidentId=failed:round:nope")
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertTrue(missing.bodyAsText().contains("incident-not-found"))

            val longLived =
                appJson.decodeFromString(
                    ObservedAgesPage.serializer(),
                    client.get("/api/sessions/$id/health/items?list=long-lived&leakThresholdMs=1000").bodyAsText(),
                )
            assertEquals("long-lived", longLived.list)
            assertTrue(longLived.items.isNotEmpty())
            val longSuspended =
                appJson.decodeFromString(
                    ObservedAgesPage.serializer(),
                    client.get("/api/sessions/$id/health/items?list=long-suspended").bodyAsText(),
                )
            assertEquals("long-suspended", longSuspended.list)
        }

    @Test
    fun `bad parameters`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-items-bad")
            for (q in listOf("", "?list=nope", "?list=incidents&kind=NOPE", "?list=round-members", "?list=incidents&limit=999")) {
                assertEquals(HttpStatusCode.BadRequest, client.get("/api/sessions/$id/health/items$q").status, q)
            }
            assertEquals(HttpStatusCode.NotFound, client.get("/api/sessions/nope/health/items?list=incidents").status)
        }

    /** An attached app whose worker started at 1 s and keeps running; the app's polls report watermarks. */
    private fun runningWorker(id: String): IngestStates.Connection {
        val s = SessionManager.getSession(id)!!
        s.send(created(id, "worker", sec(1)))
        s.send(started(id, "worker", sec(1)))
        return IngestStates.connectionOpened(id, firstSeq = 1)
    }

    @Test
    fun `a quiet poll between two healths at one seq, each follow-up gets its own health's possibly blocked items`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-items-quiet")
            val connection = runningWorker(id)
            IngestStates.watermarkReceived(connection, watermark(id, sec(5)), receivedEpochMs = 1)
            val early = client.health(id)
            IngestStates.watermarkReceived(connection, watermark(id, sec(12)), receivedEpochMs = 2)
            val late = client.health(id)
            assertEquals(early.asOfSeq, late.asOfSeq)
            assertEquals(1, late.possiblyBlockedCount)

            assertEquals(0, client.incidents(id, "&kind=POSSIBLY_BLOCKED" + early.binding()).total)
            assertEquals(1, client.incidents(id, "&kind=POSSIBLY_BLOCKED" + late.binding()).total)
            assertEquals(0, client.incidents(id, "&kind=POSSIBLY_BLOCKED&asOfSeq=${late.asOfSeq}").total, "no watermark bound")
        }

    @Test
    fun `a transport change without an event, the earlier binding keeps its own cut`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-items-transport")
            val connection = runningWorker(id)
            IngestStates.watermarkReceived(connection, watermark(id, sec(12)), receivedEpochMs = 1)
            val before = client.health(id)
            IngestStates.frameSkipped(connection)
            val after = client.health(id)
            assertEquals(before.asOfSeq, after.asOfSeq)
            assertEquals(1, client.incidents(id, "&kind=POSSIBLY_BLOCKED" + before.binding()).total)
            assertEquals(0, client.incidents(id, "&kind=POSSIBLY_BLOCKED" + after.binding()).total)
        }

    @Test
    fun `competing readers do not evict a view's cut, and its evidence survives a trim`() =
        testApplication {
            application { module() }
            startApplication()
            SessionManager.configure(maxEventsPerSession = 6)
            val id = client.createSessionNamed("app-items-competing")
            val connection = runningWorker(id)
            val s = SessionManager.getSession(id)!!
            for (i in 1..2) {
                s.send(created(id, "f$i", sec(2)))
                s.send(completed(id, "f$i", sec(3)))
            }
            IngestStates.watermarkReceived(connection, watermark(id, sec(12)), receivedEpochMs = 1)
            val mine = client.health(id)
            val blocked = assertIs<PossiblyBlockedIncident>(mine.incidents.items.single())

            // Other readers take their own cuts while the worker suspends, resumes and its early events are trimmed.
            repeat(5) { i ->
                s.send(if (i % 2 == 0) suspended(id, "worker", sec(13L + i)) else started(id, "worker", sec(13L + i)))
                s.send(created(id, "x$i", sec(13L + i)))
                IngestStates.watermarkReceived(connection, watermark(id, sec(14L + i)), receivedEpochMs = 2L + i)
                client.health(id)
            }
            assertTrue(
                s.store
                    .all()
                    .first()
                    .seq > 2,
                "the worker's start was trimmed",
            )

            val page = client.incidents(id, mine.binding())
            assertEquals(listOf<Incident>(blocked), page.items, "the same incident, transitions included")
        }
}
