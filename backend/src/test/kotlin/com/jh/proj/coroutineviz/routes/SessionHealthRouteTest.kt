package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.health.AsOfKind
import com.jh.proj.coroutineviz.health.EvaluationStatus
import com.jh.proj.coroutineviz.health.FailedRoundIncident
import com.jh.proj.coroutineviz.health.HealthState
import com.jh.proj.coroutineviz.health.NotEvaluatedReason
import com.jh.proj.coroutineviz.health.PossiblyBlockedIncident
import com.jh.proj.coroutineviz.health.SessionHealth
import com.jh.proj.coroutineviz.health.SessionOrigin
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.routes.HealthEvents.sec
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionHealthRouteTest {
    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
        IngestStates.clear()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.clearAll()
        IngestStates.clear()
    }

    private suspend fun HttpClient.health(path: String): SessionHealth {
        val response = get(path)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return appJson.decodeFromString(SessionHealth.serializer(), response.bodyAsText())
    }

    /** A demo round: a request whose child failed. Returns the seq of the event before the failure. */
    private fun failingDemo(session: VizSession): Long {
        val id = session.sessionId
        session.send(HealthEvents.created(id, "req", at = sec(1)))
        session.send(HealthEvents.created(id, "child", at = sec(1), parent = "req"))
        session.send(HealthEvents.started(id, "child", at = sec(2), parent = "req"))
        val beforeFailure = session.lastSeq
        session.send(HealthEvents.failed(id, "child", at = sec(3), parent = "req"))
        return beforeFailure
    }

    @Test
    fun `an unknown session is not found`() =
        testApplication {
            application { module() }
            assertEquals(HttpStatusCode.NotFound, client.get("/api/sessions/nope/health").status)
        }

    @Test
    fun `atSeq must be a non-negative number`() =
        testApplication {
            application { module() }
            val sessionId = client.createSessionNamed("scenario-bad-seq")

            assertEquals(HttpStatusCode.BadRequest, client.get("/api/sessions/$sessionId/health?atSeq=-1").status)
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/sessions/$sessionId/health?atSeq=abc").status)
        }

    @Test
    fun `a failed demo round is one problem, and possibly blocked is not evaluated for demos`() =
        testApplication {
            application { module() }
            val sessionId = client.createSessionNamed("scenario-health")
            failingDemo(SessionManager.getSession(sessionId)!!)

            val health = client.health("/api/sessions/$sessionId/health")

            assertEquals(HealthState.PROBLEMS, health.state)
            val round = assertIs<FailedRoundIncident>(health.incidents.items.single())
            assertEquals("req", round.anchorId)
            assertEquals(NotEvaluatedReason.DEMO, health.possiblyBlocked.reason)
            assertEquals(AsOfKind.LAST_EVENT, health.asOf?.kind)
            assertEquals(4L, health.asOfSeq)
            assertEquals(false, health.retention?.trimmed)
            assertEquals(1L, health.retention?.oldestRetainedSeq)
        }

    @Test
    fun `the wire tags incidents by type`() =
        testApplication {
            application { module() }
            val sessionId = client.createSessionNamed("scenario-wire")
            failingDemo(SessionManager.getSession(sessionId)!!)

            val incident =
                client
                    .getJson("/api/sessions/$sessionId/health")["incidents"]!!
                    .jsonObject["items"]!!
                    .let { (it as kotlinx.serialization.json.JsonArray)[0].jsonObject }

            assertEquals("FAILED_ROUND", incident["type"]?.jsonPrimitive?.content)
        }

    @Test
    fun `atSeq replays the health at the cursor`() =
        testApplication {
            application { module() }
            val sessionId = client.createSessionNamed("scenario-replay")
            val beforeFailure = failingDemo(SessionManager.getSession(sessionId)!!)

            val before = client.health("/api/sessions/$sessionId/health?atSeq=$beforeFailure")
            val after = client.health("/api/sessions/$sessionId/health?atSeq=99")

            assertEquals(0, before.problemCount)
            assertEquals(beforeFailure, before.asOfSeq)
            assertEquals(AsOfKind.REPLAY_CURSOR, before.asOf?.kind)
            assertEquals(NotEvaluatedReason.REPLAY, before.possiblyBlocked.reason)
            assertEquals(1, after.problemCount)
            assertEquals(4L, after.asOfSeq, "a cursor past the last event is clamped to it")
        }

    @Test
    fun `an attached app without poll data is not evaluated for possibly blocked`() =
        testApplication {
            application { module() }
            val sessionId = client.createSessionNamed("app-no-watermark")
            val session = SessionManager.getSession(sessionId)!!
            session.ingest(HealthEvents.created(sessionId, "c1", at = sec(1)))
            session.ingest(HealthEvents.started(sessionId, "c1", at = sec(1)))

            val health = client.health("/api/sessions/$sessionId/health")

            assertEquals(NotEvaluatedReason.NO_WATERMARK, health.possiblyBlocked.reason)
            assertEquals(AsOfKind.INGEST, health.asOf?.kind)
            assertNull(health.asOfWatermark)
        }

    @Test
    fun `a coroutine running past the threshold up to the watermark is possibly blocked`() =
        testApplication {
            application { module() }
            val ws = createClient { install(WebSockets) }
            val sessionId = ws.createSessionNamed("app-blocked")

            ws.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(HealthEvents.created(sessionId, "worker", at = sec(1)).frameText()))
                send(Frame.Text(HealthEvents.started(sessionId, "worker", at = sec(1)).frameText()))
                send(Frame.Text(HealthEvents.watermark(sessionId, at = sec(12)).frameText()))
                eventually { IngestStates.snapshot(sessionId).watermark != null }

                // Read while connected: closing without a final watermark makes delivery uncertain.
                val health = ws.health("/api/sessions/$sessionId/health")

                assertEquals(EvaluationStatus.EVALUATED, health.possiblyBlocked.status)
                val blocked = assertIs<PossiblyBlockedIncident>(health.incidents.items.single())
                assertEquals("worker", blocked.coroutineId)
                assertEquals(sec(11), blocked.runningForNanos)
                assertEquals(AsOfKind.WATERMARK, health.asOf?.kind)
                assertEquals(sec(12), health.asOfWatermark?.appNanos)
            }
        }

    @Test
    fun `after an unclean close failures read at least and possibly blocked is not evaluated`() =
        testApplication {
            application { module() }
            val ws = createClient { install(WebSockets) }
            val sessionId = ws.createSessionNamed("app-unclean")

            ws.webSocket("/api/sessions/$sessionId/ingest") {
                send(Frame.Text(HealthEvents.created(sessionId, "c1", at = sec(1)).frameText()))
                eventually { SessionManager.getSession(sessionId)!!.lastSeq == 1L }
            }
            eventually { IngestStates.snapshot(sessionId).transport.deliveryUncertain }

            val health = ws.health("/api/sessions/$sessionId/health")

            assertTrue(health.failuresAtLeast)
            assertEquals(NotEvaluatedReason.EVENTS_DROPPED, health.possiblyBlocked.reason)
            assertEquals(1L, health.transport.sinceSeq)
        }

    @Test
    fun `the leak threshold parameter reaches the long-lived observation`() =
        testApplication {
            application { module() }
            val sessionId = client.createSessionNamed("scenario-leak")
            val session = SessionManager.getSession(sessionId)!!
            session.send(HealthEvents.created(sessionId, "slow", at = sec(1)))
            session.send(HealthEvents.started(sessionId, "slow", at = sec(3)))

            val byDefault = client.health("/api/sessions/$sessionId/health")
            val short = client.health("/api/sessions/$sessionId/health?leakThresholdMs=1000")

            assertEquals(0, byDefault.observations.longLived.total)
            assertEquals(1, short.observations.longLived.total)
        }

    @Test
    fun `a trimmed memory session keeps its failures live and in replay`() =
        testApplication {
            application { module() }
            // The app configured the default on start; a tiny store forces a trim.
            client.get("/api/sessions")
            SessionManager.configure(maxEventsPerSession = 3)
            try {
                val sessionId = client.createSessionNamed("scenario-trim")
                val session = SessionManager.getSession(sessionId)!!
                failingDemo(session)
                session.send(HealthEvents.completed(sessionId, "req", at = sec(4)))

                val live = client.health("/api/sessions/$sessionId/health")
                val replay = client.health("/api/sessions/$sessionId/health?atSeq=4")
                val early = client.health("/api/sessions/$sessionId/health?atSeq=1")

                assertEquals(true, live.retention?.trimmed)
                assertEquals(3L, live.retention?.oldestRetainedSeq)
                assertEquals(1, live.failedRoundCount)
                assertEquals(false, live.failuresAtLeast, "memory summaries saw every event")
                assertEquals(1, replay.failedRoundCount, "the seed keeps the trimmed creations")
                assertEquals(0, early.problemCount)
                assertEquals(1L, early.asOfSeq)
            } finally {
                SessionManager.configure()
            }
        }

    @Test
    fun `an app on an SDK from before triage is evaluated with what it sends`() =
        testApplication {
            application { module() }
            val ws = createClient { install(WebSockets) }
            val sessionId = ws.createSessionNamed("app-old-sdk")

            ws.webSocket("/api/sessions/$sessionId/ingest") {
                // No origin, no provenance, no watermark.
                send(Frame.Text(HealthEvents.created(sessionId, "c1", at = sec(1)).olderSdkFrameText("origin")))
                send(Frame.Text(HealthEvents.started(sessionId, "c1", at = sec(1)).frameText()))
                send(Frame.Text(HealthEvents.suspended(sessionId, "c1", at = sec(2)).olderSdkFrameText("provenance")))
                eventually { SessionManager.getSession(sessionId)!!.lastSeq == 3L }

                val health = ws.health("/api/sessions/$sessionId/health")

                assertEquals(SessionOrigin.UNKNOWN, health.sessionOrigin)
                assertEquals(NotEvaluatedReason.NO_WATERMARK, health.possiblyBlocked.reason)
                assertEquals(HealthState.NO_FAILURES_PARTIAL, health.state, "never green without origins")
                assertEquals(1, health.counts.unknown)
            }
        }

    @Test
    fun `a blocked coroutine whose events were trimmed from memory is still detected`() =
        testApplication {
            application { module() }
            val ws = createClient { install(WebSockets) }
            ws.get("/api/sessions")
            SessionManager.configure(maxEventsPerSession = 3)
            try {
                val sessionId = ws.createSessionNamed("app-trimmed")
                ws.webSocket("/api/sessions/$sessionId/ingest") {
                    send(Frame.Text(HealthEvents.created(sessionId, "worker", at = sec(1)).frameText()))
                    send(Frame.Text(HealthEvents.started(sessionId, "worker", at = sec(1)).frameText()))
                    send(Frame.Text(HealthEvents.created(sessionId, "f1", at = sec(2)).frameText()))
                    send(Frame.Text(HealthEvents.completed(sessionId, "f1", at = sec(2)).frameText()))
                    send(Frame.Text(HealthEvents.created(sessionId, "f2", at = sec(3)).frameText()))
                    send(Frame.Text(HealthEvents.watermark(sessionId, at = sec(12)).frameText()))
                    eventually { IngestStates.snapshot(sessionId).watermark != null }

                    val health = ws.health("/api/sessions/$sessionId/health")

                    assertEquals(true, health.retention?.trimmed)
                    val blocked = assertIs<PossiblyBlockedIncident>(health.incidents.items.single())
                    assertEquals("worker", blocked.coroutineId)
                    assertTrue(blocked.transitionsGap, "its transitions were trimmed")
                }
            } finally {
                SessionManager.configure()
            }
        }

    @Test
    fun `transport facts that arrive while a health is read are not lost`() =
        testApplication {
            application { module() }
            val sessionId = client.createSessionNamed("app-race")
            val session = SessionManager.getSession(sessionId)!!
            session.ingest(HealthEvents.created(sessionId, "c1", at = sec(1)))
            session.ingest(HealthEvents.started(sessionId, "c1", at = sec(1)))
            session.ingest(HealthEvents.suspended(sessionId, "c1", at = sec(2)))
            val connection = IngestStates.connectionOpened(sessionId, firstSeq = 1)
            IngestStates.watermarkReceived(connection, HealthEvents.watermark(sessionId, at = sec(3)), receivedEpochMs = 1)
            // The cut a read took before resolving the session: clean so far.
            val cut = IngestStates.snapshot(sessionId)
            // Then a frame could not be read before the summaries were copied.
            IngestStates.frameSkipped(connection)

            val health = readSessionHealth(session, cut, atSeq = null, leakThresholdMs = 30_000)

            assertTrue(health.transport.deliveryUncertain)
            assertTrue(health.failuresAtLeast)
            assertEquals(NotEvaluatedReason.EVENTS_DROPPED, health.possiblyBlocked.reason)
        }
}
