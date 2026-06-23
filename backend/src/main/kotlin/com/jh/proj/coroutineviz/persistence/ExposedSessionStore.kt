package com.jh.proj.coroutineviz.persistence

import com.jh.proj.coroutineviz.auth.TenantContext
import com.jh.proj.coroutineviz.auth.TenantScopedSessionStore
import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.models.SessionInfo
import com.jh.proj.coroutineviz.persistence.tables.EventsTable
import com.jh.proj.coroutineviz.persistence.tables.SessionsTable
import com.jh.proj.coroutineviz.session.SessionStoreInterface
import com.jh.proj.coroutineviz.session.VizSession
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
        val sessionId =
            name?.let { "$it-${System.currentTimeMillis()}" }
                ?: "session-${System.currentTimeMillis()}"

        // Persist the owning tenant id when the caller is scoped; Admin/Unscoped
        // creation is global (null tenant) — only Scoped callers stamp ownership.
        val owner = (tenant as? TenantContext.Scoped)?.tenantId

        transaction(db) {
            SessionsTable.insert {
                it[id] = sessionId
                it[SessionsTable.name] = name
                it[createdAt] = Clock.System.now()
                it[scenario] = null
                it[tenantId] = owner
                it[metadata] = null
            }
        }
        logger.info("Created session (db): $sessionId (tenant=${owner ?: "<global>"})")
        return buildSession(sessionId)
    }

    override fun getSession(
        sessionId: String,
        tenant: TenantContext,
    ): VizSession? {
        val exists =
            transaction(db) {
                SessionsTable
                    .selectAll()
                    .where { tenantPredicate(tenant) and (SessionsTable.id eq sessionId) }
                    .limit(1)
                    .any()
            }
        return if (exists) buildSession(sessionId) else null
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
     * no DB dependency leaks into coroutine-viz-core.
     */
    private fun buildSession(sessionId: String): VizSession =
        VizSession(
            sessionId = sessionId,
            maxEvents = maxEvents,
            eventStoreFactory = { id -> ExposedEventStore(db, id) },
        ).also {
            // A DB-backed session is reconstructed fresh on every read with empty
            // in-memory read models — replay the persisted events so the coroutine
            // tree / threads / timeline projections are populated (PERS visualization).
            it.rehydrateFromStore()
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
