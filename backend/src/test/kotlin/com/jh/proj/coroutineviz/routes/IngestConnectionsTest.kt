package com.jh.proj.coroutineviz.routes

import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Retention decides whether a session is streamed into and deletes it as one step (#261): a
 * socket that registers in between must wait, and must not register into a session the decision
 * deleted.
 */
class IngestConnectionsTest {
    /**
     * Holds a retention decision open on another thread, runs [whileHeld] once a socket is waiting
     * for it, then ends the decision. The decision is always ended and joined, so a failing check
     * cannot leave the process-wide lock held for the tests that follow.
     */
    private fun duringDecision(
        socket: () -> Unit,
        insideDecision: () -> Unit = {},
        whileHeld: () -> Unit,
    ) {
        val decisionStarted = CountDownLatch(1)
        val endDecision = CountDownLatch(1)
        val decision =
            thread {
                IngestConnections.whileNoSocketRegisters {
                    decisionStarted.countDown()
                    endDecision.await()
                    insideDecision()
                }
            }
        var socketThread: Thread? = null
        try {
            assertTrue(decisionStarted.await(5, TimeUnit.SECONDS))
            socketThread = thread { socket() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!IngestConnections.registrationsWaiting && System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue(IngestConnections.registrationsWaiting, "the socket must be waiting for the decision")
            whileHeld()
        } finally {
            endDecision.countDown()
            decision.join(5_000)
            socketThread?.join(5_000)
        }
    }

    @Test
    fun `a socket cannot register while a retention decision is in progress`() {
        val sessionId = "s-${UUID.randomUUID()}"
        val registered = AtomicBoolean(false)

        duringDecision(
            socket = {
                IngestConnections.openedIfPresent(sessionId) { true }
                registered.set(true)
            },
        ) {
            assertFalse(registered.get())
            assertFalse(IngestConnections.isConnected(sessionId))
        }

        assertTrue(registered.get(), "the socket registers once the decision is over")
        assertTrue(IngestConnections.isConnected(sessionId))
        IngestConnections.closed(sessionId)
    }

    @Test
    fun `a socket whose session the decision deleted does not register`() {
        val sessionId = "s-${UUID.randomUUID()}"
        val deleted = AtomicBoolean(false)
        val result = AtomicReference<Boolean>()

        duringDecision(
            socket = { result.set(IngestConnections.openedIfPresent(sessionId) { !deleted.get() }) },
            insideDecision = { deleted.set(true) },
        ) {}

        assertEquals(false, result.get(), "the session is gone, so the socket must not register")
        assertFalse(IngestConnections.isConnected(sessionId))
    }

    @Test
    fun `sockets registering at the same time do not wait for each other`() {
        val sessionId = "s-${UUID.randomUUID()}"
        val sockets = List(8) { thread { IngestConnections.openedIfPresent(sessionId) { true } } }
        sockets.forEach { it.join(5_000) }

        assertTrue(sockets.none { it.isAlive })
        repeat(8) { IngestConnections.closed(sessionId) }
        assertFalse(IngestConnections.isConnected(sessionId))
    }
}
