package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.models.SessionInfo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #129: retention must age a session by its LAST ACTIVITY, and must never evict a
 * session whose client still holds an ingest socket open.
 *
 * Before this, [RetentionPolicy] aged sessions by [VizSession.createdAtMs], so a
 * real app streaming through the ingest socket had its session deleted after
 * `session.maxAgeMs` (1 h by default) WHILE STILL SENDING; the client's next frame
 * then hit a session that no longer existed. The eviction also happened by count:
 * enough newer sessions pushed the live one out.
 *
 * Every case here drives time through an injected clock (`{ now }`) shared by the
 * policy and the sessions, so nothing sleeps and the idle windows are exact.
 */
class RetentionActivityTest {

    private val opened = mutableListOf<VizSession>()

    @AfterEach
    fun tearDown() {
        opened.forEach { it.close() }
        opened.clear()
    }

    /**
     * Lightweight in-memory store, same shape as the one in [RetentionPolicyTest]:
     * the policy only needs list/get/delete, and this avoids the [SessionManager]
     * singleton.
     */
    private class TestSessionStore : SessionStoreInterface {
        private val sessions = ConcurrentHashMap<String, VizSession>()

        override suspend fun createSession(name: String?): VizSession {
            val id = name ?: "session-${sessions.size}"
            return VizSession(id).also { sessions[id] = it }
        }

        fun addSession(session: VizSession) {
            sessions[session.sessionId] = session
        }

        override fun getSession(sessionId: String): VizSession? = sessions[sessionId]

        override fun listSessions(): List<SessionInfo> =
            sessions.values.map { session ->
                SessionInfo(
                    sessionId = session.sessionId,
                    coroutineCount = session.snapshot.coroutines.size,
                    eventCount = session.store.all().size,
                )
            }

        override fun deleteSession(sessionId: String): Boolean {
            val removed = sessions.remove(sessionId)
            removed?.close()
            return removed != null
        }

        override fun clearAll() {
            sessions.values.forEach { it.close() }
            sessions.clear()
        }

        fun size(): Int = sessions.size
    }

    /** A session wired to the test clock; closed by [tearDown]. */
    private fun session(
        id: String,
        clock: () -> Long,
        createdAtMs: Long,
    ): VizSession = VizSession(id, clock = clock, createdAtMs = createdAtMs).also { opened += it }

    private fun event(session: VizSession): CoroutineCreated =
        CoroutineCreated(
            sessionId = session.sessionId,
            seq = session.nextSeq(),
            tsNanos = System.nanoTime(),
            coroutineId = "c-${session.sessionId}",
            jobId = "job-${session.sessionId}",
            parentCoroutineId = null,
            scopeId = "scope-retention-activity",
            label = null,
        )

    @Test
    fun `a session with an open ingest socket survives far past maxAge`() {
        var now = 1_000_000L
        val store = TestSessionStore()
        val policy = RetentionPolicy(
            maxSessionAgeMs = 60_000L,
            maxSessions = 100,
            clock = { now },
        )

        val live = session("live", clock = { now }, createdAtMs = now)
        live.ingestSocketOpened()
        store.addSession(live)

        // Ten minutes with no frame at all — the exact #129 shape: a connected app
        // that is simply quiet between bursts.
        now += 600_000L

        assertEquals(0, policy.cleanup(store), "A connected session must not be aged out")
        assertEquals(1, store.size())
        assertNotNull(store.getSession("live"), "live session must still exist")
        assertTrue(live.hasOpenIngestSocket)
    }

    @Test
    fun `a connected session is not evicted by the count limit, an idle one is instead`() {
        var now = 1_000_000L
        val store = TestSessionStore()
        val policy = RetentionPolicy(
            maxSessionAgeMs = 3_600_000L, // nothing expires by age here
            maxSessions = 2,
            clock = { now },
        )

        // The connected session is the LEAST recently active of the three, so a
        // purely activity-ordered eviction would pick it first.
        val live = session("live", clock = { now }, createdAtMs = now)
        live.ingestSocketOpened() // stamps activity at 1_000_000
        // Everything else starts its life 50 s later, so the connected session ends
        // up with the oldest activity stamp of the three.
        now += 50_000L
        val idleOld = session("idle-old", clock = { now }, createdAtMs = now - 40_000L)
        val idleNew = session("idle-new", clock = { now }, createdAtMs = now - 10_000L)
        store.addSession(live)
        store.addSession(idleOld)
        store.addSession(idleNew)

        // 3 sessions, maxSessions = 2 => exactly one eviction, and it must be the
        // least-recently-active DISCONNECTED one.
        val removed = policy.cleanup(store)

        assertEquals(1, removed, "Exactly one session is over the limit")
        assertNull(store.getSession("idle-old"), "the oldest idle session is the candidate")
        assertNotNull(store.getSession("live"), "a connected session is never evicted by count")
        assertNotNull(store.getSession("idle-new"), "the newest idle session stays")
        assertTrue(
            live.lastActivityAtMs < idleNew.lastActivityAtMs,
            "the surviving connected session really is the least recently active",
        )
    }

    @Test
    fun `an idle session past maxAge is still evicted while a connected one of the same age survives`() {
        var now = 1_000_000L
        val store = TestSessionStore()
        val policy = RetentionPolicy(
            maxSessionAgeMs = 60_000L,
            maxSessions = 100,
            clock = { now },
        )

        val live = session("live", clock = { now }, createdAtMs = now)
        live.ingestSocketOpened()
        val idle = session("idle", clock = { now }, createdAtMs = now)
        store.addSession(live)
        store.addSession(idle)

        // Both sessions last saw activity at the same instant; only the socket differs.
        assertEquals(live.lastActivityAtMs, idle.lastActivityAtMs)
        now += 120_000L

        val removed = policy.cleanup(store)

        assertEquals(1, removed, "retention still evicts idle sessions as configured")
        assertNull(store.getSession("idle"), "the disconnected session is aged out")
        assertNotNull(store.getSession("live"), "the connected session is not")
    }

    @Test
    fun `a long-lived session with a recent event is aged by its activity, not its creation`() {
        var now = 1_000_000L
        val store = TestSessionStore()
        val policy = RetentionPolicy(
            maxSessionAgeMs = 60_000L,
            maxSessions = 100,
            clock = { now },
        )

        // Created well over an hour ago, with no ingest socket held open at all:
        // only the recent append may keep it alive.
        val busy = session("busy", clock = { now }, createdAtMs = now - 7_200_000L)
        store.addSession(busy)
        busy.send(event(busy))

        assertFalse(busy.hasOpenIngestSocket, "this case must not lean on socket tracking")
        assertEquals(now, busy.lastActivityAtMs, "send() stamps the activity clock")
        assertEquals(0, policy.cleanup(store), "a session that just sent an event is not idle")
        assertNotNull(store.getSession("busy"))

        // ...and once it does go quiet for longer than maxAge, it is evicted.
        now += 120_000L
        assertEquals(1, policy.cleanup(store), "the same session is evicted once idle")
        assertNull(store.getSession("busy"))
    }

    @Test
    fun `closing the ingest socket makes the session evictable and starts the idle clock at the close`() {
        var now = 1_000_000L
        val store = TestSessionStore()
        val policy = RetentionPolicy(
            maxSessionAgeMs = 60_000L,
            maxSessions = 100,
            clock = { now },
        )

        val s = session("reconnecting", clock = { now }, createdAtMs = now)
        s.ingestSocketOpened()
        store.addSession(s)

        // Connected and quiet for ten minutes, then the client disconnects.
        now += 600_000L
        s.ingestSocketClosed()

        assertFalse(s.hasOpenIngestSocket, "the socket is no longer held open")
        assertEquals(now, s.lastActivityAtMs, "the close itself is activity")

        // The session is 600 s old and quiet, but only just disconnected: the idle
        // clock runs from the close, so it is NOT immediately evicted.
        assertEquals(0, policy.cleanup(store), "idle time is measured from the close")
        now += 30_000L
        assertEquals(0, policy.cleanup(store), "30 s idle is still inside maxAge")

        // Past maxAge measured from the close, it is evicted like any idle session.
        now += 40_000L
        assertEquals(1, policy.cleanup(store), "70 s after the close it ages out")
        assertNull(store.getSession("reconnecting"))
    }
}
