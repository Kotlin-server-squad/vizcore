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
                            name = "Retry with Backoff: Weather Forecast",
                            description =
                                "The weather server is overloaded and turns down the first three tries. The app retries with " +
                                    "exponential backoff, waiting 0.75 s, 1.5 s and 3 s, and the fourth try gets the forecast.",
                            endpoint = "/api/scenarios/patterns/retry",
                            duration = "~12 seconds",
                        ),
                        PatternInfo(
                            id = "producer-consumer",
                            name = "Producer-Consumer: Coffee Bar",
                            description =
                                "The cashier puts orders on a rail with room for 3 and the barista makes them, each at their own " +
                                    "pace. In the morning rush the rail fills and the cashier waits; in the quiet spell the barista " +
                                    "waits for orders.",
                            endpoint = "/api/scenarios/patterns/producer-consumer",
                            duration = "~18 seconds",
                        ),
                        PatternInfo(
                            id = "fan-out-fan-in",
                            name = "Fan-Out / Fan-In: Holiday Album",
                            description =
                                "9 photos go to 3 resizers, each photo to exactly one (fan-out). The album collects the finished " +
                                    "photos through one channel in the order they are done, not the order they were picked (fan-in).",
                            endpoint = "/api/scenarios/patterns/fan-out-fan-in",
                            duration = "~12 seconds",
                        ),
                        PatternInfo(
                            id = "supervisor",
                            name = "Supervisor: Nightly Backup",
                            description =
                                "Photos, contacts and messages back up side by side under supervisorScope. The message backup " +
                                    "fails, and photos and contacts keep going: one child's failure does not cancel its siblings.",
                            endpoint = "/api/scenarios/patterns/supervisor",
                            duration = "~14 seconds",
                        ),
                        PatternInfo(
                            id = "circuit-breaker",
                            name = "Circuit Breaker: Payment Provider",
                            description =
                                "The payment provider is down. After 3 failed charges the breaker opens and rejects orders at " +
                                    "once for 5.5 s, then lets one trial order through; the provider is back, so the breaker closes.",
                            endpoint = "/api/scenarios/patterns/circuit-breaker",
                            duration = "~22 seconds",
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
