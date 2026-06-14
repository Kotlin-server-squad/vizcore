package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.session.ComparisonService
import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.registerComparisonRoutes() {
    get("/api/sessions/compare") {
        val sessionAId = call.request.queryParameters["a"]
        val sessionBId = call.request.queryParameters["b"]

        if (sessionAId == null || sessionBId == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(error = "Both a and b query parameters are required"),
            )
            return@get
        }

        val sessionA = SessionManager.getSession(sessionAId)
        if (sessionA == null) {
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(error = "Session not found: $sessionAId"),
            )
            return@get
        }

        val sessionB = SessionManager.getSession(sessionBId)
        if (sessionB == null) {
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(error = "Session not found: $sessionBId"),
            )
            return@get
        }

        val comparison = ComparisonService.compare(sessionA, sessionB)
        call.respond(HttpStatusCode.OK, comparison)
    }
}
