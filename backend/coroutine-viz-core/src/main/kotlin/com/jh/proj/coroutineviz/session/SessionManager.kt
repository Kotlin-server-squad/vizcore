package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.models.SessionInfo
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages active visualization sessions.
 *
 * Sessions are stored in memory and can be accessed across multiple API calls.
 * This allows clients to:
 * - Create a session
 * - Run scenarios in that session
 * - Stream events from that session via SSE
 * - Query the session snapshot
 *
 * Implements [SessionStoreInterface] so callers can depend on the abstraction
 * rather than this concrete singleton.
 */
object SessionManager : SessionStoreInterface {
    private val logger = LoggerFactory.getLogger(SessionManager::class.java)
    private val sessions = ConcurrentHashMap<String, VizSession>()

    private var maxEventsPerSession: Int = 100_000

    /**
     * Optional backing store. When set via [useStore] (e.g. the persistence
     * layer installs an Exposed store), SessionManager acts as a thin façade
     * delegating all lifecycle operations to it — every existing call site
     * (routes, metrics) keeps calling `SessionManager.*` unchanged.
     *
     * When null (the default), SessionManager uses its in-memory
     * [ConcurrentHashMap] exactly as before — zero behavior change (D-04a).
     *
     * The [onSessionCreated]/[onSessionClosed] callbacks still fire in both
     * modes so the metrics layer keeps working regardless of backing store.
     */
    @Volatile
    private var backingStore: SessionStoreInterface? = null

    /** Callback invoked when a new session is created. */
    var onSessionCreated: ((VizSession) -> Unit)? = null

    /** Callback invoked when a session is closed, with the session ID. */
    var onSessionClosed: ((String) -> Unit)? = null

    /**
     * Configure session defaults. Call before creating sessions.
     */
    fun configure(maxEventsPerSession: Int = 100_000) {
        this.maxEventsPerSession = maxEventsPerSession
        logger.info("SessionManager configured: maxEventsPerSession=$maxEventsPerSession")
    }

    /**
     * Install a backing [SessionStoreInterface] (e.g. a DB-backed store).
     * After this call, all lifecycle operations delegate to [store].
     * Pass null to revert to the default in-memory behavior (used by tests).
     */
    fun useStore(store: SessionStoreInterface?) {
        this.backingStore = store
        logger.info("SessionManager backing store set: ${store?.let { it::class.simpleName } ?: "in-memory (default)"}")
    }

    /**
     * Create a new visualization session.
     *
     * Satisfies the [SessionStoreInterface.createSession] contract.
     * The suspend modifier is accepted but this implementation does not
     * actually suspend — it completes synchronously.
     */
    override suspend fun createSession(name: String?): VizSession {
        backingStore?.let { store ->
            val session = store.createSession(name)
            logger.info("Created session (backing store): ${session.sessionId}")
            onSessionCreated?.invoke(session)
            return session
        }

        val sessionId =
            name?.let { "$it-${System.currentTimeMillis()}" }
                ?: "session-${System.currentTimeMillis()}"

        val session = VizSession(sessionId, maxEvents = maxEventsPerSession)
        sessions[sessionId] = session

        logger.info("Created session: $sessionId")
        onSessionCreated?.invoke(session)
        return session
    }

    /**
     * Get an existing session by ID.
     */
    override fun getSession(sessionId: String): VizSession? {
        backingStore?.let { return it.getSession(sessionId) }
        return sessions[sessionId]
    }

    /**
     * List all active sessions.
     */
    override fun listSessions(): List<SessionInfo> {
        backingStore?.let { return it.listSessions() }
        return sessions.values.map { session ->
            SessionInfo(
                sessionId = session.sessionId,
                coroutineCount = session.snapshot.coroutines.size,
                eventCount = session.store.all().size,
            )
        }
    }

    /**
     * Close and remove a session.
     * Retained for backwards compatibility — delegates to [deleteSession].
     */
    fun closeSession(sessionId: String): Boolean = deleteSession(sessionId)

    /**
     * Delete (close and remove) a session by ID.
     *
     * Cleans up session resources before removal. Satisfies the
     * [SessionStoreInterface.deleteSession] contract.
     */
    override fun deleteSession(sessionId: String): Boolean {
        backingStore?.let { store ->
            val deleted = store.deleteSession(sessionId)
            if (deleted) {
                logger.info("Closed session (backing store): $sessionId")
                onSessionClosed?.invoke(sessionId)
            }
            return deleted
        }

        val removed = sessions.remove(sessionId)
        if (removed != null) {
            removed.close() // Clean up session resources
            logger.info("Closed session: $sessionId")
            onSessionClosed?.invoke(sessionId)
            return true
        }
        return false
    }

    /**
     * Clear all sessions (useful for testing).
     */
    override fun clearAll() {
        backingStore?.let {
            it.clearAll()
            logger.info("Cleared all sessions (backing store)")
            return
        }
        val count = sessions.size
        sessions.clear()
        logger.info("Cleared all sessions: $count removed")
    }
}
