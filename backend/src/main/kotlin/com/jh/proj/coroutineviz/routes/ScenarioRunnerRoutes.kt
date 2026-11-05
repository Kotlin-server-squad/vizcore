package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.auth.resolveTenant
import com.jh.proj.coroutineviz.scenarioDurationTimerRef
import com.jh.proj.coroutineviz.scenarios.BasicScenarios
import com.jh.proj.coroutineviz.scenarios.ChannelScenarios
import com.jh.proj.coroutineviz.scenarios.ScenarioConfigRequest
import com.jh.proj.coroutineviz.scenarios.ScenarioExecutionResponse
import com.jh.proj.coroutineviz.scenarios.ScenarioRunner
import com.jh.proj.coroutineviz.scenarios.toScenarioConfig
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("CoroutineVizRouting")

fun Route.registerScenarioRunnerRoutes() {
    demoPost("/api/scenarios/nested") {
        val sessionId = call.request.queryParameters["sessionId"]
        val session = call.getOrCreateSession(sessionId)

        logger.info("Running nested coroutines scenario in session: ${session.sessionId}")

        call.runScenarioWithResponse(session) {
            BasicScenarios.runNestedCoroutines(session)
            ScenarioCompletionResponse(
                success = true,
                sessionId = session.sessionId,
                message = "Scenario completed. Connect to /api/sessions/${session.sessionId}/stream for live events.",
                coroutineCount = session.snapshot.coroutineCount,
                eventCount = session.store.all().size,
            )
        }
    }

    demoPost("/api/scenarios/parallel") {
        val sessionId = call.request.queryParameters["sessionId"]
        val session = call.getOrCreateSession(sessionId)

        logger.info("Running parallel execution scenario in session: ${session.sessionId}")

        call.runScenarioWithResponse(session) {
            BasicScenarios.runParallelExecution(session)
            ScenarioCompletionResponse(
                success = true,
                sessionId = session.sessionId,
                message = "Parallel scenario completed",
                coroutineCount = session.snapshot.coroutineCount,
                eventCount = session.store.all().size,
            )
        }
    }

    demoPost("/api/scenarios/cancellation") {
        val sessionId = call.request.queryParameters["sessionId"]
        val session = call.getOrCreateSession(sessionId)

        logger.info("Running cancellation scenario in session: ${session.sessionId}")

        call.runScenarioWithResponse(session) {
            BasicScenarios.runCancellationScenario(session)
            ScenarioCompletionResponse(
                success = true,
                sessionId = session.sessionId,
                message = "Cancellation scenario completed",
                coroutineCount = session.snapshot.coroutineCount,
                eventCount = session.store.all().size,
            )
        }
    }

    demoPost("/api/scenarios/deep-nesting") {
        val sessionId = call.request.queryParameters["sessionId"]
        val depth = call.request.queryParameters["depth"]?.toIntOrNull() ?: 5
        val session = call.getOrCreateSession(sessionId)

        logger.info("Running deep nesting scenario (depth=$depth) in session: ${session.sessionId}")

        call.runScenarioWithResponse(session) {
            BasicScenarios.runDeepNesting(session, depth)
            ScenarioCompletionResponse(
                success = true,
                sessionId = session.sessionId,
                message = "Deep nesting scenario completed (depth=$depth)",
                coroutineCount = session.snapshot.coroutineCount,
                eventCount = session.store.all().size,
            )
        }
    }

    demoPost("/api/scenarios/mixed") {
        val sessionId = call.request.queryParameters["sessionId"]
        val session = call.getOrCreateSession(sessionId)

        logger.info("Running mixed scenario in session: ${session.sessionId}")

        call.runScenarioWithResponse(session) {
            BasicScenarios.runMixedScenario(session)
            ScenarioCompletionResponse(
                success = true,
                sessionId = session.sessionId,
                message = "Mixed scenario completed",
                coroutineCount = session.snapshot.coroutineCount,
                eventCount = session.store.all().size,
            )
        }
    }

    demoPost("/api/scenarios/exception") {
        val sessionId = call.request.queryParameters["sessionId"]
        val session = call.getOrCreateSession(sessionId)

        logger.info("Running exception scenario in session: ${session.sessionId}")

        call.runScenarioWithResponse(session) {
            BasicScenarios.runExceptionScenario(session)
            ScenarioCompletionResponse(
                success = true,
                sessionId = session.sessionId,
                message = "Exception scenario completed",
                coroutineCount = session.snapshot.coroutineCount,
                eventCount = session.store.all().size,
            )
        }
    }

    demoPost("/api/scenarios/dispatcher") {
        val sessionId = call.request.queryParameters["sessionId"]
        val session = call.getOrCreateSession(sessionId)

        logger.info("Running dispatcher scenario in session: ${session.sessionId}")

        call.runScenarioWithResponse(session) {
            BasicScenarios.runDispatcherScenario(session).join()
            ScenarioCompletionResponse(
                success = true,
                sessionId = session.sessionId,
                message = "Dispatcher scenario completed",
                coroutineCount = session.snapshot.coroutineCount,
                eventCount = session.store.all().size,
            )
        }
    }

    // ============================================================================
    // CHANNEL SCENARIOS - Channel communication patterns
    // ============================================================================

    demoPost("/api/scenarios/channel-rendezvous") {
        val sessionId = call.request.queryParameters["sessionId"]
        val session = call.getOrCreateSession(sessionId)

        logger.info("Running channel rendezvous scenario in session: ${session.sessionId}")

        call.runScenarioWithResponse(session) {
            ChannelScenarios.runRendezvous(session)
            ScenarioCompletionResponse(
                success = true,
                sessionId = session.sessionId,
                message = "Channel rendezvous scenario completed",
                coroutineCount = session.snapshot.coroutineCount,
                eventCount = session.store.all().size,
            )
        }
    }

    demoPost("/api/scenarios/channel-buffered") {
        val sessionId = call.request.queryParameters["sessionId"]
        val session = call.getOrCreateSession(sessionId)

        logger.info("Running channel buffered scenario in session: ${session.sessionId}")

        call.runScenarioWithResponse(session) {
            ChannelScenarios.runBuffered(session)
            ScenarioCompletionResponse(
                success = true,
                sessionId = session.sessionId,
                message = "Channel buffered scenario completed",
                coroutineCount = session.snapshot.coroutineCount,
                eventCount = session.store.all().size,
            )
        }
    }

    demoPost("/api/scenarios/channel-fan-out") {
        val sessionId = call.request.queryParameters["sessionId"]
        val session = call.getOrCreateSession(sessionId)

        logger.info("Running channel fan-out scenario in session: ${session.sessionId}")

        call.runScenarioWithResponse(session) {
            ChannelScenarios.runFanOut(session)
            ScenarioCompletionResponse(
                success = true,
                sessionId = session.sessionId,
                message = "Channel fan-out scenario completed",
                coroutineCount = session.snapshot.coroutineCount,
                eventCount = session.store.all().size,
            )
        }
    }

    // ============================================================================
    // REALISTIC SCENARIOS - Real-world service simulations
    // ============================================================================

    demoPost("/api/scenarios/order-processing") {
        val sessionId = call.request.queryParameters["sessionId"]
        val shouldFail = call.request.queryParameters["fail"]?.toBoolean() ?: false
        val session = call.getOrCreateSession(sessionId)

        logger.info("Running Order Processing scenario (fail=$shouldFail) in session: ${session.sessionId}")

        call.runScenarioWithResponse(session) {
            ScenarioRunner.runOrderProcessingScenario(session, shouldFail)
            ScenarioCompletionResponse(
                success = true,
                sessionId = session.sessionId,
                message = "Order Processing scenario completed",
                coroutineCount = session.snapshot.coroutineCount,
                eventCount = session.store.all().size,
            )
        }
    }

    demoPost("/api/scenarios/user-registration") {
        val sessionId = call.request.queryParameters["sessionId"]
        val shouldFailEmail = call.request.queryParameters["failEmail"]?.toBoolean() ?: false
        val session = call.getOrCreateSession(sessionId)

        logger.info("Running User Registration scenario (failEmail=$shouldFailEmail) in session: ${session.sessionId}")

        call.runScenarioWithResponse(session) {
            ScenarioRunner.runUserRegistrationScenario(session, shouldFailEmail)
            ScenarioCompletionResponse(
                success = true,
                sessionId = session.sessionId,
                message = "User Registration scenario completed",
                coroutineCount = session.snapshot.coroutineCount,
                eventCount = session.store.all().size,
            )
        }
    }

    demoPost("/api/scenarios/report-generation") {
        val sessionId = call.request.queryParameters["sessionId"]
        val shouldTimeout = call.request.queryParameters["timeout"]?.toBoolean() ?: false
        val session = call.getOrCreateSession(sessionId)

        logger.info("Running Report Generation scenario (timeout=$shouldTimeout) in session: ${session.sessionId}")

        call.runScenarioWithResponse(session) {
            ScenarioRunner.runReportGenerationScenario(session, shouldTimeout)
            ScenarioCompletionResponse(
                success = true,
                sessionId = session.sessionId,
                message = "Report Generation scenario completed",
                coroutineCount = session.snapshot.coroutineCount,
                eventCount = session.store.all().size,
            )
        }
    }

    demoPost("/api/scenarios/custom") {
        logger.info("Received custom scenario configuration request")

        try {
            val request = call.receive<ScenarioConfigRequest>()

            logger.info("Custom scenario: ${request.name}")
            logger.debug("Description: ${request.description}")

            val session = call.getOrCreateSession(request.sessionId)
            logger.info("Using session: ${session.sessionId}")

            val scenarioConfig = request.toScenarioConfig()
            ScenarioRunner.runCustomScenario(session, scenarioConfig)

            delay(100)

            val response =
                ScenarioExecutionResponse(
                    success = true,
                    sessionId = session.sessionId,
                    message = "Custom scenario '${request.name}' completed successfully",
                    coroutineCount = session.snapshot.coroutineCount,
                    eventCount = session.store.all().size,
                )

            logger.info(
                "✅ Custom scenario completed successfully | " +
                    "Coroutines: ${response.coroutineCount} | Events: ${response.eventCount}",
            )

            call.respond(HttpStatusCode.OK, response)
        } catch (e: BadRequestException) {
            // Reading the body was cancelled (the demo's time limit stopped a slow upload), and
            // Ktor reports that as a bad request too: let the cancellation through instead.
            currentCoroutineContext().ensureActive()
            // F12: a missing/malformed request body fails deserialization in call.receive(),
            // which Ktor surfaces as BadRequestException. That is client error → 400, not 500.
            logger.warn("Malformed custom scenario request body: ${e.message}")
            call.respond(
                HttpStatusCode.BadRequest,
                ScenarioExecutionResponse(
                    success = false,
                    sessionId = "",
                    message = "Malformed request body",
                    errors = listOf(e.message ?: "Could not parse scenario configuration"),
                ),
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid scenario configuration", e)
            call.respond(
                HttpStatusCode.BadRequest,
                ScenarioExecutionResponse(
                    success = false,
                    sessionId = "",
                    message = "Invalid scenario configuration: ${e.message}",
                    errors = listOf(e.message ?: "Unknown validation error"),
                ),
            )
        } catch (e: CancellationException) {
            // Client disconnect cancels the handler — rethrow to honor cooperative
            // cancellation instead of attempting respond() inside a cancelled coroutine.
            throw e
        } catch (e: Exception) {
            // Keep stack traces in server logs only; never disclose internals to clients.
            logger.error("Error running custom scenario", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ScenarioExecutionResponse(
                    success = false,
                    sessionId = "",
                    message = "Error executing scenario",
                    errors = listOf(e.message ?: "internal error"),
                ),
            )
        }
    }

    // Every demo the "New demo session" dialog can start, grouped as it shows them.
    get("/api/scenarios") {
        call.respond(HttpStatusCode.OK, DemoCatalogResponse(demoCatalog))
    }
}

private suspend fun ApplicationCall.runScenarioWithResponse(
    session: VizSession,
    block: suspend () -> ScenarioCompletionResponse,
) {
    try {
        val sample = scenarioDurationTimerRef?.let { Timer.start() }
        val response = block()
        sample?.stop(scenarioDurationTimerRef)
        delay(100)
        respond(HttpStatusCode.OK, response)
    } catch (e: CancellationException) {
        // Client disconnect cancels the handler (realistic scenarios block 15-35s on
        // job.join()) — rethrow to honor cooperative cancellation instead of trying
        // to respond inside a cancelled coroutine.
        throw e
    } catch (e: Exception) {
        // Sanitized body: message only; full stack trace stays in server logs.
        logger.error("Error running scenario", e)
        respond(
            HttpStatusCode.InternalServerError,
            mapOf("error" to "Scenario failed: ${e.message ?: "internal error"}"),
        )
    }
}

internal suspend fun ApplicationCall.getOrCreateSession(sessionId: String?): VizSession {
    val store = tenantScopedStore()
    if (store == null) {
        // Memory / auth-off: preserve legacy unscoped behavior (D-04b global visibility).
        return sessionId?.let { SessionManager.getSession(it) }
            ?: SessionManager.createSession("auto-${System.currentTimeMillis()}")
    }
    // Tenant-scoped (AUTH-04): a cross-tenant or unknown id resolves to null and falls
    // through to a NEW session owned by the caller — never a write into another tenant's
    // session. Created sessions are stamped with the caller's tenant (no orphans).
    val tenant = resolveTenant()
    return sessionId?.let { store.getSession(it, tenant) }
        ?: store.createSession("auto-${System.currentTimeMillis()}", tenant)
}
