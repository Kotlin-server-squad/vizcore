package com.jh.proj.coroutineviz

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
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*

fun Application.configureRouting() {
    install(SSE)
    routing {
        // Public routes — no auth, no rate limit
        registerRootRoutes()
        registerHealthRoutes()

        // API routes — rate limited
        rateLimit(RateLimitName("api")) {
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
