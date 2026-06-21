package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.auth.JwtConfig
import com.jh.proj.coroutineviz.auth.UserStore
import com.jh.proj.coroutineviz.routes.registerAuthRoutes
import com.jh.proj.coroutineviz.routes.registerComparisonRoutes
import com.jh.proj.coroutineviz.routes.registerFlowScenarioRoutes
import com.jh.proj.coroutineviz.routes.registerHealthRoutes
import com.jh.proj.coroutineviz.routes.registerPatternRoutes
import com.jh.proj.coroutineviz.routes.registerRootRoutes
import com.jh.proj.coroutineviz.routes.registerScenarioRunnerRoutes
import com.jh.proj.coroutineviz.routes.registerSessionRoutes
import com.jh.proj.coroutineviz.routes.registerSyncScenarioRoutes
import com.jh.proj.coroutineviz.routes.registerTestRoutes
import com.jh.proj.coroutineviz.routes.registerValidationRoutes
import com.jh.proj.coroutineviz.routes.registerVizScenarioRoutes
import com.jh.proj.coroutineviz.share.ShareService
import com.jh.proj.coroutineviz.share.registerShareOwnerRoutes
import com.jh.proj.coroutineviz.share.registerSharedPublicRoute
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
fun Application.configureRouting() {
    install(SSE)

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
        // Public routes — no auth required (AUTH-01 allowlist).
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
        // (configureHTTP), outside this wrapper, so it stays public.
        authenticatedApi {
            registerVizScenarioRoutes()
            registerSyncScenarioRoutes()
            registerTestRoutes()
            registerSessionRoutes()
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
