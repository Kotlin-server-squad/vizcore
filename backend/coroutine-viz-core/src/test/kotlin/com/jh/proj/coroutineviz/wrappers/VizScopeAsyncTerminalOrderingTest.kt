package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.job.JobStateChanged
import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.validation.rules.NoEventsAfterTerminalRule
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for terminal-event ordering in vizAsync's invokeOnCompletion
 * handler (IN-05) — mirrors [VizScopeTerminalOrderingTest], which covers vizLaunch.
 *
 * Invariant: for every async coroutine, its terminal lifecycle event
 * (CoroutineCancelled, CoroutineFailed) must carry the highest seq among all
 * events with that coroutineId, and JobStateChanged must be emitted BEFORE the
 * terminal event so downstream views see job-state transitions for async
 * coroutines just like launched ones.
 *
 * Timing note: VizScope runs vizAsync coroutines on [VizSession.sessionScope]'s REAL
 * [kotlinx.coroutines.Dispatchers.Default]. These tests use [runBlocking] (real time) and poll
 * the store until the terminal event has actually been emitted, rather than `runTest`'s
 * virtual-time `delay`, which would skip ahead without waiting for the real-dispatcher
 * invokeOnCompletion handler — the race that made these flaky on loaded CI runners.
 */
class VizScopeAsyncTerminalOrderingTest {

    /**
     * Test A – Failed ordering: the failing async coroutine's max-seq event must be
     * CoroutineFailed, preceded by a JobStateChanged with a strictly lower seq.
     */
    @Test
    fun `failed async coroutine emits JobStateChanged before CoroutineFailed`() = runBlocking {
        val session = VizSession("test-async-terminal-ordering-failed")
        val exceptionHandler = CoroutineExceptionHandler { _, _ -> /* swallow expected exception */ }
        val isolatedScope = CoroutineScope(SupervisorJob() + exceptionHandler)
        val viz = VizScope(session, context = isolatedScope.coroutineContext)

        val deferred = viz.vizAsync("failing-async") {
            delay(10)
            throw IllegalStateException("Intentional failure for async terminal-ordering test")
        }

        try {
            deferred.await()
        } catch (_: Exception) {
            // expected — the async block throws
        }
        // Wait (real time) until the failing-async coroutine's terminal event has been emitted.
        awaitTerminalLabel(session) { it is CoroutineFailed && it.label == "failing-async" }

        val events = session.store.all()
        val failedCoroutineId = events
            .filterIsInstance<CoroutineFailed>()
            .first { it.label == "failing-async" }
            .coroutineId

        val eventsForFailing = events
            .filter { it is com.jh.proj.coroutineviz.events.CoroutineEvent && it.coroutineId == failedCoroutineId }
            .map { it as com.jh.proj.coroutineviz.events.CoroutineEvent }

        val maxSeqEvent = eventsForFailing.maxByOrNull { it.seq }
            ?: error("No events found for failing async coroutine $failedCoroutineId")

        assertEquals(
            "CoroutineFailed",
            maxSeqEvent.kind,
            "The highest-seq event for the failing async coroutine must be CoroutineFailed, " +
                "but got ${maxSeqEvent.kind}@seq=${maxSeqEvent.seq}. " +
                "All events: ${eventsForFailing.sortedBy { it.seq }.map { "${it.kind}@seq=${it.seq}" }}",
        )

        val jobStateChangedForFailing = eventsForFailing.filterIsInstance<JobStateChanged>()
        assertTrue(
            jobStateChangedForFailing.isNotEmpty(),
            "vizAsync's failure path must emit JobStateChanged (parity with vizLaunch, IN-05)",
        )
        val terminalSeq = maxSeqEvent.seq
        jobStateChangedForFailing.forEach { jsc ->
            assertTrue(
                jsc.seq < terminalSeq,
                "JobStateChanged@seq=${jsc.seq} must have a lower seq than CoroutineFailed@seq=$terminalSeq",
            )
        }
    }

    /**
     * Test B – Cancelled ordering: the cancelled async coroutine's max-seq event must
     * be CoroutineCancelled, preceded by a JobStateChanged with a strictly lower seq.
     */
    @Test
    fun `cancelled async coroutine emits JobStateChanged before CoroutineCancelled`() = runBlocking {
        val session = VizSession("test-async-terminal-ordering-cancelled")
        val viz = VizScope(session)

        val deferred = viz.vizAsync("cancellable-async") {
            delay(10_000) // long-lived so we can cancel it
        }

        delay(50)
        deferred.cancel()

        try {
            deferred.join()
        } catch (_: Exception) {
            // expected
        }
        // Wait (real time) until the cancellable-async coroutine's terminal event has been emitted.
        awaitTerminalLabel(session) { it is CoroutineCancelled && it.label == "cancellable-async" }

        val events = session.store.all()
        val cancelledCoroutineId = events
            .filterIsInstance<CoroutineCancelled>()
            .first { it.label == "cancellable-async" }
            .coroutineId

        val eventsForCancelled = events
            .filter { it is com.jh.proj.coroutineviz.events.CoroutineEvent && it.coroutineId == cancelledCoroutineId }
            .map { it as com.jh.proj.coroutineviz.events.CoroutineEvent }

        val maxSeqEvent = eventsForCancelled.maxByOrNull { it.seq }
            ?: error("No events found for cancelled async coroutine $cancelledCoroutineId")

        assertEquals(
            "CoroutineCancelled",
            maxSeqEvent.kind,
            "The highest-seq event for the cancelled async coroutine must be CoroutineCancelled, " +
                "but got ${maxSeqEvent.kind}@seq=${maxSeqEvent.seq}. " +
                "All events: ${eventsForCancelled.sortedBy { it.seq }.map { "${it.kind}@seq=${it.seq}" }}",
        )

        val jobStateChangedForCancelled = eventsForCancelled.filterIsInstance<JobStateChanged>()
        assertTrue(
            jobStateChangedForCancelled.isNotEmpty(),
            "vizAsync's cancellation path must emit JobStateChanged (parity with vizLaunch, IN-05)",
        )
        val terminalSeq = maxSeqEvent.seq
        jobStateChangedForCancelled.forEach { jsc ->
            assertTrue(
                jsc.seq < terminalSeq,
                "JobStateChanged@seq=${jsc.seq} must have a lower seq than CoroutineCancelled@seq=$terminalSeq",
            )
        }
    }

    /**
     * Test C – Validator clean: NoEventsAfterTerminalRule over the instrumented
     * failing-async event stream must produce zero findings.
     */
    @Test
    fun `NoEventsAfterTerminalRule produces zero findings for instrumented failing-async scenario`() = runBlocking {
        val session = VizSession("test-async-terminal-validator-clean")
        val exceptionHandler = CoroutineExceptionHandler { _, _ -> /* swallow */ }
        val isolatedScope = CoroutineScope(SupervisorJob() + exceptionHandler)
        val viz = VizScope(session, context = isolatedScope.coroutineContext)

        val deferred = viz.vizAsync("failing-async") {
            delay(10)
            throw IllegalStateException("Intentional failure for async validator test")
        }

        try {
            deferred.await()
        } catch (_: Exception) {
            // expected
        }
        // Wait until the failing-async terminal event is emitted so the validator sees the full stream.
        awaitTerminalLabel(session) { it is CoroutineFailed && it.label == "failing-async" }

        val events = session.store.all()
        val findings = NoEventsAfterTerminalRule().validate(events)

        assertEquals(
            0,
            findings.size,
            "NoEventsAfterTerminalRule must report zero findings for the instrumentation's own output. " +
                "Got ${findings.size} finding(s): ${findings.map { "${it.message} [${it.affectedEntities}]" }}",
        )
    }
}
