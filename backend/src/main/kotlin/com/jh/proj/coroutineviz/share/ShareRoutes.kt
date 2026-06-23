package com.jh.proj.coroutineviz.share

import com.jh.proj.coroutineviz.auth.ApiKeyPrincipal
import com.jh.proj.coroutineviz.auth.TenantContext
import com.jh.proj.coroutineviz.auth.TenantScopedSessionStore
import com.jh.proj.coroutineviz.auth.UserPrincipal
import com.jh.proj.coroutineviz.currentPrincipal
import com.jh.proj.coroutineviz.routes.CoroutineNodeDto
import com.jh.proj.coroutineviz.routes.SessionSnapshotResponse
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.ExperimentalTime

private val logger = LoggerFactory.getLogger("ShareRoutes")
private val requestJson = Json { ignoreUnknownKeys = true }

/**
 * Resolve the owning principal id for `created_by` (T-03-16). Falls back to
 * "anonymous" when auth is off (no principal) — sharing is still allowed, the
 * row simply records an anonymous owner.
 */
private fun ApplicationCall.shareCreatorId(): String =
    when (val p = currentPrincipal()) {
        is UserPrincipal -> p.userId
        is ApiKeyPrincipal -> p.name
        else -> "anonymous"
    }

/**
 * The active tenant-scoped backing store, if persistence is on AND the store
 * implements [TenantScopedSessionStore]; null in memory / auth-off mode (where
 * ownership is enforced by the recorded `created_by` only, D-04b).
 */
private fun tenantScopedStore(): TenantScopedSessionStore? = SessionManager.backingStore() as? TenantScopedSessionStore

/**
 * Strict ownership tenant used for MINTING a share (WR-02). Unlike the general
 * `resolveTenant()`, an ADMIN principal does NOT receive the [TenantContext.Admin]
 * `Op.TRUE` filter bypass here: minting is authorized only when the caller
 * actually OWNS the session (tenant match), never merely because an admin can
 * *see* it. Without this, an ADMIN could mint a durable public share on another
 * tenant's session that the owning tenant can neither list (list is
 * `created_by`-scoped) nor revoke (`revoke` requires a `created_by` match) — a
 * tenant-invisible, tenant-unrevocable link. Admins resolve to their own id as a
 * concrete scope, so they may mint only on sessions they themselves own.
 */
private fun ApplicationCall.mintOwnershipTenant(): TenantContext =
    when (val p = currentPrincipal()) {
        is UserPrincipal -> TenantContext.Scoped(p.userId)
        is ApiKeyPrincipal -> TenantContext.Scoped(p.name)
        else -> TenantContext.Unscoped
    }

/**
 * Owner (authenticated) share-management routes. Registered INSIDE
 * `authenticatedApi { }` by Routing.kt so mint/list/revoke require a credential
 * when auth is on (T-03-16).
 *
 * @param service the DB-backed share lifecycle.
 * @param publicBaseUrl optional configured base for the share URL (e.g.
 *   `https://app.example.com`); when null/blank the URL is derived from the
 *   request origin.
 */
@OptIn(ExperimentalTime::class)
fun Route.registerShareOwnerRoutes(
    service: ShareService,
    publicBaseUrl: String?,
) {
    post("/api/sessions/{id}/share") {
        val sessionId =
            call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session ID"))
                return@post
            }

        // Ownership-checked existence (CR-02 / D-03 / WR-02): when the tenant-scoped
        // store is active, the caller may only mint a share on a session it OWNS — a
        // cross-tenant (or absent) id resolves to null → 404, indistinguishable so it
        // never leaks another tenant's session. Minting uses the STRICT ownership
        // tenant (no ADMIN bypass) so a privileged actor cannot mint a tenant-invisible
        // share on another tenant's session. In memory / auth-off mode (store null)
        // fall back to the unscoped existence check (D-04b global visibility).
        val scopedStore = tenantScopedStore()
        val ownsSession =
            if (scopedStore != null) {
                scopedStore.getSession(sessionId, call.mintOwnershipTenant()) != null
            } else {
                SessionManager.getSession(sessionId) != null
            }
        if (!ownsSession) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
            return@post
        }

        val body = call.receiveText()
        val request =
            runCatching { requestJson.decodeFromString(CreateShareRequest.serializer(), body) }.getOrNull()
        val expiry = ShareExpiry.fromCode(request?.expiresIn)
        if (expiry == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Invalid expiresIn (expected one of 1d, 7d, 30d, never)"),
            )
            return@post
        }

        val share = service.create(sessionId, call.shareCreatorId(), expiry)
        val base = resolveBaseUrl(call, publicBaseUrl)
        call.respond(
            HttpStatusCode.Created,
            CreateShareResponse(
                token = share.token,
                url = "$base/shared/${share.token}",
                expiresAt = share.expiresAt?.toString(),
            ),
        )
    }

    get("/api/sessions/{id}/shares") {
        val sessionId =
            call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session ID"))
                return@get
            }
        // Owner-scoped list (CR-02): a principal only sees the shares it minted on
        // this session — never another tenant's shares.
        val summaries =
            service.listForSession(sessionId, call.shareCreatorId()).map { s ->
                ShareSummary(
                    token = s.token,
                    expiresAt = s.expiresAt?.toString(),
                    accessCount = s.accessCount,
                    lastAccessedAt = s.lastAccessedAt?.toString(),
                )
            }
        call.respond(HttpStatusCode.OK, summaries)
    }

    delete("/api/sessions/{id}/shares/{token}") {
        val sessionId =
            call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session ID"))
                return@delete
            }
        val token =
            call.parameters["token"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing token"))
                return@delete
            }
        // Owner-scoped revoke (CR-02): a non-creator delete matches no row → 404.
        if (service.revoke(sessionId, token, call.shareCreatorId())) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Share link not found"))
        }
    }
}

/**
 * Public read route. Registered OUTSIDE `authenticatedApi { }` (the token IS the
 * credential) and wrapped in `rateLimit(RateLimitName("shared"))` by Routing.kt.
 *
 * Valid → 200 `{session, events}`; Expired → 410; unknown/revoked → 404.
 */
fun Route.registerSharedPublicRoute(service: ShareService) {
    get("/api/shared/{token}") {
        val token =
            call.parameters["token"] ?: run {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Share link not found"))
                return@get
            }

        when (val resolution = service.resolve(token)) {
            is ShareResolution.Valid -> {
                val session = SessionManager.getSession(resolution.share.sessionId)
                if (session == null) {
                    // The share row outlived its session (should not happen — FK
                    // cascade + retention guard — but treat defensively as 404).
                    logger.warn("Share {} resolved but session {} missing", token, resolution.share.sessionId)
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Share link not found"))
                    return@get
                }
                call.respond(HttpStatusCode.OK, sharedResponse(session))
            }

            ShareResolution.Expired ->
                call.respond(HttpStatusCode.Gone, mapOf("error" to "Share link has expired"))

            ShareResolution.NotFound ->
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Share link not found"))
        }
    }
}

/**
 * Build the `{session, events}` body for a shared session — the same snapshot
 * shape `GET /api/sessions/{id}` returns plus the full event history (reusing
 * the [VizSession] store, which the SSE/events paths also read).
 */
private fun sharedResponse(session: VizSession): SharedSessionResponse {
    val events = session.store.all()
    val snapshot =
        SessionSnapshotResponse(
            sessionId = session.sessionId,
            coroutineCount = session.snapshot.coroutines.size,
            eventCount = events.size,
            coroutines =
                session.snapshot.coroutines.values.map { node ->
                    CoroutineNodeDto(
                        id = node.id,
                        jobId = node.jobId,
                        parentId = node.parentId,
                        scopeId = node.scopeId,
                        label = node.label,
                        state = node.state.toString(),
                    )
                },
        )
    return SharedSessionResponse(session = snapshot, events = events)
}

/**
 * The base URL the share link is built from: the configured `app.publicBaseUrl`
 * when set, else derived from the request origin (scheme + host[:port]).
 */
private fun resolveBaseUrl(
    call: ApplicationCall,
    publicBaseUrl: String?,
): String {
    if (!publicBaseUrl.isNullOrBlank()) return publicBaseUrl.trimEnd('/')
    val origin = call.request.local
    val scheme = origin.scheme
    val host = origin.serverHost
    val port = origin.serverPort
    val isDefaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
    return if (isDefaultPort) "$scheme://$host" else "$scheme://$host:$port"
}
