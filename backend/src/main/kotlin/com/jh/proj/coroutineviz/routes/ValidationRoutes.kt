package com.jh.proj.coroutineviz.routes

import com.jh.proj.coroutineviz.checksystem.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ValidationRoutes")

@Serializable
data class ValidationResponse(
    val sessionId: String,
    val results: List<ValidationResult>,
    val timing: TimingReport,
)

@Serializable
data class ValidationRule(
    val name: String,
    val description: String,
)

fun Route.registerValidationRoutes() {
    post("/api/validate/session/{id}") {
        val sessionId =
            call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing session ID"))
                return@post
            }

        // F7: resolve through the SAME tenant-scoped store the read paths use, NOT the
        // unscoped SessionManager — in DB mode (storage.type=database) sessions live in the
        // Exposed store and SessionManager is always empty, so the bare lookup 404s for every
        // real session. Mirrors the F5 scoping fix on the scenario-runner routes.
        val session = call.resolveScopedSession(sessionId)
        if (session == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Session not found"))
            return@post
        }

        val events = session.store.all()
        logger.info("Running validation on session $sessionId with ${events.size} events")

        val results = mutableListOf<ValidationResult>()

        // Run all validators. A session with an open ingest socket belongs to a running app,
        // so its unfinished coroutines are still running rather than leaked.
        results += LifecycleValidator.validate(events, sessionLive = session.hasOpenIngestSocket)
        results += HierarchyValidator.validate(events)
        results += StructuredConcurrencyValidator.validate(events)
        results += SequenceChecker.checkNoDuplicateSequenceNumbers(events)
        results += SequenceChecker.checkEventsInExactOrder(events)

        // Run timing analysis
        val timingReport = TimingAnalyzer.analyze(events)

        val response =
            ValidationResponse(
                sessionId = sessionId,
                results = results,
                timing = timingReport,
            )

        call.respond(HttpStatusCode.OK, response)
    }

    get("/api/validate/rules") {
        val rules =
            listOf(
                ValidationRule(
                    "CreatedHasStarted",
                    "Every CoroutineCreated must have a matching CoroutineStarted",
                ),
                ValidationRule(
                    "StartedHasTerminal",
                    "Every started coroutine must reach a terminal state (Completed, Cancelled, or Failed); " +
                        "while the app is still connected, unfinished coroutines are reported as running",
                ),
                ValidationRule(
                    "NoEventsAfterTerminal",
                    "No events should appear for a coroutine after its terminal event",
                ),
                ValidationRule(
                    "ChildCreatedWithinParentScope",
                    "Children must be created after their parent is created",
                ),
                ValidationRule(
                    "ParentNotCompletedBeforeChildren",
                    "Parent must not complete before all of its children (structured concurrency)",
                ),
                ValidationRule(
                    "ParentCancellationPropagation",
                    "Parent cancellation should cause children to be cancelled",
                ),
                ValidationRule(
                    "ChildFailurePropagation",
                    "Child failure should propagate to parent (unless SupervisorJob)",
                ),
                ValidationRule(
                    "NoDuplicateSequenceNumbers",
                    "No two events should share the same sequence number",
                ),
                ValidationRule(
                    "EventsInExactOrder",
                    "Events must be recorded in exact sequential order — strictly increasing " +
                        "sequence numbers with no reordering or duplicates",
                ),
            )

        call.respond(HttpStatusCode.OK, rules)
    }
}
