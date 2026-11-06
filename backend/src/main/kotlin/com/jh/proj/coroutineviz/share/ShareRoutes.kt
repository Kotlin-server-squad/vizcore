package com.jh.proj.coroutineviz.share

import com.jh.proj.coroutineviz.auth.ApiKeyPrincipal
import com.jh.proj.coroutineviz.auth.TenantContext
import com.jh.proj.coroutineviz.auth.TenantScopedSessionStore
import com.jh.proj.coroutineviz.auth.UserPrincipal
import com.jh.proj.coroutineviz.currentPrincipal
import com.jh.proj.coroutineviz.health.SummariesUnavailableException
import com.jh.proj.coroutineviz.routes.AS_OF_SEQ_HEADER
import com.jh.proj.coroutineviz.routes.CUT_HEADER
import com.jh.proj.coroutineviz.routes.ChecksResults
import com.jh.proj.coroutineviz.routes.ChecksRun
import com.jh.proj.coroutineviz.routes.ErrorResponse
import com.jh.proj.coroutineviz.routes.HistoryTrimmedException
import com.jh.proj.coroutineviz.routes.IngestSnapshot
import com.jh.proj.coroutineviz.routes.IngestStates
import com.jh.proj.coroutineviz.routes.ReadCut
import com.jh.proj.coroutineviz.routes.SessionView
import com.jh.proj.coroutineviz.routes.ViewBinding
import com.jh.proj.coroutineviz.routes.badParameter
import com.jh.proj.coroutineviz.routes.hierarchyOf
import com.jh.proj.coroutineviz.routes.leakThresholdMs
import com.jh.proj.coroutineviz.routes.listRounds
import com.jh.proj.coroutineviz.routes.parseViewBinding
import com.jh.proj.coroutineviz.routes.readSessionHealth
import com.jh.proj.coroutineviz.routes.readView
import com.jh.proj.coroutineviz.routes.respondHealthItems
import com.jh.proj.coroutineviz.routes.respondReadErrors
import com.jh.proj.coroutineviz.routes.snapshotResponseOf
import com.jh.proj.coroutineviz.routes.timelineAt
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
 * Public read routes. Registered OUTSIDE `authenticatedApi { }` (the token IS the
 * credential) and wrapped in `rateLimit(RateLimitName("shared"))` by Routing.kt.
 *
 * `GET /api/shared/{token}`: valid → 200 `{session, events, health, checks}` under one cut (spec C-6); expired → 410;
 * unknown/revoked → 404. The shared view's follow-up reads (`…/health`, `…/health/items`, `…/rounds`, `…/hierarchy`,
 * `…/coroutines/{coroutineId}/timeline`) take the session routes' parameters and answers and do not count as accesses.
 */
fun Route.registerSharedPublicRoute(service: ShareService) {
    get("/api/shared/{token}") {
        call.withSharedSession(service, countAccess = true) { session, ingest, checks ->
            call.respond(HttpStatusCode.OK, sharedResponse(session, ingest, checks, call.leakThresholdMs()))
        }
    }
    get("/api/shared/{token}/health") {
        call.withSharedSession(service) { session, ingest, _ ->
            call.respondReadErrors {
                val binding = parseViewBinding(call.request.queryParameters)
                val atSeq = (binding as? ViewBinding.Replay)?.atSeq
                if (binding is ViewBinding.Bound) badParameter("the health takes atSeq, not asOfSeq")
                val read = readSessionHealth(session, ingest, atSeq, call.leakThresholdMs())
                read.cutId?.let { call.response.headers.append(CUT_HEADER, it) }
                call.respond(HttpStatusCode.OK, read.health)
            }
        }
    }
    get("/api/shared/{token}/health/items") { call.withSharedView(service) { view -> call.respondHealthItems(view) } }
    get("/api/shared/{token}/rounds") {
        call.withSharedView(service) { view -> call.respond(HttpStatusCode.OK, listRounds(view, call.request.queryParameters)) }
    }
    get("/api/shared/{token}/hierarchy") {
        call.withSharedView(service) { view ->
            call.response.headers.append(AS_OF_SEQ_HEADER, view.seq.toString())
            call.respond(
                HttpStatusCode.OK,
                hierarchyOf(view, call.request.queryParameters["scopeId"], call.request.queryParameters["roundId"]),
            )
        }
    }
    get("/api/shared/{token}/coroutines/{coroutineId}/timeline") {
        call.withSharedView(service) { view ->
            val body = call.parameters["coroutineId"]?.let { timelineAt(view, it) }
            if (body ==
                null
            ) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Coroutine not found"))
            } else {
                call.respond(HttpStatusCode.OK, body)
            }
        }
    }
}

/**
 * Resolves the share token (410 expired, 404 unknown or session gone), reads the ingest snapshot and the stored checks run
 * BEFORE resolving the session (so neither is newer than what the session then shows: 5a D-9c, spec C-6), then runs
 * [block].
 */
private suspend fun ApplicationCall.withSharedSession(
    service: ShareService,
    countAccess: Boolean = false,
    block: suspend (VizSession, IngestSnapshot, ChecksRun?) -> Unit,
) {
    val token = parameters["token"] ?: return respond(HttpStatusCode.NotFound, mapOf("error" to "Share link not found"))
    when (val resolution = service.resolve(token, countAccess)) {
        is ShareResolution.Valid -> {
            val sessionId = resolution.share.sessionId
            val ingest = IngestStates.snapshot(sessionId)
            val checks = ChecksResults.load(sessionId)
            val session = SessionManager.getSession(sessionId)
            afterSessionResolvedForTest?.invoke(sessionId)
            if (session == null) {
                // The share row outlived its session (should not happen — FK cascade + retention guard).
                logger.warn("Share {} resolved but session {} missing", token, sessionId)
                respond(HttpStatusCode.NotFound, mapOf("error" to "Share link not found"))
            } else {
                block(session, ingest, checks)
            }
        }
        ShareResolution.Expired -> respond(HttpStatusCode.Gone, mapOf("error" to "Share link has expired"))
        ShareResolution.NotFound -> respond(HttpStatusCode.NotFound, mapOf("error" to "Share link not found"))
    }
}

/** Test seam: runs right after a shared session is resolved (its ingest state and checks were read before). */
@Volatile
internal var afterSessionResolvedForTest: ((String) -> Unit)? = null

/** A shared follow-up read: the session's view with the request's binding, its read errors answered. */
private suspend fun ApplicationCall.withSharedView(
    service: ShareService,
    block: suspend (SessionView) -> Unit,
) = withSharedSession(service) { session, ingest, _ ->
    respondReadErrors { block(readView(session, ingest, parseViewBinding(request.queryParameters), leakThresholdMs())) }
}

/**
 * The share payload (spec C-6): the session snapshot, its events, the health at load and the last checks run, all from one
 * cut taken under the session's send lock. The health's cut is kept, so the shared view's follow-ups answer from it.
 */
private fun sharedResponse(
    session: VizSession,
    ingest: IngestSnapshot,
    checks: ChecksRun?,
    leakThresholdMs: Long,
): SharedSessionResponse {
    val cut = ReadCut.of(session)
    val events = cut.events.filter { it.seq <= cut.seq }
    var unavailable: String? = null
    val read =
        try {
            readSessionHealth(session, ingest, atSeq = null, leakThresholdMs = leakThresholdMs, cut = cut)
        } catch (_: SummariesUnavailableException) {
            unavailable = "summaries-unavailable"
            null
        } catch (_: HistoryTrimmedException) {
            unavailable = "history-trimmed"
            null
        }
    return SharedSessionResponse(
        session = snapshotResponseOf(session, events, cut, ingest),
        events = events,
        health = read?.health,
        healthUnavailable = unavailable,
        healthCut = read?.cutId,
        checks = ChecksResults.status(session.sessionId, checks, cut.seq),
    )
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
