package com.jh.proj.coroutineviz.session

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A session-closed listener that throws must not keep the listeners after it from hearing about
 * the delete: open streams and ingest sockets wait on that notification to close.
 */
class SessionManagerCloseListenersTest {
    @AfterEach
    fun tearDown() {
        SessionManager.clearOnSessionClosedListeners()
        SessionManager.clearAll()
    }

    @Test
    fun `a failing close listener does not stop the ones after it`() =
        runBlocking {
            val sessionId = SessionManager.createSession("close-listeners").sessionId
            val heard = mutableListOf<String>()
            SessionManager.addOnSessionClosed { error("listener failure") }
            SessionManager.addOnSessionClosed { id -> heard += id }

            assertTrue(SessionManager.deleteSession(sessionId))

            assertEquals(listOf(sessionId), heard)
        }
}
