@file:Suppress("DEPRECATION")

package com.jh.proj.coroutineviz.auth

import com.jh.proj.coroutineviz.currentPrincipal
import com.jh.proj.coroutineviz.models.SessionInfo
import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.Principal

/**
 * Multi-tenancy resolution (AUTH-04, D-03).
 *
 * Tenancy engages ONLY when auth is configured. When auth is off the current
 * principal is null and every caller resolves to [TenantContext.Unscoped], so
 * stored sessions stay globally visible (D-04b — the `git clone → run` default).
 *
 * Resolution rules (ADR-016 / D-03):
 * - [UserPrincipal] (JWT) → tenant id = `userId` (the JWT `sub` claim).
 * - [ApiKeyPrincipal] → tenant id = the key `name` (api-key-name fallback).
 * - any principal with [Role.ADMIN] → [TenantContext.Admin] (filter bypass).
 * - no principal (auth off) → [TenantContext.Unscoped] (no filter, global).
 *
 * The resolved context is passed EXPLICITLY into store reads/creation — there is
 * no global mutable tenant state (RESEARCH Pitfall 3 / T-03-10).
 */
sealed interface TenantContext {
    /** A concrete tenant; reads/creation are filtered to [tenantId]. */
    data class Scoped(val tenantId: String) : TenantContext

    /** An ADMIN principal — sees ALL sessions, bypassing the tenant filter (D-03). */
    data object Admin : TenantContext

    /** No tenant scope (auth off) — all sessions globally visible (D-04b). */
    data object Unscoped : TenantContext

    companion object {
        /**
         * Resolve the [TenantContext] for a request [principal].
         * A null principal (auth-off path) resolves to [Unscoped].
         */
        fun resolve(principal: Principal?): TenantContext =
            when (principal) {
                null -> Unscoped
                is UserPrincipal ->
                    if (principal.role == Role.ADMIN) Admin else Scoped(principal.userId)
                is ApiKeyPrincipal ->
                    if (principal.role == Role.ADMIN) Admin else Scoped(principal.name)
                else -> Unscoped
            }
    }
}

/**
 * Store contract for tenant-scoped session reads/creation. Implemented by the
 * DB-backed store ([com.jh.proj.coroutineviz.persistence.ExposedSessionStore]);
 * the in-memory [com.jh.proj.coroutineviz.session.SessionManager] does NOT
 * implement it (memory mode is the unscoped/global use case, D-04b).
 *
 * The tenant filter lives HERE, in the store layer, applied on EVERY read path
 * (list/get/delete) so isolation is enforced uniformly (Architectural
 * Responsibility Map; T-03-09).
 */
interface TenantScopedSessionStore {
    /** Create a session owned by [tenant] (persists the tenant id on the row). */
    suspend fun createSession(
        name: String?,
        tenant: TenantContext,
    ): VizSession

    /** List sessions visible to [tenant] (filtered unless Admin/Unscoped). */
    fun listSessions(tenant: TenantContext): List<SessionInfo>

    /** Get a session by id IF visible to [tenant], else null (cross-tenant → not found). */
    fun getSession(
        sessionId: String,
        tenant: TenantContext,
    ): VizSession?

    /** Delete a session by id IF visible to [tenant]; false if absent or cross-tenant. */
    fun deleteSession(
        sessionId: String,
        tenant: TenantContext,
    ): Boolean
}

/**
 * Resolve the [TenantContext] for the current request from its authenticated
 * principal (or [TenantContext.Unscoped] when auth is off). Wraps the Plan 02
 * [currentPrincipal] helper.
 */
fun ApplicationCall.resolveTenant(): TenantContext = TenantContext.resolve(currentPrincipal())
