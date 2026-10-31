package com.jh.proj.coroutineviz.routes

import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Retention decides whether a session is streamed into and deletes it as one step (#261): a
 * socket that registers in between must wait, or it would land in a session about to be deleted.
 */
class IngestConnectionsTest {
    @Test
    fun `a socket cannot register while a retention decision is in progress`() {
        val sessionId = "s-${UUID.randomUUID()}"
        val decisionStarted = CountDownLatch(1)
        val endDecision = CountDownLatch(1)
        val registered = AtomicBoolean(false)

        val decision =
            thread {
                IngestConnections.whileNoSocketRegisters {
                    decisionStarted.countDown()
                    endDecision.await()
                }
            }
        decisionStarted.await()
        val socket =
            thread {
                IngestConnections.opened(sessionId)
                registered.set(true)
            }
        // An unblocked registration takes microseconds.
        socket.join(300)
        assertFalse(registered.get(), "the socket must wait for the decision to finish")
        assertFalse(IngestConnections.isConnected(sessionId))

        endDecision.countDown()
        decision.join(5_000)
        socket.join(5_000)
        assertTrue(registered.get(), "the socket registers once the decision is over")
        assertTrue(IngestConnections.isConnected(sessionId))
        IngestConnections.closed(sessionId)
    }

    @Test
    fun `sockets registering at the same time do not wait for each other`() {
        val sessionId = "s-${UUID.randomUUID()}"
        val sockets = List(8) { thread { IngestConnections.opened(sessionId) } }
        sockets.forEach { it.join(5_000) }

        assertTrue(sockets.none { it.isAlive })
        repeat(8) { IngestConnections.closed(sessionId) }
        assertFalse(IngestConnections.isConnected(sessionId))
    }
}
