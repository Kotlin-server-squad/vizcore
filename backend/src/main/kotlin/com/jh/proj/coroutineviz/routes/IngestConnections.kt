package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.persistence.StreamingSessions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Open ingest sockets per session id, for this server process.
 *
 * Keyed by id rather than held on [com.jh.proj.coroutineviz.session.VizSession] because
 * the database session store builds a fresh `VizSession` on every lookup: a counter on
 * the instance the ingest route resolved is invisible to the instance another route
 * resolves. Checks reads this to tell a running app's live coroutines from leaked ones.
 *
 * Process-local, like the rest of a live session's state (event bus, SSE streams): ingest
 * and Checks must reach the same backend process.
 */
object IngestConnections : StreamingSessions {
    private val open = ConcurrentHashMap<String, Int>()

    // Sockets register under the read lock, so they never wait for each other; a retention decision
    // holds the write lock (#261). Closing needs no lock: a socket that closes during a decision only
    // keeps its session until the next one.
    private val registration = ReentrantReadWriteLock()

    /**
     * Registers a socket for [sessionId] if the session still [exists], and says whether it did.
     * Checked under the read lock: a retention decision that deleted the session has committed by
     * then, and one that has not started yet sees this socket and keeps the session (#261).
     */
    fun openedIfPresent(
        sessionId: String,
        exists: () -> Boolean,
    ): Boolean =
        registration.read {
            if (!exists()) return@read false
            open.merge(sessionId, 1, Int::plus)
            true
        }

    /** Whether a socket is waiting for a retention decision to end; tests synchronise on it. */
    internal val registrationsWaiting: Boolean get() = registration.hasQueuedThreads()

    fun closed(sessionId: String) {
        // Remove the entry when the last socket closes; never go below zero.
        open.computeIfPresent(sessionId) { _, count -> if (count > 1) count - 1 else null }
    }

    fun isConnected(sessionId: String): Boolean = (open[sessionId] ?: 0) > 0

    override fun isStreaming(sessionId: String): Boolean = isConnected(sessionId)

    /** Blocks [opened] while [block] runs; retention runs one short database delete in it, once an hour. */
    override fun <T> whileNoSocketRegisters(block: () -> T): T = registration.write(block)
}
