package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.checksystem.LifecycleValidator
import com.jh.proj.coroutineviz.checksystem.ValidationResult
import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression test for wr-10: a coroutine cancelled before its body is ever dispatched
 * must still produce a well-formed Created -> Started -> terminal lifecycle.
 *
 * Previously vizLaunch emitted CoroutineStarted from INSIDE the coroutine body, while
 * CoroutineCreated was emitted synchronously at launch. So a cancel-before-start produced
 * the stream [CoroutineCreated, JobStateChanged, CoroutineCancelled] with NO CoroutineStarted,
 * which trips LifecycleValidator's CreatedHasStarted rule ("created but never started") —
 * the instrumentation flagging its own output.
 *
 * Determinism: the body's initial continuation is queued on a paused StandardTestDispatcher
 * that is never advanced before cancellation. After job.cancel(), advancing the scheduler runs
 * the (already-cancelled) initial continuation, which resumes with CancellationException WITHOUT
 * executing the body (resumeCancellableWith short-circuits) — modelling a true cancel-before-start.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VizScopeCancelBeforeStartTest {
    @Test
    fun `cancel-before-start still emits a Created-Started pair (wr-10)`(): Unit =
        runBlocking {
            val session = VizSession("test-cancel-before-start")
            val viz = VizScope(session)
            val scheduler = TestCoroutineScheduler()
            val pausedDispatcher = StandardTestDispatcher(scheduler)
            val bodyRan = AtomicBoolean(false)

            // The body's initial dispatch is queued on the paused dispatcher (not yet run).
            val job =
                viz.vizLaunch("never-starts", context = pausedDispatcher) {
                    bodyRan.set(true)
                }

            // Cancel before the body's initial continuation is processed.
            job.cancel()
            // Let the now-cancelled initial continuation run: it resumes with CancellationException
            // WITHOUT executing the body, completing the coroutine as cancelled.
            scheduler.advanceUntilIdle()
            job.join()

            assertFalse(
                bodyRan.get(),
                "Test invariant: the body must not execute in a cancel-before-start scenario " +
                    "(otherwise this does not exercise wr-10).",
            )

            val events = session.store.all()
            val ours =
                events
                    .filterIsInstance<CoroutineEvent>()
                    .filter { it.label == "never-starts" }

            assertTrue(
                ours.any { it is CoroutineCreated },
                "Expected a CoroutineCreated for the cancelled coroutine. " +
                    "Events: ${ours.sortedBy { it.seq }.map { "${it.kind}@${it.seq}" }}",
            )
            assertTrue(
                ours.any { it is CoroutineStarted },
                "wr-10: a cancel-before-start coroutine must still emit CoroutineStarted so the " +
                    "Created/Started pair is well-formed. " +
                    "Events: ${ours.sortedBy { it.seq }.map { "${it.kind}@${it.seq}" }}",
            )

            val createdHasStartedFail =
                LifecycleValidator
                    .validate(events)
                    .filterIsInstance<ValidationResult.Fail>()
                    .firstOrNull { it.ruleName == "CreatedHasStarted" }
            assertNull(
                createdHasStartedFail,
                "LifecycleValidator must not report a CreatedHasStarted failure for the " +
                    "instrumentation's own cancel-before-start output: ${createdHasStartedFail?.message}",
            )
        }
}
