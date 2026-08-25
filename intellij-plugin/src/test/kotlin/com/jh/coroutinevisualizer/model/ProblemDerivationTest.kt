package com.jh.coroutinevisualizer.model

import com.jh.coroutinevisualizer.api.HierarchyNodeDto
import com.jh.coroutinevisualizer.api.LeakDto
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProblemDerivationTest {
    private fun node(
        id: String,
        state: String = "RUNNING",
        createdAtNanos: Long = 0,
        exceptionType: String? = null,
        exceptionMessage: String? = null,
        name: String = id,
    ) = HierarchyNodeDto(
        id = id,
        name = name,
        state = state,
        createdAtNanos = createdAtNanos,
        exceptionType = exceptionType,
        exceptionMessage = exceptionMessage,
    )

    @Test fun `isRealException flags real throwables and rejects null`() {
        assertTrue(ProblemDerivation.isRealException("java.lang.IllegalStateException"))
        assertFalse(ProblemDerivation.isRealException(null))
    }

    @Test fun `isRealException excludes all CancellationException variants`() {
        assertFalse(ProblemDerivation.isRealException("kotlinx.coroutines.JobCancellationException"))
        assertFalse(ProblemDerivation.isRealException("kotlin.coroutines.cancellation.CancellationException"))
        assertFalse(ProblemDerivation.isRealException("java.util.concurrent.CancellationException"))
    }

    @Test fun `problems are ordered by severity then recency`() {
        val hierarchy =
            listOf(
                node("exOld", createdAtNanos = 100, exceptionType = "java.lang.IllegalStateException"),
                node("exNew", createdAtNanos = 300, exceptionType = "java.lang.RuntimeException"),
                node("leakNode", createdAtNanos = 50),
                node("suspNode", state = "SUSPENDED", createdAtNanos = 10),
            )
        val leaks = listOf(LeakDto(coroutineId = "leakNode", aliveMs = 42_000))
        val longSuspended = mapOf("suspNode" to 0L)
        val problems = ProblemDerivation.deriveProblems(hierarchy, leaks, longSuspended, nowNanos = 35_000_000_000L)
        assertEquals(
            listOf(
                ProblemCategory.EXCEPTION,
                ProblemCategory.EXCEPTION,
                ProblemCategory.LEAK,
                ProblemCategory.LONG_SUSPENDED,
            ),
            problems.map { it.category },
        )
        // within EXCEPTION: newest (higher createdAtNanos) first (A2 recency)
        assertEquals(
            listOf("exNew", "exOld"),
            problems.filter { it.category == ProblemCategory.EXCEPTION }.map { it.coroutineId },
        )
    }

    @Test fun `why strings render one-line reasons`() {
        val hierarchy =
            listOf(
                node("ex", createdAtNanos = 0, exceptionType = "java.lang.IllegalStateException", exceptionMessage = "boom"),
                node("leakNode"),
                node("suspNode", state = "SUSPENDED"),
            )
        val problems =
            ProblemDerivation.deriveProblems(
                hierarchy,
                leaks = listOf(LeakDto(coroutineId = "leakNode", aliveMs = 42_000)),
                longSuspended = mapOf("suspNode" to 0L),
                nowNanos = 35_000_000_000L,
            )
        val byId = problems.associateBy { it.coroutineId }
        assertEquals("IllegalStateException: boom", byId.getValue("ex").why)
        assertEquals("alive ~42.0s", byId.getValue("leakNode").why)
        assertEquals("suspended ~35.0s", byId.getValue("suspNode").why)
    }

    @Test fun `exception why uses simple class name alone when message blank`() {
        val hierarchy = listOf(node("ex", exceptionType = "java.lang.IllegalStateException", exceptionMessage = "  "))
        val problems = ProblemDerivation.deriveProblems(hierarchy, emptyList(), emptyMap(), nowNanos = 0)
        assertEquals("IllegalStateException", problems.single().why)
    }

    @Test fun `exception message is truncated to keep the row one-line`() {
        val long = "x".repeat(400)
        val hierarchy = listOf(node("ex", exceptionType = "java.lang.IllegalStateException", exceptionMessage = long))
        val why = ProblemDerivation.deriveProblems(hierarchy, emptyList(), emptyMap(), nowNanos = 0).single().why
        assertTrue(why.length <= "IllegalStateException: ".length + 120, "message truncated to 120 chars")
    }

    @Test fun `leak name resolves label then node name then id`() {
        val hierarchy = listOf(node("hasNode", name = "worker"))
        val leaks =
            listOf(
                LeakDto(coroutineId = "hasLabel", label = "labelled-leak", aliveMs = 1_000),
                LeakDto(coroutineId = "hasNode", aliveMs = 1_000),
                LeakDto(coroutineId = "missing", aliveMs = 1_000),
            )
        val problems = ProblemDerivation.deriveProblems(hierarchy, leaks, emptyMap(), nowNanos = 0)
        val byId = problems.associateBy { it.coroutineId }
        assertEquals("labelled-leak", byId.getValue("hasLabel").name)
        assertEquals("worker", byId.getValue("hasNode").name)
        assertEquals("missing", byId.getValue("missing").name)
    }

    @Test fun `leaked id missing from hierarchy still produces a problem`() {
        val problems =
            ProblemDerivation.deriveProblems(
                hierarchy = emptyList(),
                leaks = listOf(LeakDto(coroutineId = "ghost", aliveMs = 5_000)),
                longSuspended = emptyMap(),
                nowNanos = 0,
            )
        assertEquals(1, problems.size)
        assertEquals("ghost", problems.single().coroutineId)
    }

    @Test fun `problemIds is the union of all category ids`() {
        val hierarchy =
            listOf(
                node("ex", exceptionType = "java.lang.IllegalStateException"),
                node("susp", state = "SUSPENDED"),
            )
        val problems =
            ProblemDerivation.deriveProblems(
                hierarchy,
                leaks = listOf(LeakDto(coroutineId = "leak", aliveMs = 1_000)),
                longSuspended = mapOf("susp" to 0L),
                nowNanos = 40_000_000_000L,
            )
        assertEquals(setOf("ex", "leak", "susp"), ProblemDerivation.problemIds(problems))
    }
}
