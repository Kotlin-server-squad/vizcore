package com.jh.proj.coroutineviz.session.source

import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves the composable listener registries on [SessionManager] (Phase 6 RCO-01,
 * Research Pitfall 3): adding a listener via [SessionManager.addOnSessionCreated]
 * / [SessionManager.addOnSessionClosed] must NOT clobber the legacy single-slot
 * `onSessionCreated` / `onSessionClosed` callbacks — both must fire, in order.
 */
class SessionCreatedListenerCompositionTest {
    @BeforeEach
    fun setUp() {
        SessionManager.clearAll()
        SessionManager.clearSessionListeners()
        SessionManager.onSessionCreated = null
        SessionManager.onSessionClosed = null
    }

    @AfterEach
    fun tearDown() {
        SessionManager.clearAll()
        SessionManager.clearSessionListeners()
        SessionManager.onSessionCreated = null
        SessionManager.onSessionClosed = null
    }

    @Test
    fun `addOnSessionCreated listener fires on createSession`() =
        runTest {
            var fired: VizSession? = null
            SessionManager.addOnSessionCreated { fired = it }

            val session = SessionManager.createSession("created-listener")

            assertEquals(session.sessionId, fired?.sessionId, "registered created-listener must fire")
        }

    @Test
    fun `legacy onSessionCreated slot AND a registered listener both fire (no clobber)`() =
        runTest {
            var legacyFired: VizSession? = null
            var registeredFired: VizSession? = null
            SessionManager.onSessionCreated = { legacyFired = it }
            SessionManager.addOnSessionCreated { registeredFired = it }

            val session = SessionManager.createSession("compose-created")

            assertEquals(session.sessionId, legacyFired?.sessionId, "legacy slot must still fire")
            assertEquals(session.sessionId, registeredFired?.sessionId, "registered listener must also fire")
        }

    @Test
    fun `two created-listeners both fire in registration order`() =
        runTest {
            val order = mutableListOf<String>()
            SessionManager.addOnSessionCreated { order.add("first") }
            SessionManager.addOnSessionCreated { order.add("second") }

            SessionManager.createSession("order-created")

            assertEquals(listOf("first", "second"), order, "listeners fire in registration order")
        }

    @Test
    fun `addOnSessionClosed listener fires on deleteSession with the correct sessionId`() =
        runTest {
            var closedId: String? = null
            SessionManager.addOnSessionClosed { closedId = it }

            val session = SessionManager.createSession("closed-listener")
            SessionManager.deleteSession(session.sessionId)

            assertEquals(session.sessionId, closedId, "close-listener must receive the closed sessionId")
        }

    @Test
    fun `legacy onSessionClosed slot AND a registered close-listener both fire (no clobber)`() =
        runTest {
            var legacyClosed: String? = null
            var registeredClosed: String? = null
            SessionManager.onSessionClosed = { legacyClosed = it }
            SessionManager.addOnSessionClosed { registeredClosed = it }

            val session = SessionManager.createSession("compose-closed")
            SessionManager.deleteSession(session.sessionId)

            assertEquals(session.sessionId, legacyClosed, "legacy close slot must still fire")
            assertEquals(session.sessionId, registeredClosed, "registered close-listener must also fire")
        }

    @Test
    fun `clearSessionListeners resets both registries`() =
        runTest {
            var createdFired = false
            var closedFired = false
            SessionManager.addOnSessionCreated { createdFired = true }
            SessionManager.addOnSessionClosed { closedFired = true }

            SessionManager.clearSessionListeners()

            val session = SessionManager.createSession("after-clear")
            SessionManager.deleteSession(session.sessionId)

            assertTrue(!createdFired, "cleared created-listener must not fire")
            assertTrue(!closedFired, "cleared close-listener must not fire")
        }

    @Test
    fun `no listeners and no legacy slot is a safe no-op`() =
        runTest {
            // Both legacy slots null and both registries empty: createSession/deleteSession
            // must not throw.
            assertNull(SessionManager.onSessionCreated)
            assertNull(SessionManager.onSessionClosed)

            val session = SessionManager.createSession("noop")
            assertEquals(true, SessionManager.deleteSession(session.sessionId))
        }
}
