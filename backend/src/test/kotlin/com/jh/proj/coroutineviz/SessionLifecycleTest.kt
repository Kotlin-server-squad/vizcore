package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.models.SessionInfo
import com.jh.proj.coroutineviz.session.RetentionPolicy
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.SessionStoreInterface
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionLifecycleTest {
    @AfterEach
    fun tearDown() {
        SessionManager.clearAll()
    }

    /** Simple test store that lets us control createdAtMs via injected sessions. */
    private class TestSessionStore : SessionStoreInterface {
        val sessions = ConcurrentHashMap<String, VizSession>()

        fun addSession(id: String, createdAtMs: Long) {
            sessions[id] = VizSession(id, createdAtMs = createdAtMs)
        }

        override suspend fun createSession(name: String?): VizSession {
            val id = name ?: "session-${System.currentTimeMillis()}"
            val session = VizSession(id)
            sessions[id] = session
            return session
        }

        override fun getSession(sessionId: String) = sessions[sessionId]

        override fun listSessions(): List<SessionInfo> =
            sessions.values.map {
                SessionInfo(it.sessionId, it.snapshot.coroutines.size, it.store.all().size)
            }

        override fun deleteSession(sessionId: String): Boolean {
            val removed = sessions.remove(sessionId)
            removed?.close()
            return removed != null
        }

        override fun clearAll() {
            sessions.clear()
        }
    }

    @Test
    fun `retention policy removes expired sessions`() = runTest {
        val baseTime = 100_000L
        var fakeClock = baseTime
        val policy = RetentionPolicy(
            maxSessionAgeMs = 1000L,
            maxSessions = 100,
            clock = { fakeClock },
        )

        val store = TestSessionStore()
        store.addSession("old-1", createdAtMs = baseTime - 500)
        store.addSession("old-2", createdAtMs = baseTime - 600)
        assertEquals(2, store.listSessions().size)

        // Advance clock past TTL
        fakeClock = baseTime + 1500L
        val removed = policy.cleanup(store)

        assertEquals(2, removed)
        assertEquals(0, store.listSessions().size)
    }

    @Test
    fun `retention policy enforces max session count`() = runTest {
        val baseTime = 100_000L
        var fakeClock = baseTime
        val policy = RetentionPolicy(
            maxSessionAgeMs = 100_000L,
            maxSessions = 2,
            clock = { fakeClock },
        )

        val store = TestSessionStore()
        store.addSession("s1", createdAtMs = baseTime - 30)
        store.addSession("s2", createdAtMs = baseTime - 20)
        store.addSession("s3", createdAtMs = baseTime - 10)
        assertEquals(3, store.listSessions().size)

        val removed = policy.cleanup(store)

        assertEquals(1, removed)
        assertEquals(2, store.listSessions().size)
    }

    @Test
    fun `graceful shutdown clears all sessions`() = runTest {
        SessionManager.createSession("shutdown-1")
        SessionManager.createSession("shutdown-2")
        assertEquals(2, SessionManager.listSessions().size)

        SessionManager.clearAll()

        assertEquals(0, SessionManager.listSessions().size)
    }

    @Test
    fun `sessions have createdAtMs for TTL tracking`() = runTest {
        val before = System.currentTimeMillis()
        val session = SessionManager.createSession("ts-test")
        val after = System.currentTimeMillis()

        assertTrue(session.createdAtMs in before..after)
    }

    @Test
    fun `retention policy keeps non-expired sessions`() = runTest {
        val baseTime = 100_000L
        val fakeClock = baseTime
        val policy = RetentionPolicy(
            maxSessionAgeMs = 5000L,
            maxSessions = 100,
            clock = { fakeClock },
        )

        val store = TestSessionStore()
        store.addSession("young", createdAtMs = baseTime - 1000) // 1s old, TTL is 5s
        assertEquals(1, store.listSessions().size)

        val removed = policy.cleanup(store)

        assertEquals(0, removed)
        assertEquals(1, store.listSessions().size)
    }
}
