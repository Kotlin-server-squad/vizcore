package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.auth.JwtConfig
import com.jh.proj.coroutineviz.auth.UserStore
import com.jh.proj.coroutineviz.routes.CorrelationRegistry
import com.jh.proj.coroutineviz.routes.registerAuthRoutes
import com.jh.proj.coroutineviz.routes.registerComparisonRoutes
import com.jh.proj.coroutineviz.routes.registerFlowScenarioRoutes
import com.jh.proj.coroutineviz.routes.registerHealthRoutes
import com.jh.proj.coroutineviz.routes.registerIngestRoutes
import com.jh.proj.coroutineviz.routes.registerPatternRoutes
import com.jh.proj.coroutineviz.routes.registerRootRoutes
import com.jh.proj.coroutineviz.routes.registerScenarioRunnerRoutes
import com.jh.proj.coroutineviz.routes.registerSessionRoutes
import com.jh.proj.coroutineviz.routes.registerSyncScenarioRoutes
import com.jh.proj.coroutineviz.routes.registerTestRoutes
import com.jh.proj.coroutineviz.routes.registerValidationRoutes
import com.jh.proj.coroutineviz.routes.registerVizScenarioRoutes
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.share.ShareService
import com.jh.proj.coroutineviz.share.registerShareOwnerRoutes
import com.jh.proj.coroutineviz.share.registerSharedPublicRoute
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
fun Application.configureRouting() {
    install(SSE)

    // Evict a session's correlation binding when it closes (D-10), so a stale token resolves to
    // 404 again. Use the COMPOSABLE addOnSessionClosed hook (NOT the single-slot onSessionClosed
    // var, which is shared and would clobber the metrics/source wiring — RCO-01 "compose, don't
    // clobber"). Fires in BOTH the backing-store and in-memory delete branches, so eviction is
    // uniform across persistence modes. No TTL: the map is bounded purely by live-session lifecycle.
    SessionManager.addOnSessionClosed { sessionId -> CorrelationRegistry.evict(sessionId) }

    // Stores built by configureAuth() (runs first in module()); reused for the token endpoint.
    val userStore = attributes.getOrNull(UserStoreKey) ?: UserStore(emptyList())
    val jwtConfig =
        attributes.getOrNull(JwtConfigKey)
            ?: JwtConfig.fromConfig(environment.config)

    // Sharing requires persistence (ADR-019): build the DB-backed ShareService
    // only when storage.type=database. In memory mode the share routes are absent.
    val db = attributes.getOrNull(DatabaseKey)
    val shareService = db?.let { ShareService(it) }
    val publicBaseUrl = environment.config.propertyOrNull("app.publicBaseUrl")?.getString()
    val rateLimitShared = attributes.getOrNull(SharedRateLimitEnabledKey) ?: false

    routing {
        // Public routes — no auth required (AUTH-01 allowlist), no rate limit.
        registerRootRoutes()
        registerHealthRoutes()
        // POST /api/auth/token is ALWAYS public (login endpoint).
        registerAuthRoutes(userStore, jwtConfig)
        // Public GET /api/shared/{token} (SHAR-02): the share token IS the credential, so
        // this is registered OUTSIDE authenticatedApi. It is wrapped in the per-IP RateLimit
        // scope (Task 2) to bound brute-force/scraping (T-03-13). Present only when persistence
        // is on (ADR-019 requires the shares table).
        shareService?.let { registerSharedRoute(it, rateLimitShared) }

        // Protected routes — wrapped so EITHER X-API-Key OR JWT satisfies (D-08); pass-through
        // when auth is fully unconfigured (D-04a). /openapi.json is served by the OpenAPI plugin
        // (configureHTTP), outside this wrapper, so it stays public. Nested inside the per-IP
        // "api" rate-limit scope (ADR-029, 60/min) so protected routes are authenticated AND
        // rate-limited.
        authenticatedApi {
            rateLimit(RateLimitName("api")) {
                registerVizScenarioRoutes()
                registerSyncScenarioRoutes()
                registerTestRoutes()
                registerSessionRoutes()
                // WebSocket ingest (RCO-05): inherits auth + the 60/min per-IP
                // limit + D-04a fail-open from this authenticatedApi/rateLimit block.
                registerIngestRoutes()
                registerValidationRoutes()
                registerScenarioRunnerRoutes()
                registerFlowScenarioRoutes()
                registerPatternRoutes()
                registerComparisonRoutes()
                // Owner share management (SHAR-01): mint/list/revoke require a credential
                // when auth is on (T-03-16). createdBy is resolved from the principal.
                shareService?.let { registerShareOwnerRoutes(it, publicBaseUrl) }
            }
        }
    }
}

/**
 * Register the public shared-read route. When [rateLimited] (the
 * `share.rateLimit.enabled` config), wrap it in the per-IP
 * `rateLimit(RateLimitName("shared")) { }` scope so exceeding the bucket returns
 * 429 + Retry-After automatically (D-12). When disabled (or the RateLimit plugin
 * is not installed), register the route directly.
 */
private fun Route.registerSharedRoute(
    shareService: ShareService,
    rateLimited: Boolean,
) {
    if (rateLimited) {
        rateLimit(RateLimitName(SHARED_RATE_LIMIT_NAME)) {
            registerSharedPublicRoute(shareService)
        }
    } else {
        registerSharedPublicRoute(shareService)
    }
}
