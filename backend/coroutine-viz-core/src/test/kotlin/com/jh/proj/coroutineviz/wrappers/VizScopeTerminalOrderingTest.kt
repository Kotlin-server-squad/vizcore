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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for terminal-event ordering in VizScope's invokeOnCompletion handler.
 *
 * Invariant: for every coroutine, its terminal lifecycle event (CoroutineCompleted,
 * CoroutineCancelled, CoroutineFailed) must carry the highest seq among all events
 * with that coroutineId. JobStateChanged (where present) must appear BEFORE the terminal
 * event so it receives a strictly lower seq.
 *
 * Failure of this invariant triggers NoEventsAfterTerminalRule findings, which causes
 * the app to flag its own instrumentation on every Exception Handling scenario run.
 */
class VizScopeTerminalOrderingTest {

    /**
     * Test A – Failed ordering: the failing coroutine's max-seq event must be CoroutineFailed,
     * and any JobStateChanged for that coroutineId must have a strictly lower seq.
     */
    @Test
    fun `failed coroutine terminal event has highest seq among its events`(): Unit = runTest {
        val session = VizSession("test-terminal-ordering-failed")
        val exceptionHandler = CoroutineExceptionHandler { _, _ -> /* swallow expected exception */ }
        val isolatedScope = CoroutineScope(SupervisorJob() + exceptionHandler)
        val viz = VizScope(session, context = isolatedScope.coroutineContext)

        val job = viz.vizLaunch("parent") {
            vizLaunch("failing-child") {
                delay(10)
                throw IllegalStateException("Intentional failure for terminal-ordering test")
            }.join()
        }

        try {
            job.join()
        } catch (_: Exception) {
            // expected — parent cancelled due to child failure
        }
        // Give invokeOnCompletion handlers time to fire
        delay(200)

        val events = session.store.all()
        val failedCoroutineId = events
            .filterIsInstance<CoroutineFailed>()
            .first { it.label == "failing-child" }
            .coroutineId

        val eventsForFailing = events
            .filter { it is com.jh.proj.coroutineviz.events.CoroutineEvent && it.coroutineId == failedCoroutineId }
            .map { it as com.jh.proj.coroutineviz.events.CoroutineEvent }

        val maxSeqEvent = eventsForFailing.maxByOrNull { it.seq }
            ?: error("No events found for failing coroutine $failedCoroutineId")

        assertEquals(
            "CoroutineFailed",
            maxSeqEvent.kind,
            "The highest-seq event for the failing coroutine must be CoroutineFailed, " +
                "but got ${maxSeqEvent.kind}@seq=${maxSeqEvent.seq}. " +
                "All events: ${eventsForFailing.sortedBy { it.seq }.map { "${it.kind}@seq=${it.seq}" }}",
        )

        val jobStateChangedForFailing = eventsForFailing.filterIsInstance<JobStateChanged>()
        if (jobStateChangedForFailing.isNotEmpty()) {
            val terminalSeq = maxSeqEvent.seq
            jobStateChangedForFailing.forEach { jsc ->
                assertTrue(
                    jsc.seq < terminalSeq,
                    "JobStateChanged@seq=${jsc.seq} must have a lower seq than CoroutineFailed@seq=$terminalSeq",
                )
            }
        }
    }

    /**
     * Test B – Cancelled ordering: the cancelled coroutine's max-seq event must be
     * CoroutineCancelled, and any JobStateChanged for that coroutineId must have a
     * strictly lower seq.
     */
    @Test
    fun `cancelled coroutine terminal event has highest seq among its events`(): Unit = runTest {
        val session = VizSession("test-terminal-ordering-cancelled")
        val viz = VizScope(session)

        val job = viz.vizLaunch("cancellable") {
            delay(10_000) // long-lived so we can cancel it
        }

        delay(50)
        job.cancel()

        try {
            job.join()
        } catch (_: Exception) {
            // expected
        }
        delay(200)

        val events = session.store.all()
        val cancelledCoroutineId = events
            .filterIsInstance<CoroutineCancelled>()
            .first { it.label == "cancellable" }
            .coroutineId

        val eventsForCancelled = events
            .filter { it is com.jh.proj.coroutineviz.events.CoroutineEvent && it.coroutineId == cancelledCoroutineId }
            .map { it as com.jh.proj.coroutineviz.events.CoroutineEvent }

        val maxSeqEvent = eventsForCancelled.maxByOrNull { it.seq }
            ?: error("No events found for cancelled coroutine $cancelledCoroutineId")

        assertEquals(
            "CoroutineCancelled",
            maxSeqEvent.kind,
            "The highest-seq event for the cancelled coroutine must be CoroutineCancelled, " +
                "but got ${maxSeqEvent.kind}@seq=${maxSeqEvent.seq}. " +
                "All events: ${eventsForCancelled.sortedBy { it.seq }.map { "${it.kind}@seq=${it.seq}" }}",
        )

        val jobStateChangedForCancelled = eventsForCancelled.filterIsInstance<JobStateChanged>()
        if (jobStateChangedForCancelled.isNotEmpty()) {
            val terminalSeq = maxSeqEvent.seq
            jobStateChangedForCancelled.forEach { jsc ->
                assertTrue(
                    jsc.seq < terminalSeq,
                    "JobStateChanged@seq=${jsc.seq} must have a lower seq than CoroutineCancelled@seq=$terminalSeq",
                )
            }
        }
    }

    /**
     * Test C – Validator clean: running NoEventsAfterTerminalRule over the instrumented
     * failing-coroutine event stream must produce zero findings. Any finding here means
     * the app would flag its own instrumentation.
     */
    @Test
    fun `NoEventsAfterTerminalRule produces zero findings for instrumented failing-child scenario`(): Unit = runTest {
        val session = VizSession("test-terminal-validator-clean")
        val exceptionHandler = CoroutineExceptionHandler { _, _ -> /* swallow */ }
        val isolatedScope = CoroutineScope(SupervisorJob() + exceptionHandler)
        val viz = VizScope(session, context = isolatedScope.coroutineContext)

        val job = viz.vizLaunch("parent") {
            vizLaunch("failing-child") {
                delay(10)
                throw IllegalStateException("Intentional failure for validator test")
            }.join()
        }

        try {
            job.join()
        } catch (_: Exception) {
            // expected
        }
        delay(200)

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
