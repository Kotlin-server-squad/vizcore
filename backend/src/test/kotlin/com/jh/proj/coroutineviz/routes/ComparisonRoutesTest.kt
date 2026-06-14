package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.events.dispatcher.ThreadAssigned
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ComparisonRoutesTest {
    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
    }

    @AfterEach
    fun tearDown() {
        SessionManager.clearAll()
    }

    private fun threadAssigned(
        sessionId: String,
        seq: Long,
        coroutineId: String,
        threadId: Long,
    ) = ThreadAssigned(
        sessionId = sessionId,
        seq = seq,
        tsNanos = seq * 1000,
        coroutineId = coroutineId,
        jobId = "job-$coroutineId",
        parentCoroutineId = null,
        scopeId = "scope-1",
        label = null,
        threadId = threadId,
        threadName = "thread-$threadId",
        dispatcherName = "Default",
    )

    /** Seed session A with 1 distinct thread and session B with 2 distinct threads. */
    private suspend fun seedTwoSessions(): Pair<VizSession, VizSession> {
        val sessionA = SessionManager.createSession("cmp-a")
        val sessionB = SessionManager.createSession("cmp-b")

        sessionA.send(threadAssigned(sessionA.sessionId, 1, "c-1", threadId = 10))

        sessionB.send(threadAssigned(sessionB.sessionId, 1, "c-1", threadId = 10))
        sessionB.send(threadAssigned(sessionB.sessionId, 2, "c-2", threadId = 11))

        return sessionA to sessionB
    }

    @Test
    fun `GET sessions compare returns 200 with thread delta and existing diff fields`() =
        testApplication {
            application { module() }

            val (sessionA, sessionB) = seedTwoSessions()

            val response = client.get("/api/sessions/compare?a=${sessionA.sessionId}&b=${sessionB.sessionId}")
            assertEquals(HttpStatusCode.OK, response.status)

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(sessionA.sessionId, body["sessionA"]?.jsonPrimitive?.content)
            assertEquals(sessionB.sessionId, body["sessionB"]?.jsonPrimitive?.content)
            // B has 2 distinct threads, A has 1 -> delta 1
            assertEquals(1, body["distinctThreadsDiff"]?.jsonPrimitive?.int)
            // Existing aggregate diff fields must still be present
            assertNotNull(body["coroutineCountDiff"], "coroutineCountDiff must be present")
            assertNotNull(body["eventCountDiff"], "eventCountDiff must be present")
            assertNotNull(body["totalDurationDiffNanos"], "totalDurationDiffNanos must be present")
            assertEquals(1, body["eventCountDiff"]?.jsonPrimitive?.int) // B(2) - A(1)
        }

    @Test
    fun `GET sessions compare returns 404 for an unknown session id`() =
        testApplication {
            application { module() }

            val sessionA = SessionManager.createSession("cmp-a")
            sessionA.send(threadAssigned(sessionA.sessionId, 1, "c-1", threadId = 10))

            val response = client.get("/api/sessions/compare?a=${sessionA.sessionId}&b=does-not-exist")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `GET sessions compare returns 400 when b is missing`() =
        testApplication {
            application { module() }

            val sessionA = SessionManager.createSession("cmp-a")

            val response = client.get("/api/sessions/compare?a=${sessionA.sessionId}")
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
}
