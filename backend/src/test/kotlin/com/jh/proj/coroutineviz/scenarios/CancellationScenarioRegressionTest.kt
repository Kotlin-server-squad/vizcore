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
 * Regression tests for FIX-04: BasicScenarios.runCancellationScenario must cancel
 * only the "profile-screen" coroutine (the screen the user closes), NOT the whole job.
 *
 * Previously, child1.cancel() was commented out and instead the whole job was cancelled
 * externally via `delay(1000); job.cancel()`. This meant:
 * - "child-to-be-cancelled" was not explicitly cancelled; both children ran or were cancelled together
 * - "normal-child" (3s delay) was cancelled before it could complete
 * - The demo did not illustrate targeted cancellation at all
 *
 * After FIX-04 (the demo now tells it as a user closing a screen):
 * - only the screen is cancelled, after a brief delay
 * - "profile-screen" receives CoroutineCancelled
 * - "sync-settings" continues running and eventually receives CoroutineCompleted
 * - The parent job ("app-session") completes normally (no whole-job cancel)
 */
class CancellationScenarioRegressionTest {
    // The demo takes about 12 s as scripted; run it 50 times faster.
    private fun demoRun() = DemoRuns(maxConcurrent = 0, timeScale = 0.02).tryStart()!!

    @Test
    fun `cancellation scenario cancels only the closed screen, leaving settings sync to complete (FIX-04)`() =
        runTest(timeout = 30.seconds) {
            val session = VizSession("test-fix-04")

            // A demo runs only while it holds a slot; no limit here.
            val job = withContext(demoRun()) { BasicScenarios.runCancellationScenario(session) }
            job.join()

            val events = session.store.all()
            val cancelledEvents = events.filterIsInstance<CoroutineCancelled>()
            val completedEvents = events.filterIsInstance<CoroutineCompleted>()

            // The screen the user closes must be cancelled (CoroutineCancelled)
            assertTrue(
                cancelledEvents.any { it.label == "profile-screen" },
                "'profile-screen' should be CoroutineCancelled, " +
                    "got: cancelled=${cancelledEvents.map { it.label }}",
            )

            // Settings sync, outside the screen, must complete successfully (CoroutineCompleted)
            assertTrue(
                completedEvents.any { it.label == "sync-settings" },
                "'sync-settings' should be CoroutineCompleted (not cancelled), " +
                    "got: completed=${completedEvents.map { it.label }}, " +
                    "cancelled=${cancelledEvents.map { it.label }}",
            )
        }

    @Test
    fun `cancellation scenario parent job completes normally (FIX-04)`() =
        runTest(timeout = 30.seconds) {
            val session = VizSession("test-fix-04-parent")

            // A demo runs only while it holds a slot; no limit here.
            val job = withContext(demoRun()) { BasicScenarios.runCancellationScenario(session) }
            job.join()

            val events = session.store.all()
            val completedEvents = events.filterIsInstance<CoroutineCompleted>()
            val cancelledEvents = events.filterIsInstance<CoroutineCancelled>()

            // The app session should complete normally after the screen is individually cancelled
            assertTrue(
                completedEvents.any { it.label == "app-session" },
                "Parent coroutine should be CoroutineCompleted (targeted cancel does not cancel parent), " +
                    "got: completed=${completedEvents.map { it.label }}, " +
                    "cancelled=${cancelledEvents.map { it.label }}",
            )
        }
}
