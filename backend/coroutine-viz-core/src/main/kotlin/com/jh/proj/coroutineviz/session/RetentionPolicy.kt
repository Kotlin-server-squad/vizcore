package com.jh.proj.coroutineviz.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Background retention policy that automatically cleans up old sessions.
 *
 * Periodically checks all sessions managed by a [SessionStoreInterface] and
 * removes those that exceed the maximum age or when the total session count
 * exceeds the configured limit.
 *
 * A session's age is measured from its LAST ACTIVITY
 * ([VizSession.lastActivityAtMs] — the last appended event or ingest socket
 * open/close), never from its creation, and a session that currently has an open
 * ingest socket ([VizSession.hasOpenIngestSocket]) is NEVER removed — not by age
 * and not by count. WHY: ageing by `createdAtMs` evicted a real app's session
 * after [maxSessionAgeMs] (1 h by default) while the app was still streaming, so
 * the client's next frame hit a session that no longer existed (#129). Idle
 * sessions are still evicted exactly as configured.
 *
 * Cleanup rules (applied in order):
 * 1. Remove all sessions idle for longer than [maxSessionAgeMs]
 * 2. If the remaining count still exceeds [maxSessions], remove the
 *    least-recently-active sessions until the count is at or below the limit
 *
 * Sessions with an open ingest socket are skipped by both phases, so the live
 * count can legitimately stay above [maxSessions] while that many clients are
 * connected — dropping a connected client's session is the worse failure.
 *
 * @property maxSessionAgeMs Maximum idle time of a session in milliseconds before
 *           it is eligible for removal. Defaults to 1 hour.
 * @property maxSessions Maximum number of sessions to retain. Defaults to 100.
 * @property checkIntervalMs How often (in milliseconds) the background cleanup
 *           runs. Defaults to 1 minute.
 * @property clock Function that returns the current time in milliseconds.
 *           Defaults to [System.currentTimeMillis]; override in tests for
 *           deterministic time control.
 */
class RetentionPolicy(
    private val maxSessionAgeMs: Long = 3_600_000L,
    private val maxSessions: Int = 100,
    private val checkIntervalMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(RetentionPolicy::class.java)
    private var job: Job? = null

    /**
     * Start the periodic cleanup coroutine.
     *
     * Launches a long-running coroutine in [scope] that calls [cleanup]
     * every [checkIntervalMs] milliseconds. If a cleanup cycle is already
     * running, this is a no-op.
     *
     * @param scope The coroutine scope to launch in (typically application scope)
     * @param sessionManager The session store to clean up
     */
    fun start(scope: CoroutineScope, sessionManager: SessionStoreInterface) {
        if (job?.isActive == true) return

        job = scope.launch {
            logger.info(
                "Retention policy started: maxAge={}ms, maxSessions={}, interval={}ms",
                maxSessionAgeMs, maxSessions, checkIntervalMs,
            )
            while (isActive) {
                delay(checkIntervalMs)
                val removed = cleanup(sessionManager)
                if (removed > 0) {
                    logger.info("Retention cleanup removed {} session(s)", removed)
                }
            }
        }
    }

    /**
     * Stop the periodic cleanup coroutine.
     *
     * Cancels the background job if it is running. Safe to call multiple times.
     */
    fun stop() {
        job?.cancel()
        job = null
        logger.info("Retention policy stopped")
    }

    /**
     * Run a single cleanup pass against the given session store.
     *
     * This method is safe to call from any context (it does not suspend).
     *
     * @param sessionManager The session store to clean up
     * @return The number of sessions that were removed
     */
    fun cleanup(sessionManager: SessionStoreInterface): Int {
        val now = clock()
        var removedCount = 0

        // Phase 1: remove sessions idle for longer than maxSessionAgeMs. A session
        // with an open ingest socket is live activity even between frames and is
        // never aged out (#129).
        val sessions = sessionManager.listSessions()
        for (info in sessions) {
            val session = sessionManager.getSession(info.sessionId) ?: continue
            if (session.hasOpenIngestSocket) continue
            val idleMs = now - session.lastActivityAtMs
            if (idleMs > maxSessionAgeMs) {
                if (sessionManager.deleteSession(info.sessionId)) {
                    removedCount++
                    logger.debug("Removed idle session {} (idle={}ms)", info.sessionId, idleMs)
                }
            }
        }

        // Phase 2: if still over maxSessions, remove the least-recently-active
        // first. Connected sessions are excluded from the candidates but still
        // counted against the limit, so newer arrivals never evict a live client.
        val remaining = sessionManager.listSessions()
        if (remaining.size > maxSessions) {
            val candidates = remaining.mapNotNull { info ->
                sessionManager.getSession(info.sessionId)
                    ?.takeUnless { it.hasOpenIngestSocket }
                    ?.let { session -> info.sessionId to session.lastActivityAtMs }
            }.sortedBy { it.second } // least recently active first

            val excessCount = (remaining.size - maxSessions).coerceAtMost(candidates.size)
            for (i in 0 until excessCount) {
                val sessionId = candidates[i].first
                if (sessionManager.deleteSession(sessionId)) {
                    removedCount++
                    logger.debug("Removed excess session {}", sessionId)
                }
            }
        }

        return removedCount
    }
}
