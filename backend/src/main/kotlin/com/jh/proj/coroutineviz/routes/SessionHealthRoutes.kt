package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.health.SummariesUnavailableException
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /api/sessions/{id}/health[?atSeq=N][&leakThresholdMs=M]` (spec 2026-10-21, C-3): the session's derived health,
 * live or at a replay cursor. Bounded for a once-per-second refetch: every list is capped with its total. Tenant
 * scoped like every session read.
 */
fun Route.registerSessionHealthRoutes() {
    get("/api/sessions/{id}/health") {
        val sessionId =
            call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing session ID"))
                return@get
            }
        val rawAtSeq = call.request.queryParameters["atSeq"]
        val atSeq = rawAtSeq?.toLongOrNull()?.takeIf { it >= 0 }
        if (rawAtSeq != null && atSeq == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("atSeq must be a non-negative integer"))
            return@get
        }
        val session = call.resolveScopedSession(sessionId)
        if (session == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Session not found"))
            return@get
        }
        val health =
            try {
                readSessionHealth(session, atSeq, call.leakThresholdMs())
            } catch (_: SummariesUnavailableException) {
                // H-3: unknown is shown as unknown, never as healthy.
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse("Health is unavailable for this session", code = "summaries-unavailable"),
                )
                return@get
            }
        call.respond(HttpStatusCode.OK, health)
    }
}
