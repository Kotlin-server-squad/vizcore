package com.jh.proj.coroutineviz.checksystem

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineBodyCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class HierarchyValidatorTest {
    private fun created(
        coroutineId: String,
        seq: Long,
        parentCoroutineId: String? = null,
        scopeId: String = "scope-1",
    ): CoroutineCreated =
        CoroutineCreated(
            sessionId = "test-session",
            seq = seq,
            tsNanos = seq * 1000,
            coroutineId = coroutineId,
            jobId = "job-$coroutineId",
            parentCoroutineId = parentCoroutineId,
            scopeId = scopeId,
            label = null,
        )

    private fun started(
        coroutineId: String,
        seq: Long,
    ): CoroutineStarted =
        CoroutineStarted(
            sessionId = "test-session",
            seq = seq,
            tsNanos = seq * 1000,
            coroutineId = coroutineId,
            jobId = "job-$coroutineId",
            parentCoroutineId = null,
            scopeId = "scope-1",
            label = null,
        )

    private fun completed(
        coroutineId: String,
        seq: Long,
    ): CoroutineCompleted =
        CoroutineCompleted(
            sessionId = "test-session",
            seq = seq,
            tsNanos = seq * 1000,
            coroutineId = coroutineId,
            jobId = "job-$coroutineId",
            parentCoroutineId = null,
            scopeId = "scope-1",
            label = null,
        )

    private fun bodyCompleted(
        coroutineId: String,
        seq: Long,
    ): CoroutineBodyCompleted =
        CoroutineBodyCompleted(
            sessionId = "test-session",
            seq = seq,
            tsNanos = seq * 1000,
            coroutineId = coroutineId,
            jobId = "job-$coroutineId",
            parentCoroutineId = null,
            scopeId = "scope-1",
            label = null,
        )

    /**
     * Regression for the validation false-positive surfaced in the live UAT after Phase 08.5:
     * DebugProbes-sourced streams emit a single `CoroutineCompleted` at coroutine BODY/vanish
     * completion and never emit `CoroutineBodyCompleted`/`WaitingForChildren`. In such a "coarse"
     * stream a parent legitimately vanishes (body done, WAITING_FOR_CHILDREN) before its children,
     * so its `CoroutineCompleted` precedes the children's. The rule must NOT treat that ordering as
     * a structured-concurrency violation when the source does not model body-vs-tree completion.
     */
    @Test
    fun `coarse source (no body-completed) does not flag parent terminal before child`() {
        val events: List<VizEvent> =
            listOf(
                created("parent", 1),
                started("parent", 2),
                created("child", 3, parentCoroutineId = "parent"),
                started("child", 4),
                // Parent vanishes (body done) before the child — normal for DebugProbes streams.
                completed("parent", 5),
                completed("child", 6),
            )

        val results = HierarchyValidator.validate(events)
        val parentBeforeChildFailures =
            results.filter {
                it.ruleName == "ParentNotCompletedBeforeChildren" && it is ValidationResult.Fail
            }
        assertTrue(
            parentBeforeChildFailures.isEmpty(),
            "Coarse (DebugProbes-style) stream lacks CoroutineBodyCompleted, so CoroutineCompleted " +
                "denotes body completion — parent terminal before child must not be flagged. " +
                "Got: $parentBeforeChildFailures",
        )
    }

    @Test
    fun `valid hierarchy passes`() {
        val events: List<VizEvent> =
            listOf(
                created("parent", 1),
                started("parent", 2),
                created("child", 3, parentCoroutineId = "parent"),
                started("child", 4),
                completed("child", 5),
                completed("parent", 6),
            )

        val results = HierarchyValidator.validate(events)
        assertTrue(
            results.all { it is ValidationResult.Pass },
            "Valid hierarchy should pass all checks. Failures: ${results.filterIsInstance<ValidationResult.Fail>()}",
        )
    }

    @Test
    fun `rich source flags parent terminal before child`() {
        // A source that models body-vs-tree completion (emits CoroutineBodyCompleted, like the
        // VizScope wrapper) is held to the strict ordering rule: a parent whose tree-terminal
        // precedes a child's terminal is a genuine structured-concurrency violation.
        val events: List<VizEvent> =
            listOf(
                created("parent", 1),
                started("parent", 2),
                created("child", 3, parentCoroutineId = "parent"),
                started("child", 4),
                bodyCompleted("child", 5),
                bodyCompleted("parent", 6),
                // Parent's tree-terminal lands before the child's — the violation under assertion.
                completed("parent", 7),
                completed("child", 8),
            )

        val results = HierarchyValidator.validate(events)
        val parentBeforeChild =
            results.filter {
                it.ruleName == "ParentNotCompletedBeforeChildren" && it is ValidationResult.Fail
            }
        assertTrue(
            parentBeforeChild.isNotEmpty(),
            "Should detect parent completing before child when the source models body completion",
        )
    }

    @Test
    fun `deeply nested hierarchy validates`() {
        val events: List<VizEvent> =
            listOf(
                created("root", 1),
                started("root", 2),
                created("mid", 3, parentCoroutineId = "root"),
                started("mid", 4),
                created("leaf", 5, parentCoroutineId = "mid"),
                started("leaf", 6),
                completed("leaf", 7),
                completed("mid", 8),
                completed("root", 9),
            )

        val results = HierarchyValidator.validate(events)
        assertTrue(
            results.all { it is ValidationResult.Pass },
            "Deeply nested hierarchy should pass. Failures: ${results.filterIsInstance<ValidationResult.Fail>()}",
        )
    }

    @Test
    fun `child created before parent fails`() {
        val events: List<VizEvent> =
            listOf(
                created("child", 1, parentCoroutineId = "parent"),
                created("parent", 2),
                started("parent", 3),
                started("child", 4),
                completed("child", 5),
                completed("parent", 6),
            )

        val results = HierarchyValidator.validate(events)
        val scopeCheck =
            results.filter {
                it.ruleName == "ChildCreatedWithinParentScope" && it is ValidationResult.Fail
            }
        assertTrue(
            scopeCheck.isNotEmpty(),
            "Should detect child created before parent",
        )
    }
}
