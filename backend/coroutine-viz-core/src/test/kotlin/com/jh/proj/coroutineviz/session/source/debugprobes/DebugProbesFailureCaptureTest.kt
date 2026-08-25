package com.jh.proj.coroutineviz.session.source.debugprobes

import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * GAP-EXCEPTIONS-BLIND (15-08 Task 1): the DebugProbes dump cannot distinguish
 * completed/cancelled/failed (v1 tradeoff A3), but the adapter holds each
 * coroutine's [Job] and `Job.invokeOnCompletion { cause -> }` delivers the
 * completion cause. These tests prove the Vanished branch maps that cause to
 * CoroutineFailed / CoroutineCancelled / CoroutineCompleted, and that the
 * adapter's outcome tracking is hygienic (one handler per Job, consume-once,
 * cleared by reset()).
 *
 * Real Jobs via [CompletableDeferred] (public API): completeExceptionally /
 * cancel / complete cover all three outcomes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebugProbesFailureCaptureTest {
    private val synthesizer = DebugProbesEventSynthesizer()

    private fun session() = VizSession(sessionId = "failure-capture-test")

    private val creationStack =
        listOf(StackTraceElement("com.example.App", "startWork", "App.kt", 12))

    private fun rawFor(job: Job) =
        CoroutineInfoAdapter.RawInfo(
            state = CoroState.RUNNING,
            job = job,
            context = EmptyCoroutineContext,
            creationStackTrace = creationStack,
            lastObservedStackTrace = emptyList(),
        )

    @Test
    fun `failed job maps Vanished to CoroutineFailed with exception type and message`() {
        val adapter = CoroutineInfoAdapter()
        val job = CompletableDeferred<Unit>()
        val snap = adapter.toSnapshot(rawFor(job))

        job.completeExceptionally(IllegalStateException("boom"))

        val outcome = adapter.completionOutcome(snap.key)
        assertNotNull(outcome, "failure cause must be recorded by the completion handler")
        val events = synthesizer.synthesize(CoroutineDelta.Vanished(snap), session(), outcome)

        assertEquals(1, events.size)
        val failed = events.single()
        assertTrue(failed is CoroutineFailed, "Vanished with a failure cause must map to CoroutineFailed")
        assertEquals("java.lang.IllegalStateException", failed.exceptionType)
        assertEquals("boom", failed.message)
    }

    @Test
    fun `cancelled job maps Vanished to CoroutineCancelled with the cancellation message`() {
        val adapter = CoroutineInfoAdapter()
        val job = CompletableDeferred<Unit>()
        val snap = adapter.toSnapshot(rawFor(job))

        job.cancel(CancellationException("stopped"))

        val outcome = adapter.completionOutcome(snap.key)
        assertNotNull(outcome, "cancellation must be recorded by the completion handler")
        val events = synthesizer.synthesize(CoroutineDelta.Vanished(snap), session(), outcome)

        assertEquals(1, events.size)
        val cancelled = events.single()
        assertTrue(cancelled is CoroutineCancelled, "Vanished with a CancellationException must map to CoroutineCancelled")
        assertEquals("stopped", cancelled.cause)
    }

    @Test
    fun `normally completed job maps Vanished to CoroutineCompleted (null outcome)`() {
        val adapter = CoroutineInfoAdapter()
        val job = CompletableDeferred<Unit>()
        val snap = adapter.toSnapshot(rawFor(job))

        job.complete(Unit)

        // Normal completion (cause == null) records NO entry.
        val outcome = adapter.completionOutcome(snap.key)
        assertNull(outcome, "normal completion must not record an outcome")

        val events = synthesizer.synthesize(CoroutineDelta.Vanished(snap), session(), outcome)
        assertEquals(1, events.size)
        assertTrue(events.single() is CoroutineCompleted)
    }

    @Test
    fun `default synthesize call (no outcome argument) still maps Vanished to CoroutineCompleted`() {
        val events =
            synthesizer.synthesize(
                CoroutineDelta.Vanished(CoroutineSnapshot(key = CoroKey("a"), state = CoroState.RUNNING)),
                session(),
            )
        assertEquals(1, events.size)
        assertTrue(events.single() is CoroutineCompleted)
    }

    @Test
    fun `outcomes are consume-once, one handler per job across polls, and cleared by reset`() {
        val adapter = CoroutineInfoAdapter()

        // Same Job observed across multiple polls installs exactly ONE handler;
        // the recorded outcome is consumed (removed) on first read.
        val jobA = CompletableDeferred<Unit>()
        val snapA = adapter.toSnapshot(rawFor(jobA))
        adapter.toSnapshot(rawFor(jobA)) // second poll: must NOT install a second handler
        jobA.completeExceptionally(IllegalStateException("x"))
        assertNotNull(adapter.completionOutcome(snapA.key), "outcome recorded once")
        assertNull(adapter.completionOutcome(snapA.key), "outcome consumed on first read")

        // reset() disposes all handles + clears outcomes: a Job completing AFTER
        // reset records nothing (a leaked second handler would record here).
        val jobB = CompletableDeferred<Unit>()
        val snapB = adapter.toSnapshot(rawFor(jobB))
        adapter.toSnapshot(rawFor(jobB))
        adapter.reset()
        jobB.completeExceptionally(IllegalStateException("y"))
        assertNull(adapter.completionOutcome(snapB.key), "reset() must dispose handles and clear outcomes")
    }

    @Test
    fun `source poll loop passes the recorded outcome into Vanished synthesis`() =
        runTest {
            val interval = 150.milliseconds
            val adapter = CoroutineInfoAdapter()
            val job = CompletableDeferred<Unit>()
            val session = VizSession("failure-capture-source")

            var current: List<CoroutineSnapshot> = emptyList()
            val source =
                DebugProbesSource(
                    session = session,
                    pollInterval = interval,
                    scope = backgroundScope,
                    installProbes = false,
                    adapter = adapter,
                    dump = { current },
                )

            // start() resets the adapter, so register the Job AFTER start —
            // mirroring the real flow where handlers register during dump.
            source.start()
            val snap = adapter.toSnapshot(rawFor(job))
            job.completeExceptionally(IllegalStateException("boom"))

            current = listOf(snap)
            runCurrent() // tick 1: Appeared

            current = emptyList()
            advanceTimeBy(interval)
            runCurrent() // tick 2: Vanished consumes the recorded outcome

            source.stop()

            val failed = session.store.all().filterIsInstance<CoroutineFailed>()
            assertEquals(1, failed.size, "Vanished after a failure must land CoroutineFailed in the store")
            assertEquals("java.lang.IllegalStateException", failed.single().exceptionType)
            assertEquals("boom", failed.single().message)
        }
}
