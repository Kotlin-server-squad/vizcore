package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Regression tests for FIX-04: ScenarioRunner.runCancellationScenario must cancel
 * only "child-to-be-cancelled" via targeted child1.cancel(), NOT cancel the whole job.
 *
 * Previously, child1.cancel() was commented out and instead the whole job was cancelled
 * externally via `delay(1000); job.cancel()`. This meant:
 * - "child-to-be-cancelled" was not explicitly cancelled; both children ran or were cancelled together
 * - "normal-child" (3s delay) was cancelled before it could complete
 * - The demo did not illustrate targeted cancellation at all
 *
 * After FIX-04:
 * - child1.cancel() is called with a brief delay first
 * - "child-to-be-cancelled" receives CoroutineCancelled
 * - "normal-child" continues running and eventually receives CoroutineCompleted
 * - The parent job completes normally (no whole-job cancel)
 */
class CancellationScenarioRegressionTest {
    @Test
    fun `cancellation scenario cancels only child-to-be-cancelled leaving normal-child to complete (FIX-04)`() =
        runTest(timeout = 30.seconds) {
            val session = VizSession("test-fix-04")

            val job = ScenarioRunner.runCancellationScenario(session)
            job.join()

            val events = session.store.all()
            val cancelledEvents = events.filterIsInstance<CoroutineCancelled>()
            val completedEvents = events.filterIsInstance<CoroutineCompleted>()

            // "child-to-be-cancelled" must be explicitly cancelled (CoroutineCancelled)
            assertTrue(
                cancelledEvents.any { it.label == "child-to-be-cancelled" },
                "'child-to-be-cancelled' should be CoroutineCancelled, " +
                    "got: cancelled=${cancelledEvents.map { it.label }}",
            )

            // "normal-child" must complete successfully (CoroutineCompleted)
            assertTrue(
                completedEvents.any { it.label == "normal-child" },
                "'normal-child' should be CoroutineCompleted (not cancelled), " +
                    "got: completed=${completedEvents.map { it.label }}, " +
                    "cancelled=${cancelledEvents.map { it.label }}",
            )
        }

    @Test
    fun `cancellation scenario parent job completes normally (FIX-04)`() =
        runTest(timeout = 30.seconds) {
            val session = VizSession("test-fix-04-parent")

            val job = ScenarioRunner.runCancellationScenario(session)
            job.join()

            val events = session.store.all()
            val completedEvents = events.filterIsInstance<CoroutineCompleted>()
            val cancelledEvents = events.filterIsInstance<CoroutineCancelled>()

            // The parent coroutine should complete normally after child1 is individually cancelled
            assertTrue(
                completedEvents.any { it.label == "parent" },
                "Parent coroutine should be CoroutineCompleted (targeted cancel does not cancel parent), " +
                    "got: completed=${completedEvents.map { it.label }}, " +
                    "cancelled=${cancelledEvents.map { it.label }}",
            )
        }
}
