package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.appJson
import com.jh.proj.coroutineviz.health.HealthMode
import com.jh.proj.coroutineviz.health.SessionHealth
import com.jh.proj.coroutineviz.models.CoroutineState
import com.jh.proj.coroutineviz.module
import com.jh.proj.coroutineviz.routes.HealthEvents.sec
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The read skeleton of every follow-up read: current, replay and bound views (plan E-1..E-3, E-17). */
class SessionViewReadsTest {
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

    /**
     * A session whose store keeps 5 events: "root" is created, started and suspended first (seqs 1..3), then 5 events of
     * "other" push those out of the store.
     */
    private fun trimmedSession(): VizSession {
        SessionManager.configure(maxEventsPerSession = 5)
        val session = runBlocking { SessionManager.createSession("app-views") }
        val id = session.sessionId
        session.send(HealthEvents.created(id, "root", sec(1)))
        session.send(HealthEvents.started(id, "root", sec(1)))
        session.send(HealthEvents.suspended(id, "root", sec(2)))
        session.send(HealthEvents.created(id, "other", sec(3)))
        session.send(HealthEvents.started(id, "other", sec(3)))
        session.send(HealthEvents.suspended(id, "other", sec(4)))
        session.send(HealthEvents.completed(id, "other", sec(5)))
        session.send(HealthEvents.created(id, "third", sec(6)))
        return session
    }

    private fun view(
        session: VizSession,
        binding: ViewBinding,
    ) = readView(session, IngestStates.snapshot(session.sessionId), binding, DEFAULT_LEAK_MS)

    @Test
    fun `the current view knows every coroutine and holds the events up to its seq`() {
        val session = trimmedSession()
        val v = view(session, ViewBinding.Current)
        assertTrue(v.bound.unknownIds.isEmpty())
        assertEquals(session.lastSeq, v.seq)
        assertTrue(v.events.all { it.seq <= v.seq })
    }

    @Test
    fun `replay of a trimmed creation unchanged since the cursor reads its real state, not created`() {
        val session = trimmedSession()
        val v = view(session, ViewBinding.Replay(session.lastSeq))
        assertEquals(HealthMode.Replay(session.lastSeq), v.input.mode)
        assertEquals(
            CoroutineState.SUSPENDED,
            v.input.summaries
                .get("root")!!
                .state,
        )
        assertEquals(0, v.unknownAtSeq)
    }

    @Test
    fun `a bound view answers from its kept cut after more events and a trim`() {
        val session = trimmedSession()
        val current = view(session, ViewBinding.Current)
        HealthCuts.remember(current.cut(System.currentTimeMillis()))
        val id = session.sessionId
        session.send(HealthEvents.started(id, "root", sec(7))) // root changes after the cut
        session.send(HealthEvents.created(id, "fourth", sec(8)))

        val bound = view(session, ViewBinding.Bound(current.seq, null, null))
        assertTrue(bound.bound.unknownIds.isEmpty())
        assertEquals(
            CoroutineState.SUSPENDED,
            bound.input.summaries
                .get("root")!!
                .state,
        )
        bound.requireNoUnknown()
    }

    @Test
    fun `a bound view that missed its cut cannot answer while a coroutine changed since is unknown`() {
        val session = trimmedSession()
        val seq = session.lastSeq
        session.send(HealthEvents.started(session.sessionId, "root", sec(7)))

        val bound = view(session, ViewBinding.Bound(seq, null, null))
        assertEquals(setOf("root"), bound.bound.unknownIds)
        assertThrows<StaleViewException> { bound.requireNoUnknown() }
        assertThrows<StaleViewException> { bound.requireKnown("root") }
        bound.requireKnown("third")
    }

    @Test
    fun `a bound view that missed its cut is stale once the transport moved on`() {
        val session = trimmedSession()
        val connection = IngestStates.connectionOpened(session.sessionId, firstSeq = 1)
        IngestStates.frameSkipped(connection) // revision 1
        assertThrows<StaleViewException> { view(session, ViewBinding.Bound(session.lastSeq, null, transportRevision = 0)) }
        view(session, ViewBinding.Bound(session.lastSeq, null, transportRevision = 1))
    }

    @Test
    fun `replay is lenient and counts what it does not know`() {
        val session = trimmedSession()
        val seq = session.lastSeq
        session.send(HealthEvents.started(session.sessionId, "root", sec(7)))
        val v = view(session, ViewBinding.Replay(seq))
        v.requireNoUnknown()
        assertEquals(1, v.unknownAtSeq)
        assertTrue(v.isPartial("root"))
    }

    @Test
    fun `the live health keeps its cut for the follow-up reads`() =
        testApplication {
            application { module() }
            val id = client.createSessionNamed("app-cut")
            SessionManager.getSession(id)!!.send(HealthEvents.created(id, "a", sec(1)))
            val health = appJson.decodeFromString(SessionHealth.serializer(), client.get("/api/sessions/$id/health").bodyAsText())
            val cut = assertNotNull(HealthCuts.find(id, health.asOfSeq))
            assertEquals(health.transport, cut.ingest.transport)
        }
}
