package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Regression tests for CR-03: VizScope's default context must carry a Job so
 * cancel()/cancelAndJoin() actually cancel running coroutines.
 *
 * Previously `coroutineContext = context + CoroutineName(...)` with default
 * `context = EmptyCoroutineContext` contained NO Job:
 * - cancel()/cancelAndJoin() did `coroutineContext[Job]?.cancel()` against a
 *   null Job — silent no-ops
 * - every root vizLaunch/vizAsync was unparented (GlobalScope-equivalent)
 * - closing a session could not stop its running scenarios
 *
 * Uses runBlocking with real delays (not runTest/virtual time) per WR-15:
 * vizLaunch dispatches on the session scope's Dispatchers.Default, so virtual
 * time in the test scope would not advance the launched coroutine.
 */
class VizScopeCancellationTest {
    @Test
    fun `VizScope default context has a Job`() {
        val viz = VizScope(VizSession("ctx"))
        assertNotNull(
            viz.coroutineContext[Job],
            "VizScope built with the default context must carry a Job — " +
                "without one, cancellation is a silent no-op and root coroutines are unparented",
        )
    }

    @Test
    fun `cancelAndJoin stops a running coroutine`(): Unit = runBlocking {
        val session = VizSession("test-cancel")
        val viz = VizScope(session)
        val reachedCompletion = AtomicBoolean(false)

        viz.vizLaunch("long-runner") {
            vizDelay(50)
            // Still running here; would only set the flag after the long delay completes
            vizDelay(10_000)
            reachedCompletion.set(true)
        }

        // Give the coroutine real time to start running
        delay(100)

        // Bounded by withTimeout: with a no-op Job, cancelAndJoin would either
        // return without stopping the coroutine or never join — the timeout
        // converts a non-functional Job into a test failure instead of a hang.
        withTimeout(2_000) {
            viz.cancelAndJoin()
        }

        assertFalse(
            reachedCompletion.get(),
            "Coroutine completed normally despite cancelAndJoin() — the VizScope Job is not functional",
        )
    }
}
