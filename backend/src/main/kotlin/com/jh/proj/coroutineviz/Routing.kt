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
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*

fun Application.configureRouting() {
    install(SSE)

    // Stores built by configureAuth() (runs first in module()); reused for the token endpoint.
    val userStore = attributes.getOrNull(UserStoreKey) ?: UserStore(emptyList())
    val jwtConfig =
        attributes.getOrNull(JwtConfigKey)
            ?: JwtConfig.fromConfig(environment.config)

    routing {
        // Public routes — no auth required (AUTH-01 allowlist).
        registerRootRoutes()
        registerHealthRoutes()
        // POST /api/auth/token is ALWAYS public (login endpoint).
        registerAuthRoutes(userStore, jwtConfig)
        // Plan 04 seam: register the public GET /api/shared/{token} route OUTSIDE the wrapper
        // (the share token is itself the credential; no Bearer/X-API-Key required there).

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
        }
    }
}
