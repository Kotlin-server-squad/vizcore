package com.jh.proj.coroutineviz.persistence

import com.jh.proj.coroutineviz.auth.TenantContext
import com.jh.proj.coroutineviz.auth.TenantScopedSessionStore
import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.models.SessionInfo
import com.jh.proj.coroutineviz.persistence.tables.EventsTable
import com.jh.proj.coroutineviz.persistence.tables.SessionsTable
import com.jh.proj.coroutineviz.session.SessionStoreInterface
import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.session.slugifySessionName
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * DB-backed [SessionStoreInterface] (PERS-01). Sessions are persisted as rows
 * in `sessions`; each session's events live in `events` via [ExposedEventStore].
 *
 * Every [VizSession] this store hands out is built with an event-store factory
 * that returns an [ExposedEventStore] scoped to the session id, so writes flow
 * through to the DB (the in-memory bounded store is bypassed for DB sessions).
 *
 * All queries use the Exposed DSL — ids are bound as parameters, never
 * string-concatenated (T-03-01). `deleteSession` relies on the FK cascade to
 * remove the session's events and shares.
 */
class ExposedSessionStore(
    private val db: Database,
    private val maxEvents: Int = 100_000,
) : SessionStoreInterface,
    TenantScopedSessionStore {
    private val logger = LoggerFactory.getLogger(ExposedSessionStore::class.java)

    // -- SessionStoreInterface: unscoped (global) — the legacy / auth-off path -----

    override suspend fun createSession(name: String?): VizSession = createSession(name, TenantContext.Unscoped)

    override fun getSession(sessionId: String): VizSession? = getSession(sessionId, TenantContext.Unscoped)

    override fun listSessions(): List<SessionInfo> = listSessions(TenantContext.Unscoped)

    override fun deleteSession(sessionId: String): Boolean = deleteSession(sessionId, TenantContext.Unscoped)

    // -- TenantScopedSessionStore: tenant filter applied on EVERY read path --------

    @OptIn(ExperimentalTime::class)
    override suspend fun createSession(
        name: String?,
        tenant: TenantContext,
    ): VizSession {
        // Slugify the name into the id (shared core helper — both minting sites use
        // ONE sanitizer so they cannot drift). The RAW name is preserved untouched in
        // the `name` column below; only the URL-embedded id is made URL-safe.
        val sessionId =
            name?.let { "${slugifySessionName(it)}-${System.currentTimeMillis()}" }
                ?: "session-${System.currentTimeMillis()}"

        // Persist the owning tenant id when the caller is scoped; Admin/Unscoped
        // creation is global (null tenant) — only Scoped callers stamp ownership.
        val owner = (tenant as? TenantContext.Scoped)?.tenantId

        val now = Clock.System.now()
        transaction(db) {
            SessionsTable.insert {
                it[id] = sessionId
                it[SessionsTable.name] = name
                it[createdAt] = now
                it[scenario] = null
                it[tenantId] = owner
                it[metadata] = null
            }
        }
        logger.info("Created session (db): $sessionId (tenant=${owner ?: "<global>"})")
        return buildSession(sessionId, now.toEpochMilliseconds())
    }

    override fun getSession(
        sessionId: String,
        tenant: TenantContext,
    ): VizSession? = createdAtMsOf(sessionId, tenant)?.let { buildSession(sessionId, it) }

    /** The session row's creation time, or null when no row is visible to [tenant]. */
    @OptIn(ExperimentalTime::class)
    private fun createdAtMsOf(
        sessionId: String,
        tenant: TenantContext,
    ): Long? =
        transaction(db) {
            SessionsTable
                .selectAll()
                .where { tenantPredicate(tenant) and (SessionsTable.id eq sessionId) }
                .limit(1)
                .firstOrNull()
                ?.get(SessionsTable.createdAt)
                ?.toEpochMilliseconds()
        }

    /** Whether the session's row exists, without rebuilding the session from its events. */
    fun sessionExists(
        sessionId: String,
        tenant: TenantContext = TenantContext.Unscoped,
    ): Boolean =
        transaction(db) {
            SessionsTable
                .selectAll()
                .where { tenantPredicate(tenant) and (SessionsTable.id eq sessionId) }
                .limit(1)
                .any()
        }

    override fun listSessions(tenant: TenantContext): List<SessionInfo> =
        transaction(db) {
            SessionsTable
                .selectAll()
                .where { tenantPredicate(tenant) }
                .map { row ->
                    val id = row[SessionsTable.id]
                    val events =
                        EventsTable
                            .selectAll()
                            .where { EventsTable.sessionId eq id }
                            .toList()
                    val coroutineCount = buildSessionEventStoreCoroutineCount(id)
                    SessionInfo(
                        sessionId = id,
                        coroutineCount = coroutineCount,
                        eventCount = events.size,
                    )
                }
        }

    override fun deleteSession(
        sessionId: String,
        tenant: TenantContext,
    ): Boolean {
        val removed =
            transaction(db) {
                // FK cascade removes the session's events and shares. The tenant
                // predicate makes a cross-tenant delete a no-op (returns false).
                SessionsTable.deleteWhere { tenantPredicate(tenant) and (SessionsTable.id eq sessionId) }
            }
        if (removed > 0) logger.info("Deleted session (db): $sessionId")
        return removed > 0
    }

    override fun clearAll() {
        transaction(db) {
            EventsTable.deleteAll()
            SessionsTable.deleteAll()
        }
        logger.info("Cleared all sessions (db)")
    }

    /**
     * Tenant filter applied to every read/delete (T-03-09). A [TenantContext.Scoped]
     * caller is restricted to `tenant_id = <id>`; [TenantContext.Admin] and
     * [TenantContext.Unscoped] return an always-true predicate (no filter — D-03 ADMIN
     * bypass and D-04b auth-off global visibility respectively).
     */
    private fun tenantPredicate(tenant: TenantContext): Op<Boolean> =
        when (tenant) {
            is TenantContext.Scoped -> SessionsTable.tenantId eq tenant.tenantId
            TenantContext.Admin, TenantContext.Unscoped -> Op.TRUE
        }

    /**
     * Build a [VizSession] whose event store is an [ExposedEventStore] scoped to
     * [sessionId]. Uses the injectable event-store-factory seam on VizSession so
     * no DB dependency leaks into coroutine-viz-core. The session keeps summaries, folded
     * from the stored events by the rehydration below: complete while the store was never
     * trimmed; the trim keeps a checkpoint of them, see [rehydrate]).
     */
    private fun buildSession(
        sessionId: String,
        createdAtMs: Long,
    ): VizSession =
        VizSession(
            sessionId = sessionId,
            maxEvents = maxEvents,
            createdAtMs = createdAtMs,
            keepSummaries = true,
            eventStoreFactory = { id -> ExposedEventStore(db, id) },
        ).also(::rehydrate)

    /**
     * A database session is rebuilt on every read (PERS visualization): its snapshot and projections from the stored
     * events, its summaries from the trim's checkpoint plus the events after it (spec C-4, plan E-12). The events are read
     * before the checkpoint, and read again while the checkpoint is newer than every event read (a trim committed in
     * between), so summaries, snapshot and projections come from one cut. Without one after [CUT_ATTEMPTS] the summaries
     * are abandoned: health reads answer "unavailable", everything else still works.
     */
    private fun rehydrate(session: VizSession) {
        repeat(CUT_ATTEMPTS) {
            val events = ExposedEventStore(db, session.sessionId).all()
            afterEventsReadForTest?.invoke(session.sessionId)
            val seed = transaction(db) { SummaryCheckpoints.read(session.sessionId) }
            if (seed == null || seed.lastEventSeq <= (events.lastOrNull()?.seq ?: 0L)) {
                session.rehydrateFrom(events, seed)
                return
            }
        }
        logger.warn("No consistent cut of session {} after {} attempts; its summaries are unavailable", session.sessionId, CUT_ATTEMPTS)
        session.rehydrateFrom(ExposedEventStore(db, session.sessionId).all(), seed = null)
        session.abandonSummaries()
    }

    /** Test seam: runs between reading a session's events and its checkpoint, to interleave a trim. */
    @Volatile
    internal var afterEventsReadForTest: ((String) -> Unit)? = null

    private companion object {
        const val CUT_ATTEMPTS = 3
    }

    /** Distinct coroutine ids present in a session's stored events. */
    private fun buildSessionEventStoreCoroutineCount(sessionId: String): Int =
        ExposedEventStore(db, sessionId)
            .all()
            .asSequence()
            .filterIsInstance<CoroutineEvent>()
            .map { it.coroutineId }
            .distinct()
            .count()
}
