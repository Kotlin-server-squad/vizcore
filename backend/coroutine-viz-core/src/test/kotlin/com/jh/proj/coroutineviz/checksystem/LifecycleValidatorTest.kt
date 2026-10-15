package com.jh.proj.coroutineviz.checksystem

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class LifecycleValidatorTest {
    private fun created(
        coroutineId: String,
        seq: Long,
        tsNanos: Long = seq * 1000,
        parentCoroutineId: String? = null,
    ): CoroutineCreated =
        CoroutineCreated(
            sessionId = "test-session",
            seq = seq,
            tsNanos = tsNanos,
            coroutineId = coroutineId,
            jobId = "job-$coroutineId",
            parentCoroutineId = parentCoroutineId,
            scopeId = "scope-1",
            label = null,
        )

    private fun started(
        coroutineId: String,
        seq: Long,
        tsNanos: Long = seq * 1000,
    ): CoroutineStarted =
        CoroutineStarted(
            sessionId = "test-session",
            seq = seq,
            tsNanos = tsNanos,
            coroutineId = coroutineId,
            jobId = "job-$coroutineId",
            parentCoroutineId = null,
            scopeId = "scope-1",
            label = null,
        )

    private fun completed(
        coroutineId: String,
        seq: Long,
        tsNanos: Long = seq * 1000,
    ): CoroutineCompleted =
        CoroutineCompleted(
            sessionId = "test-session",
            seq = seq,
            tsNanos = tsNanos,
            coroutineId = coroutineId,
            jobId = "job-$coroutineId",
            parentCoroutineId = null,
            scopeId = "scope-1",
            label = null,
        )

    private fun cancelled(
        coroutineId: String,
        seq: Long,
        tsNanos: Long = seq * 1000,
    ): CoroutineCancelled =
        CoroutineCancelled(
            sessionId = "test-session",
            seq = seq,
            tsNanos = tsNanos,
            coroutineId = coroutineId,
            jobId = "job-$coroutineId",
            parentCoroutineId = null,
            scopeId = "scope-1",
            label = null,
            cause = "test cancellation",
        )

    private fun suspended(
        coroutineId: String,
        seq: Long,
        tsNanos: Long = seq * 1000,
    ): CoroutineSuspended =
        CoroutineSuspended(
            sessionId = "test-session",
            seq = seq,
            tsNanos = tsNanos,
            coroutineId = coroutineId,
            jobId = "job-$coroutineId",
            parentCoroutineId = null,
            scopeId = "scope-1",
            label = null,
            reason = "delay",
        )

    @Test
    fun `valid lifecycle passes all checks`() {
        val events: List<VizEvent> =
            listOf(
                created("c1", 1),
                started("c1", 2),
                completed("c1", 3),
            )

        val results = LifecycleValidator.validate(events)
        assertTrue(results.all { it is ValidationResult.Pass }, "All checks should pass for valid lifecycle")
    }

    @Test
    fun `missing Started after Created fails`() {
        val events: List<VizEvent> =
            listOf(
                created("c1", 1),
            )

        val results = LifecycleValidator.validate(events)
        val createdHasStarted = results.first { it.ruleName == "CreatedHasStarted" }
        assertTrue(createdHasStarted is ValidationResult.Fail, "Should fail when Started is missing")
        assertTrue(
            (createdHasStarted as ValidationResult.Fail).message.contains("never started"),
            "Failure message should mention 'never started'",
        )
    }

    @Test
    fun `events after terminal state detected`() {
        val events: List<VizEvent> =
            listOf(
                created("c1", 1),
                started("c1", 2),
                completed("c1", 3),
                // This event is after the terminal state
                suspended("c1", 4),
            )

        val results = LifecycleValidator.validate(events)
        val noEventsAfterTerminal = results.first { it.ruleName == "NoEventsAfterTerminal" }
        assertTrue(
            noEventsAfterTerminal is ValidationResult.Fail,
            "Should fail when events appear after terminal state",
        )
        assertTrue(
            (noEventsAfterTerminal as ValidationResult.Fail).details.contains("seq=4"),
            "Failure should reference the offending event sequence number",
        )
    }

    @Test
    fun `cancellation lifecycle passes`() {
        val events: List<VizEvent> =
            listOf(
                created("c1", 1),
                started("c1", 2),
                cancelled("c1", 3),
            )

        val results = LifecycleValidator.validate(events)
        assertTrue(results.all { it is ValidationResult.Pass }, "Cancellation lifecycle should pass")
    }

    @Test
    fun `multiple coroutines validated independently`() {
        val events: List<VizEvent> =
            listOf(
                created("c1", 1),
                started("c1", 2),
                completed("c1", 3),
                created("c2", 4),
                // c2 is never started -- should fail
            )

        val results = LifecycleValidator.validate(events)

        // c1 should have all passes
        val c1Results = results.filter { it.message.contains("c1") }
        assertTrue(c1Results.all { it is ValidationResult.Pass }, "c1 should pass all checks")

        // c2 should have a failure for CreatedHasStarted
        val c2Failure =
            results.first {
                it.ruleName == "CreatedHasStarted" && it.message.contains("c2")
            }
        assertTrue(c2Failure is ValidationResult.Fail, "c2 should fail CreatedHasStarted")
    }

    @Test
    fun `started coroutine without terminal event fails`() {
        val events: List<VizEvent> =
            listOf(
                created("c1", 1),
                started("c1", 2),
                suspended("c1", 3),
            )

        val results = LifecycleValidator.validate(events)
        val startedHasTerminal = results.first { it.ruleName == "StartedHasTerminal" }
        assertTrue(
            startedHasTerminal is ValidationResult.Fail,
            "Should fail when started coroutine has no terminal event",
        )
    }

    @Test
    fun `started coroutine without terminal event is running, not failed, on a live session`() {
        val events: List<VizEvent> =
            listOf(
                created("c1", 1),
                started("c1", 2),
                suspended("c1", 3),
            )

        val results = LifecycleValidator.validate(events, sessionLive = true)
        val startedHasTerminal = results.first { it.ruleName == "StartedHasTerminal" }
        assertTrue(
            startedHasTerminal is ValidationResult.Pass,
            "A live session's unfinished coroutine is still running, not a failure",
        )
        assertTrue(startedHasTerminal.message.contains("still running"), startedHasTerminal.message)
    }

    @Test
    fun `live session still fails other lifecycle rules`() {
        val events: List<VizEvent> =
            listOf(
                created("c1", 1),
                started("c1", 2),
                completed("c1", 3),
                suspended("c1", 4),
            )

        val results = LifecycleValidator.validate(events, sessionLive = true)
        assertTrue(
            results.first { it.ruleName == "NoEventsAfterTerminal" } is ValidationResult.Fail,
            "sessionLive only relaxes StartedHasTerminal",
        )
    }
}
