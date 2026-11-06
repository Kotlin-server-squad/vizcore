package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.routes.HealthEvents.created
import com.jh.proj.coroutineviz.routes.HealthEvents.sec
import com.jh.proj.coroutineviz.routes.HealthEvents.started
import com.jh.proj.coroutineviz.routes.HealthEvents.suspended
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `GET /api/sessions/{id}/metrics` split by origin, leak ages frozen (spec C-3; plan E-15). */
class MetricsByOriginRouteTest {
    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
        IngestStates.clear()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.useStore(null)
        SessionManager.clearAll()
        IngestStates.clear()
    }

    private suspend fun HttpClient.metrics(
        id: String,
        query: String = "",
    ): MetricsResponse {
        val response = get("/api/sessions/$id/metrics$query")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return appJson.decodeFromString(MetricsResponse.serializer(), response.bodyAsText())
    }

    @Test
    fun `an attached app's metrics split by origin, with leak ages frozen at the last observation`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-metrics")
            val s = SessionManager.getSession(id)!!
            for ((cid, origin) in listOf("work" to CoroutineOrigin.APP, "conn" to CoroutineOrigin.VIZCORE)) {
                s.ingest(created(id, cid, sec(1), origin = origin))
                s.ingest(started(id, cid, sec(1)))
                s.ingest(suspended(id, cid, sec(45)))
            }
            val first = client.metrics(id, "?leakThresholdMs=30000")
            val byOrigin = assertNotNull(first.byOrigin)
            assertEquals(1, byOrigin.app.captured)
            assertEquals(1, byOrigin.vizcore.captured)
            assertEquals(1, byOrigin.app.active)
            assertTrue(byOrigin.app.peak >= byOrigin.app.active)
            assertEquals(1, byOrigin.app.longLived)
            assertEquals(
                setOf(CoroutineOrigin.APP to 44_000L, CoroutineOrigin.VIZCORE to 44_000L),
                first.leaks
                    .map {
                        it.origin to
                            it.aliveMs
                    }.toSet(),
            )
            assertTrue(first.leaksAvailable)
            assertFalse(first.retainedOnly)
            Thread.sleep(1_100)
            assertEquals(first.leaks, client.metrics(id, "?leakThresholdMs=30000").leaks, "frozen, not wall-clock")
        }

    @Test
    fun `a demo's coroutines all count as the app's`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("scenario-metrics")
            val s = SessionManager.getSession(id)!!
            s.send(created(id, "a", sec(1), origin = null))
            s.send(created(id, "b", sec(1), origin = CoroutineOrigin.VIZCORE))
            val byOrigin = assertNotNull(client.metrics(id).byOrigin)
            assertEquals(2, byOrigin.app.captured)
            assertEquals(0, byOrigin.vizcore.captured)
        }

    @Test
    fun `without summaries there are no leaks rather than wall-clock ages`() =
        testApplication {
            application { module() }
            startApplication()
            val store = SessionHealthUnavailableTestStore()
            SessionManager.useStore(store)
            val session = store.create("app-metrics-nosummaries")
            session.send(created(session.sessionId, "a", sec(1)))
            val m = client.metrics(session.sessionId, "?leakThresholdMs=1000")
            assertFalse(m.leaksAvailable)
            assertEquals(emptyList(), m.leaks)
            assertNull(m.byOrigin)
        }
}
