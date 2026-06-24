package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.models.SessionInfo
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

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

    /**
     * Legacy single-slot callback invoked when a new session is created.
     *
     * Retained for backward compatibility (direct-assignment callers and tests
     * still read/write it). When set, it fires FIRST on every createSession,
     * before any listener registered via [addOnSessionCreated]. New subsystems
     * should prefer [addOnSessionCreated] so multiple wirings compose instead of
     * clobbering each other (Phase 6 RCO-01).
     */
    var onSessionCreated: ((VizSession) -> Unit)? = null

    /**
     * Legacy single-slot callback invoked when a session is closed, with the
     * session ID. Retained for backward compatibility; fires FIRST on every
     * deleteSession, before any listener registered via [addOnSessionClosed].
     * New subsystems should prefer [addOnSessionClosed].
     */
    var onSessionClosed: ((String) -> Unit)? = null

    /**
     * Composable session-created listeners. Each is invoked (in registration
     * order) on every createSession AFTER the legacy [onSessionCreated] slot,
     * so multiple subsystems (metrics, instrumentation sources, …) can wire into
     * session creation without clobbering one another (Phase 6 RCO-01,
     * Research Pitfall 3). [CopyOnWriteArrayList] keeps iteration thread-safe
     * against concurrent registration.
     */
    private val onSessionCreatedListeners = CopyOnWriteArrayList<(VizSession) -> Unit>()

    /**
     * Composable session-closed listeners, invoked (in registration order) on
     * every deleteSession AFTER the legacy [onSessionClosed] slot. This is the
     * teardown hook an [com.jh.proj.coroutineviz.session.source.InstrumentationSource]
     * registers its `stop()` against (e.g. Plan 06-02 DebugProbesSource), so a
     * JVM-global ref-counted install is always released when a session is closed
     * or evicted — no long-lived leak.
     */
    private val onSessionClosedListeners = CopyOnWriteArrayList<(String) -> Unit>()

    /**
     * Register a listener fired on every session creation. Composes with the
     * legacy [onSessionCreated] slot and any other registered listeners — none
     * clobbers another. Listeners fire in registration order.
     */
    fun addOnSessionCreated(listener: (VizSession) -> Unit) {
        onSessionCreatedListeners.add(listener)
    }

    /**
     * Register a listener fired on every session close/delete (both the
     * backing-store and in-memory branches). Composes with the legacy
     * [onSessionClosed] slot and any other registered listeners. Listeners fire
     * in registration order. Sources use this to wire `stop()`/teardown.
     */
    fun addOnSessionClosed(listener: (String) -> Unit) {
        onSessionClosedListeners.add(listener)
    }

    /** Remove all listeners registered via [addOnSessionCreated]. Test helper. */
    fun clearOnSessionCreatedListeners() {
        onSessionCreatedListeners.clear()
    }

    /** Remove all listeners registered via [addOnSessionClosed]. Test helper. */
    fun clearOnSessionClosedListeners() {
        onSessionClosedListeners.clear()
    }

    /**
     * Reset both created- and closed-listener registries. SessionManager is an
     * object singleton, so tests sharing it must reset listener state between
     * runs to avoid cross-test accretion/flake (06-REVIEWS.md listener-reset).
     */
    fun clearSessionListeners() {
        clearOnSessionCreatedListeners()
        clearOnSessionClosedListeners()
    }

    /**
     * Fire the legacy created-slot then every registered created-listener,
     * in registration order. Centralizes the composition so both createSession
     * branches stay identical.
     */
    private fun fireSessionCreated(session: VizSession) {
        onSessionCreated?.invoke(session)
        onSessionCreatedListeners.forEach { it(session) }
    }

    /**
     * Fire the legacy closed-slot then every registered close-listener, in
     * registration order. Centralizes the composition so both deleteSession
     * branches stay identical.
     */
    private fun fireSessionClosed(sessionId: String) {
        onSessionClosed?.invoke(sessionId)
        onSessionClosedListeners.forEach { it(sessionId) }
    }

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
     * Expose the installed backing store (or null for the in-memory default).
     *
     * Used by the route layer to detect a tenant-scoped store and apply the
     * tenant filter on reads/creation. Kept as the abstract
     * [SessionStoreInterface] so no backend/tenancy types leak into the SDK;
     * callers narrow with `as?`.
     */
    fun backingStore(): SessionStoreInterface? = backingStore

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
            fireSessionCreated(session)
            return session
        }

        val sessionId =
            name?.let { "$it-${System.currentTimeMillis()}" }
                ?: "session-${System.currentTimeMillis()}"

        val session = VizSession(sessionId, maxEvents = maxEventsPerSession)
        sessions[sessionId] = session

        logger.info("Created session: $sessionId")
        fireSessionCreated(session)
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
                fireSessionClosed(sessionId)
            }
            return deleted
        }

        val removed = sessions.remove(sessionId)
        if (removed != null) {
            removed.close() // Clean up session resources
            logger.info("Closed session: $sessionId")
            fireSessionClosed(sessionId)
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
