package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.auth.resolveTenant
import com.jh.proj.coroutineviz.scenarios.FlowScenarios
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.registerFlowScenarioRoutes() {
    demoPost("/api/scenarios/flow/simple") {
        val session = call.getOrCreateSession(call.request.queryParameters["sessionId"], "flow-simple")
        // Answers once the demo has ended, as the Flow demos always have.
        FlowScenarios.simpleFlow(session).join()
        call.respond(HttpStatusCode.OK, session.toCompletionResponse("Simple Flow completed"))
    }

    demoPost("/api/scenarios/flow/operators") {
        val session = call.getOrCreateSession(call.request.queryParameters["sessionId"], "flow-operators")
        // Answers once the demo has ended, as the Flow demos always have.
        FlowScenarios.flowOperators(session).join()
        call.respond(HttpStatusCode.OK, session.toCompletionResponse("Flow Operators completed"))
    }

    demoPost("/api/scenarios/flow/stateflow") {
        val session = call.getOrCreateSession(call.request.queryParameters["sessionId"], "flow-stateflow")
        // Answers once the demo has ended, as the Flow demos always have.
        FlowScenarios.stateFlowDemo(session).join()
        call.respond(HttpStatusCode.OK, session.toCompletionResponse("StateFlow demo completed"))
    }

    demoPost("/api/scenarios/flow/sharedflow") {
        val session = call.getOrCreateSession(call.request.queryParameters["sessionId"], "flow-sharedflow")
        // Answers once the demo has ended, as the Flow demos always have.
        FlowScenarios.sharedFlowDemo(session).join()
        call.respond(HttpStatusCode.OK, session.toCompletionResponse("SharedFlow demo completed"))
    }

    get("/api/scenarios/flow") {
        call.respond(
            HttpStatusCode.OK,
            mapOf(
                "scenarios" to
                    listOf(
                        mapOf(
                            "id" to "flow/simple",
                            "name" to "Simple Flow: Stock Ticker",
                            "description" to "A cold flow sends six prices to each screen that collects it",
                            "endpoint" to "/api/scenarios/flow/simple",
                            "category" to "flow",
                            "duration" to "~16s",
                        ),
                        mapOf(
                            "id" to "flow/operators",
                            "name" to "Flow Operators: Search as You Type",
                            "description" to "Keystrokes are debounced, filtered and mapped to search results",
                            "endpoint" to "/api/scenarios/flow/operators",
                            "category" to "flow",
                            "duration" to "~14s",
                        ),
                        mapOf(
                            "id" to "flow/stateflow",
                            "name" to "StateFlow: Shopping Cart",
                            "description" to "The cart total always has a value; a late subscriber gets the latest at once",
                            "endpoint" to "/api/scenarios/flow/stateflow",
                            "category" to "flow",
                            "duration" to "~12s",
                        ),
                        mapOf(
                            "id" to "flow/sharedflow",
                            "name" to "SharedFlow: Notifications",
                            "description" to "Every open screen gets each notification at the same moment",
                            "endpoint" to "/api/scenarios/flow/sharedflow",
                            "category" to "flow",
                            "duration" to "~14s",
                        ),
                    ),
            ),
        )
    }
}

internal suspend fun ApplicationCall.getOrCreateSession(
    sessionId: String?,
    prefix: String,
): VizSession {
    val store = tenantScopedStore()
    if (store == null) {
        // Memory / auth-off: preserve legacy unscoped behavior (D-04b global visibility).
        return if (sessionId != null) {
            SessionManager.getSession(sessionId) ?: SessionManager.createSession(sessionId)
        } else {
            SessionManager.createSession(prefix)
        }
    }
    // Tenant-scoped (AUTH-04): a cross-tenant or unknown id resolves to null and falls
    // through to a NEW session owned by the caller — never a write into another tenant's
    // session. Created sessions are stamped with the caller's tenant (no orphans).
    val tenant = resolveTenant()
    return sessionId?.let { store.getSession(it, tenant) }
        ?: store.createSession(prefix, tenant)
}

internal fun VizSession.toCompletionResponse(message: String) =
    ScenarioCompletionResponse(
        success = true,
        sessionId = sessionId,
        message = message,
        coroutineCount = snapshot.coroutineCount,
        eventCount = store.all().size,
    )
