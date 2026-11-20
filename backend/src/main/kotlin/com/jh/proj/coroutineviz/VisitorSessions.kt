package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.WeakHashMap

private val logger = LoggerFactory.getLogger("PublicDemo")

/**
 * What public demo mode keeps in check about visitors' sessions: each takes one demo run, and
 * there are never more than [maxSessions] (`session.maxCount`, env `SESSION_MAX_COUNT`) of them.
 */
internal class VisitorSessions(
    private val maxSessions: Int,
) {
    /** Sessions a visitor's demo run is being started in right now; an entry goes with its session. */
    private val starting: MutableSet<VizSession> = Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap()))

    /**
     * Takes [session] for a visitor's demo run, or says no: it has events already, an app streams
     * into it, or another run is being started in it this moment. A session takes one run: a second
     * would add to what another visitor is watching, and a session run over and over never goes
     * idle, so it would outgrow SESSION_MAX_AGE_MS. "Run again" opens a new session.
     */
    fun claimForRun(session: VizSession): Boolean = !session.hasOpenIngestSocket && session.lastSeq == 0L && starting.add(session)

    /** The request that claimed [session] has its answer: a run that started left events in it. */
    fun runAnswered(session: VizSession) {
        starting.remove(session)
    }

    /**
     * Removes the sessions beyond [maxSessions], the least recently active first and never one an
     * app is streaming into. Called once a visitor's request has added a session, before it is
     * answered: the periodic sweep does the same, but only every SESSION_CHECK_INTERVAL_MS, and
     * many visitors can open many sessions in between.
     */
    @Synchronized
    fun trimToLimit() {
        val excess = SessionManager.listSessions().size - maxSessions
        if (excess <= 0) return
        SessionManager
            .listSessions()
            .mapNotNull { info -> SessionManager.getSession(info.sessionId)?.takeUnless { it.hasOpenIngestSocket } }
            .sortedBy { it.lastActivityAtMs }
            .take(excess)
            .forEach { session ->
                if (SessionManager.deleteSession(session.sessionId)) {
                    logger.debug("Removed session {}: more than {} sessions after a visitor added one", session.sessionId, maxSessions)
                }
            }
    }
}
