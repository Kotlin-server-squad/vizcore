package com.jh.proj.coroutineviz.checksystem

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class SequenceCheckerTest {
    private fun created(seq: Long): CoroutineCreated =
        CoroutineCreated(
            sessionId = "test-session",
            seq = seq,
            tsNanos = seq * 1000,
            coroutineId = "c1",
            jobId = "job-c1",
            parentCoroutineId = null,
            scopeId = "scope-1",
            label = null,
        )

    private fun started(seq: Long): CoroutineStarted =
        CoroutineStarted(
            sessionId = "test-session",
            seq = seq,
            tsNanos = seq * 1000,
            coroutineId = "c1",
            jobId = "job-c1",
            parentCoroutineId = null,
            scopeId = "scope-1",
            label = null,
        )

    private fun completed(seq: Long): CoroutineCompleted =
        CoroutineCompleted(
            sessionId = "test-session",
            seq = seq,
            tsNanos = seq * 1000,
            coroutineId = "c1",
            jobId = "job-c1",
            parentCoroutineId = null,
            scopeId = "scope-1",
            label = null,
        )

    @Test
    fun `correct order passes`() {
        val events: List<VizEvent> =
            listOf(
                created(1),
                started(2),
                completed(3),
            )

        val result =
            SequenceChecker.checkOrdering(
                events,
                listOf("CoroutineCreated", "CoroutineStarted", "CoroutineCompleted"),
            )
        assertTrue(result is ValidationResult.Pass, "Correct ordering should pass")
    }

    @Test
    fun `wrong order fails`() {
        val events: List<VizEvent> =
            listOf(
                started(1),
                created(2),
                completed(3),
            )

        val result =
            SequenceChecker.checkOrdering(
                events,
                listOf("CoroutineCreated", "CoroutineStarted", "CoroutineCompleted"),
            )
        // started(seq=1) comes first, so Created is matched at seq=2, but then Started
        // has already been passed, so it won't match "CoroutineStarted" again.
        assertTrue(result is ValidationResult.Fail, "Wrong ordering should fail")
    }

    @Test
    fun `duplicate sequence numbers detected`() {
        val events: List<VizEvent> =
            listOf(
                created(1),
                // Duplicate seq=1
                started(1),
                completed(2),
            )

        val result = SequenceChecker.checkNoDuplicateSequenceNumbers(events)
        assertTrue(result is ValidationResult.Fail, "Duplicate sequence numbers should be detected")
        assertTrue(
            (result as ValidationResult.Fail).details.contains("seq=1"),
            "Details should mention the duplicate sequence number",
        )
    }

    @Test
    fun `unique sequence numbers pass`() {
        val events: List<VizEvent> =
            listOf(
                created(1),
                started(2),
                completed(3),
            )

        val result = SequenceChecker.checkNoDuplicateSequenceNumbers(events)
        assertTrue(result is ValidationResult.Pass, "Unique sequence numbers should pass")
    }

    // --- EventsInExactOrder (the "events sent in exact order" rule) -----------------------

    @Test
    fun `exact order passes for strictly increasing non-contiguous sequences`() {
        // Gaps are EXPECTED — VizSession.nextSeq() also mints ids — so non-contiguous but
        // strictly increasing must still PASS.
        val events: List<VizEvent> = listOf(created(3), started(4), completed(8))

        val result = SequenceChecker.checkEventsInExactOrder(events)
        assertTrue(result is ValidationResult.Pass, "Strictly increasing (with gaps) must pass: $result")
    }

    @Test
    fun `exact order fails when an event is out of sequence`() {
        val events: List<VizEvent> = listOf(created(1), completed(5), started(3))

        val result = SequenceChecker.checkEventsInExactOrder(events)
        assertTrue(result is ValidationResult.Fail, "Out-of-order sequence must fail")
        assertTrue(
            (result as ValidationResult.Fail).details.contains("seq=3"),
            "Details should point at the offending event: ${result.details}",
        )
    }

    @Test
    fun `exact order fails on a duplicate sequence number (not strictly increasing)`() {
        val events: List<VizEvent> = listOf(created(1), started(2), completed(2))

        val result = SequenceChecker.checkEventsInExactOrder(events)
        assertTrue(result is ValidationResult.Fail, "A repeated seq is not strictly increasing")
    }

    @Test
    fun `exact order trivially passes for fewer than two events`() {
        assertTrue(SequenceChecker.checkEventsInExactOrder(emptyList()) is ValidationResult.Pass)
        assertTrue(SequenceChecker.checkEventsInExactOrder(listOf(created(1))) is ValidationResult.Pass)
    }
}
