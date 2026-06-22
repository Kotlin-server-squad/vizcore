package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.scenarios.PatternScenarios
import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.wrappers.VizScope
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("PatternRoutes")

@Serializable
data class PatternInfo(
    val id: String,
    val name: String,
    val description: String,
    val endpoint: String,
    val category: String = "pattern",
    val duration: String,
)

/**
 * Routes for real-world coroutine pattern scenarios.
 *
 * These routes demonstrate common concurrency patterns including
 * retry, producer-consumer, fan-out/fan-in, supervisor, and circuit breaker.
 */
fun Route.registerPatternRoutes() {
    // ========================================================================
    // PATTERN SCENARIOS
    // ========================================================================

    post("/api/scenarios/patterns/retry") {
        val sessionId = call.request.queryParameters["sessionId"]
        val session = call.getOrCreateSession(sessionId, "pattern")

        logger.info("Running retry with exponential backoff scenario in session: ${session.sessionId}")

        call.runPatternScenario(session, "Retry with Exponential Backoff") { scope ->
            PatternScenarios.retryWithExponentialBackoff(scope)
        }
    }

    post("/api/scenarios/patterns/producer-consumer") {
        val sessionId = call.request.queryParameters["sessionId"]
        val session = call.getOrCreateSession(sessionId, "pattern")

        logger.info("Running producer-consumer scenario in session: ${session.sessionId}")

        call.runPatternScenario(session, "Producer-Consumer") { scope ->
            PatternScenarios.producerConsumer(scope)
        }
    }

    post("/api/scenarios/patterns/fan-out-fan-in") {
        val sessionId = call.request.queryParameters["sessionId"]
        val session = call.getOrCreateSession(sessionId, "pattern")

        logger.info("Running fan-out/fan-in scenario in session: ${session.sessionId}")

        call.runPatternScenario(session, "Fan-Out / Fan-In") { scope ->
            PatternScenarios.fanOutFanIn(scope)
        }
    }

    post("/api/scenarios/patterns/supervisor") {
        val sessionId = call.request.queryParameters["sessionId"]
        val session = call.getOrCreateSession(sessionId, "pattern")

        logger.info("Running supervisor pattern scenario in session: ${session.sessionId}")

        call.runPatternScenario(session, "Supervisor Pattern") { scope ->
            PatternScenarios.supervisorPattern(scope)
        }
    }

    post("/api/scenarios/patterns/circuit-breaker") {
        val sessionId = call.request.queryParameters["sessionId"]
        val session = call.getOrCreateSession(sessionId, "pattern")

        logger.info("Running circuit breaker scenario in session: ${session.sessionId}")

        call.runPatternScenario(session, "Circuit Breaker") { scope ->
            PatternScenarios.circuitBreaker(scope)
        }
    }

    // ========================================================================
    // LIST ALL PATTERN SCENARIOS
    // ========================================================================

    get("/api/scenarios/patterns") {
        call.respond(
            HttpStatusCode.OK,
            mapOf(
                "scenarios" to
                    listOf(
                        PatternInfo(
                            id = "retry",
                            name = "Retry with Exponential Backoff",
                            description =
                                "Retry a failing operation 3 times with exponential backoff " +
                                    "(100ms, 200ms, 400ms). Shows structured retry pattern for transient failures.",
                            endpoint = "/api/scenarios/patterns/retry",
                            duration = "~1-2 seconds",
                        ),
                        PatternInfo(
                            id = "producer-consumer",
                            name = "Producer-Consumer",
                            description =
                                "Producer sends items to a buffered channel (capacity 5), " +
                                    "consumer processes them with delay. Shows channel-based communication.",
                            endpoint = "/api/scenarios/patterns/producer-consumer",
                            duration = "~1-2 seconds",
                        ),
                        PatternInfo(
                            id = "fan-out-fan-in",
                            name = "Fan-Out / Fan-In",
                            description =
                                "One producer, 3 worker coroutines that process in parallel, " +
                                    "results collected via channel. Shows work distribution pattern.",
                            endpoint = "/api/scenarios/patterns/fan-out-fan-in",
                            duration = "~1-2 seconds",
                        ),
                        PatternInfo(
                            id = "supervisor",
                            name = "Supervisor Pattern",
                            description =
                                "Launch children where one fails but siblings continue. " +
                                    "Shows error isolation with structured concurrency.",
                            endpoint = "/api/scenarios/patterns/supervisor",
                            duration = "~1-2 seconds",
                        ),
                        PatternInfo(
                            id = "circuit-breaker",
                            name = "Circuit Breaker",
                            description =
                                "After N consecutive failures, stop trying for a cooldown period. " +
                                    "Shows resilience pattern for protecting downstream services.",
                            endpoint = "/api/scenarios/patterns/circuit-breaker",
                            duration = "~1-2 seconds",
                        ),
                    ),
            ),
        )
    }
}

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

private suspend fun ApplicationCall.runPatternScenario(
    session: VizSession,
    scenarioName: String,
    scenario: suspend (VizScope) -> Unit,
) {
    try {
        val scope = VizScope(session)
        scenario(scope)
        respond(HttpStatusCode.OK, session.toCompletionResponse("$scenarioName completed"))
    } catch (e: Exception) {
        logger.error("Error running pattern scenario '$scenarioName'", e)
        respond(
            HttpStatusCode.InternalServerError,
            mapOf("error" to "Scenario failed: ${e.message}"),
        )
    }
}
