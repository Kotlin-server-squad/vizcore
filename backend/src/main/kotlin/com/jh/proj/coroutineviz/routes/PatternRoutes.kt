package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.scenarios.PatternScenarios
import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

    demoPost("/api/scenarios/patterns/retry") {
        val session = call.getOrCreateSession(call.request.queryParameters["sessionId"], "pattern")
        logger.info("Running retry with backoff scenario in session: ${session.sessionId}")
        call.runPatternScenario(session, "Retry with Backoff: Weather Forecast") { PatternScenarios.runRetry(session) }
    }

    demoPost("/api/scenarios/patterns/producer-consumer") {
        val session = call.getOrCreateSession(call.request.queryParameters["sessionId"], "pattern")
        logger.info("Running producer-consumer scenario in session: ${session.sessionId}")
        call.runPatternScenario(session, "Producer-Consumer: Coffee Bar") { PatternScenarios.runProducerConsumer(session) }
    }

    demoPost("/api/scenarios/patterns/fan-out-fan-in") {
        val session = call.getOrCreateSession(call.request.queryParameters["sessionId"], "pattern")
        logger.info("Running fan-out/fan-in scenario in session: ${session.sessionId}")
        call.runPatternScenario(session, "Fan-Out / Fan-In: Holiday Album") { PatternScenarios.runFanOutFanIn(session) }
    }

    demoPost("/api/scenarios/patterns/supervisor") {
        val session = call.getOrCreateSession(call.request.queryParameters["sessionId"], "pattern")
        logger.info("Running supervisor scenario in session: ${session.sessionId}")
        call.runPatternScenario(session, "Supervisor: Nightly Backup") { PatternScenarios.runSupervisor(session) }
    }

    demoPost("/api/scenarios/patterns/circuit-breaker") {
        val session = call.getOrCreateSession(call.request.queryParameters["sessionId"], "pattern")
        logger.info("Running circuit breaker scenario in session: ${session.sessionId}")
        call.runPatternScenario(session, "Circuit Breaker: Payment Provider") { PatternScenarios.runCircuitBreaker(session) }
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

/**
 * Runs a pattern demo and answers once it has ended, as the pattern routes always have; the demo
 * itself returns its root coroutine at once (see [PatternScenarios]).
 */
private suspend fun ApplicationCall.runPatternScenario(
    session: VizSession,
    scenarioName: String,
    start: suspend () -> Job,
) {
    try {
        start().join()
        respond(HttpStatusCode.OK, session.toCompletionResponse("$scenarioName completed"))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("Error running pattern scenario '$scenarioName'", e)
        respond(
            HttpStatusCode.InternalServerError,
            mapOf("error" to "Scenario failed: ${e.message}"),
        )
    }
}
