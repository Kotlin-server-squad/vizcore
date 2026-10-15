package com.jh.proj.coroutineviz.checksystem

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted

/**
 * Validates coroutine lifecycle correctness across an event stream.
 *
 * Rules enforced:
 * - Every [CoroutineCreated] must have a matching [CoroutineStarted]
 * - Every started coroutine must reach a terminal state (Completed, Cancelled, or Failed),
 *   judged only once the session has ended — on a live session a coroutine without a
 *   terminal event is simply still running
 * - No events should appear for a coroutine after its terminal event
 */
object LifecycleValidator {
    private val TERMINAL_KINDS = setOf("CoroutineCompleted", "CoroutineCancelled", "CoroutineFailed")

    /**
     * Run all lifecycle validation rules against the given event list.
     *
     * @param events Full event stream (may contain events for multiple coroutines)
     * @param sessionLive Whether the app producing [events] is still connected. A live
     *   session's still-running coroutines are reported as running, not as never terminated:
     *   an app that is up has long-lived coroutines (servers, SSE streams, consumers).
     * @return List of [ValidationResult] -- one per rule per coroutine
     */
    fun validate(
        events: List<VizEvent>,
        sessionLive: Boolean = false,
    ): List<ValidationResult> {
        val results = mutableListOf<ValidationResult>()

        // Group coroutine events by coroutine ID
        val byCoroutine =
            events
                .filterIsInstance<CoroutineEvent>()
                .groupBy { it.coroutineId }

        for ((coroutineId, coroutineEvents) in byCoroutine) {
            val sorted = coroutineEvents.sortedBy { it.seq }
            results += checkCreatedHasStarted(coroutineId, sorted)
            results += checkStartedHasTerminal(coroutineId, sorted, sessionLive)
            results += checkNoEventsAfterTerminal(coroutineId, sorted)
        }

        if (byCoroutine.isEmpty()) {
            results +=
                ValidationResult.Pass(
                    "LifecycleValidation",
                    "No coroutine events to validate",
                )
        }

        return results
    }

    /** Ids of coroutines that started and have no terminal event, in first-seen order. */
    fun unfinishedCoroutineIds(events: List<VizEvent>): List<String> =
        events
            .filterIsInstance<CoroutineEvent>()
            .groupBy { it.coroutineId }
            .filter { (_, coroutineEvents) ->
                coroutineEvents.any { it is CoroutineStarted } && coroutineEvents.none { it.kind in TERMINAL_KINDS }
            }.keys
            .toList()

    private fun checkCreatedHasStarted(
        coroutineId: String,
        events: List<CoroutineEvent>,
    ): ValidationResult {
        val ruleName = "CreatedHasStarted"
        val hasCreated = events.any { it is CoroutineCreated }
        val hasStarted = events.any { it is CoroutineStarted }

        return if (!hasCreated) {
            ValidationResult.Pass(ruleName, "Coroutine $coroutineId has no Created event (skipped)")
        } else if (hasStarted) {
            ValidationResult.Pass(ruleName, "Coroutine $coroutineId: Created is followed by Started")
        } else {
            ValidationResult.Fail(
                ruleName,
                "Coroutine $coroutineId was created but never started",
                "CoroutineCreated found at seq=${events.first { it is CoroutineCreated }.seq} " +
                    "but no matching CoroutineStarted exists",
            )
        }
    }

    private fun checkStartedHasTerminal(
        coroutineId: String,
        events: List<CoroutineEvent>,
        sessionLive: Boolean,
    ): ValidationResult {
        val ruleName = "StartedHasTerminal"
        val hasStarted = events.any { it is CoroutineStarted }
        val hasTerminal = events.any { it.kind in TERMINAL_KINDS }

        return if (!hasStarted) {
            ValidationResult.Pass(ruleName, "Coroutine $coroutineId not started (skipped)")
        } else if (hasTerminal) {
            val terminalKind = events.first { it.kind in TERMINAL_KINDS }.kind
            ValidationResult.Pass(
                ruleName,
                "Coroutine $coroutineId: Started coroutine reached terminal state ($terminalKind)",
            )
        } else if (sessionLive) {
            ValidationResult.Pass(
                ruleName,
                "Coroutine $coroutineId is still running (session is live; checked once the app disconnects)",
            )
        } else {
            ValidationResult.Fail(
                ruleName,
                "Coroutine $coroutineId was started but never terminated",
                "CoroutineStarted found but no terminal event " +
                    "(CoroutineCompleted, CoroutineCancelled, or CoroutineFailed) exists",
            )
        }
    }

    private fun checkNoEventsAfterTerminal(
        coroutineId: String,
        events: List<CoroutineEvent>,
    ): ValidationResult {
        val ruleName = "NoEventsAfterTerminal"
        val terminalEvent =
            events.firstOrNull { it.kind in TERMINAL_KINDS }
                ?: return ValidationResult.Pass(
                    ruleName,
                    "Coroutine $coroutineId has no terminal event (skipped)",
                )

        val eventsAfterTerminal = events.filter { it.seq > terminalEvent.seq }

        return if (eventsAfterTerminal.isEmpty()) {
            ValidationResult.Pass(
                ruleName,
                "Coroutine $coroutineId: No events after terminal event (${terminalEvent.kind})",
            )
        } else {
            ValidationResult.Fail(
                ruleName,
                "Coroutine $coroutineId has ${eventsAfterTerminal.size} event(s) after terminal state",
                "Terminal event ${terminalEvent.kind} at seq=${terminalEvent.seq}, " +
                    "but found: ${eventsAfterTerminal.map { "${it.kind}@seq=${it.seq}" }}",
            )
        }
    }
}
