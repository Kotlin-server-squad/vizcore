package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.health.SummariesUnavailableException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** The live health's kept cut id: follow-ups pass it as `asOfCut` to name exactly that cut (plan E-17). */
const val CUT_HEADER = "X-Viz-Cut"

/**
 * `GET /api/sessions/{id}/health[?atSeq=N][&leakThresholdMs=M]` (spec 2026-10-21, C-3): the session's derived health,
 * live or at a replay cursor. Bounded for a once-per-second refetch: every list is capped with its total. Tenant
 * scoped like every session read.
 */

fun Route.registerSessionHealthRoutes() {
    // GET /api/sessions/{id}/rounds (spec C-3, T-4): every round, problem rounds first, with server-side find.
    get("/api/sessions/{id}/rounds") {
        call.withSessionView { view -> call.respond(HttpStatusCode.OK, listRounds(view, call.request.queryParameters)) }
    }
    // GET /api/sessions/{id}/health/items (spec C-3): the health's lists past their first 50.
    get("/api/sessions/{id}/health/items") {
        call.withSessionView { view -> call.respondHealthItems(view) }
    }

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
        // Before the session is resolved: see readSessionHealth.
        val ingest = IngestStates.snapshot(sessionId)
        val session = call.resolveScopedSession(sessionId)
        if (session == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Session not found"))
            return@get
        }
        // H-3: unknown is shown as unknown, never as healthy.
        val health =
            try {
                readSessionHealth(session, ingest, atSeq, call.leakThresholdMs())
                    .also { read ->
                        read.cutId?.let { call.response.headers.append(CUT_HEADER, it) }
                    }.health
            } catch (_: SummariesUnavailableException) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse("Health is unavailable for this session", code = "summaries-unavailable"),
                )
                return@get
            } catch (_: HistoryTrimmedException) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse("Health is unavailable: this session's early events were trimmed", code = "history-trimmed"),
                )
                return@get
            }
        call.respond(HttpStatusCode.OK, health)
    }
}

/**
 * The common part of the follow-up reads (plan E-3): parse the binding, read the ingest snapshot before resolving the
 * session (5a D-9c), resolve it under the caller's tenant (404 otherwise), read the view and hand it to [block]; the read
 * errors are answered by [respondReadErrors].
 */
internal suspend fun ApplicationCall.withSessionView(block: suspend (SessionView) -> Unit) =
    respondReadErrors {
        val binding = parseViewBinding(request.queryParameters)
        val sessionId = parameters["id"] ?: badParameter("Missing session ID")
        val ingest = IngestStates.snapshot(sessionId)
        val session = resolveScopedSession(sessionId)
        if (session == null) {
            respond(HttpStatusCode.NotFound, ErrorResponse("Session not found"))
        } else {
            block(readView(session, ingest, binding, leakThresholdMs()))
        }
    }
