package com.jh.proj.coroutineviz.routes

import java.util.concurrent.ConcurrentHashMap

/**
 * Open ingest sockets per session id, for this server process.
 *
 * Keyed by id rather than held on [com.jh.proj.coroutineviz.session.VizSession] because
 * the database session store builds a fresh `VizSession` on every lookup: a counter on
 * the instance the ingest route resolved is invisible to the instance another route
 * resolves. Checks reads this to tell a running app's live coroutines from leaked ones.
 */
object IngestConnections {
    private val open = ConcurrentHashMap<String, Int>()

    fun opened(sessionId: String) {
        open.merge(sessionId, 1, Int::plus)
    }

    fun closed(sessionId: String) {
        // Remove the entry when the last socket closes; never go below zero.
        open.computeIfPresent(sessionId) { _, count -> if (count > 1) count - 1 else null }
    }

    fun isConnected(sessionId: String): Boolean = (open[sessionId] ?: 0) > 0
}
