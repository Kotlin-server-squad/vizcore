package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class MemoryInfo(
    val usedMb: Long,
    val maxMb: Long,
    val usagePercent: Double,
)

@Serializable
data class HealthStatus(
    val status: String,
    val version: String,
    val sessions: Int,
    val uptimeMs: Long,
    val memory: MemoryInfo,
    val components: Map<String, String> = emptyMap(),
    /**
     * Whether sharing is available — true only when persistence is on
     * (storage.type=database), since the share routes require the shares table
     * (ADR-019). The frontend uses this to gate the Share affordance instead of
     * offering an action that 404s in memory mode.
     */
    val sharingEnabled: Boolean = false,
)

private val startTime = System.currentTimeMillis()

private const val APP_VERSION = "0.0.1"

private suspend fun ApplicationCall.respondHealth() {
    val runtime = Runtime.getRuntime()
    val maxMb = runtime.maxMemory() / (1024 * 1024)
    val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    val usagePercent = if (maxMb > 0) (usedMb.toDouble() / maxMb * 100) else 0.0

    val memory =
        MemoryInfo(
            usedMb = usedMb,
            maxMb = maxMb,
            usagePercent = usagePercent,
        )

    val sessions = SessionManager.listSessions().size
    val uptimeMs = System.currentTimeMillis() - startTime
    val healthy = usagePercent < 90.0

    val status =
        HealthStatus(
            status = if (healthy) "UP" else "DEGRADED",
            version = APP_VERSION,
            sessions = sessions,
            uptimeMs = uptimeMs,
            memory = memory,
            components =
                mapOf(
                    "sessionManager" to "UP",
                    "memory" to if (healthy) "UP" else "DEGRADED",
                ),
            // Sharing requires the DB-backed (tenant-scoped) store; absent in memory mode.
            sharingEnabled = tenantScopedStore() != null,
        )

    val httpStatus = if (healthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
    respond(httpStatus, status)
}

fun Route.registerHealthRoutes() {
    // /health alias — kept for backwards compatibility
    get("/health") { call.respondHealth() }

    route("/api") {
        get("/health") { call.respondHealth() }

        get("/live") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "UP"))
        }

        get("/ready") {
            val runtime = Runtime.getRuntime()
            val maxMb = runtime.maxMemory()
            val usedMb = runtime.totalMemory() - runtime.freeMemory()
            val usagePercent = if (maxMb > 0) (usedMb.toDouble() / maxMb * 100) else 0.0
            // Verify SessionManager is reachable by calling listSessions()
            SessionManager.listSessions()
            if (usagePercent < 95.0) {
                call.respond(HttpStatusCode.OK, mapOf("status" to "UP"))
            } else {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    mapOf("status" to "DOWN", "reason" to "high memory"),
                )
            }
        }
    }
}
