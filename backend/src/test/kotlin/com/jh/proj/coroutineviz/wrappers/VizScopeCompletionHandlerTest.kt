package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for FIX-03: VizScope completion handler must classify
 * FAILED vs CANCELLED based solely on `cause !is CancellationException`.
 *
 * Previously, the condition was:
 *   cause !is CancellationException && cause.message?.contains(ctx.label ?: "unknown") == true
 * which always evaluated to false (label not in exception message), causing the
 * invokeOnCompletion handler to fall through to the CANCELLED branch and emit
 * CoroutineCancelled even when the coroutine threw a non-cancellation exception.
 */
class VizScopeCompletionHandlerTest {
    /**
     * FIX-03 regression: a coroutine that throws a non-CancellationException must emit
     * CoroutineFailed (not CoroutineCancelled) in its invokeOnCompletion handler.
     *
     * With the broken condition, `cause.message?.contains(ctx.label ?: "unknown")` returns
     * false because the coroutine label "failing-child" is not in the exception message
     * "Intentional failure for demo". The handler then falls through to:
     *   cause is CancellationException || job.isCancelled
     * and since job.isCancelled == true for a failed job, it emits CoroutineCancelled.
     */
    @Test
    fun `failing coroutine emits CoroutineFailed not CoroutineCancelled (FIX-03)`() =
        runTest {
            val session = VizSession("test-fix-03")
            val viz = VizScope(session)

            val job =
                viz.vizLaunch("parent") {
                    vizLaunch("failing-child") {
                        vizDelay(50)
                        throw IllegalStateException("Intentional failure for regression test")
                    }.join()
                }

            // Wait for all coroutines to complete (with structured concurrency, parent
            // will be cancelled when failing-child fails, so we wait/ignore the exception)
            try {
                job.join()
            } catch (_: Exception) {
                // expected — parent is cancelled due to child failure
            }
            // Give the invokeOnCompletion handlers time to fire
            kotlinx.coroutines.delay(100)

            val events = session.store.all()

            // The failing-child coroutine must emit CoroutineFailed
            val failedEvents = events.filterIsInstance<CoroutineFailed>()
            val cancelledEvents = events.filterIsInstance<CoroutineCancelled>()

            assertTrue(
                failedEvents.any { it.label == "failing-child" },
                "Expected CoroutineFailed for 'failing-child', but found: " +
                    "failed=${failedEvents.map { it.label }}, " +
                    "cancelled=${cancelledEvents.map { it.label }}",
            )

            assertTrue(
                failedEvents.any { it.exceptionType?.contains("IllegalStateException") == true },
                "CoroutineFailed event must record the exception type, got: ${failedEvents.map { it.exceptionType }}",
            )

            // The failing-child must NOT appear in CoroutineCancelled events
            assertFalse(
                cancelledEvents.any { it.label == "failing-child" },
                "'failing-child' should emit CoroutineFailed, not CoroutineCancelled",
            )
        }

    /**
     * Complementary: a coroutine cancelled via job.cancel() must emit CoroutineCancelled.
     * This verifies the normal cancellation path still works after the FIX-03 correction.
     */
    @Test
    fun `explicitly cancelled coroutine emits CoroutineCancelled (regression guard)`() =
        runTest {
            val session = VizSession("test-fix-03-cancelled")
            val viz = VizScope(session)

            val job =
                viz.vizLaunch("cancellable-coroutine") {
                    vizDelay(10_000) // long enough to be cancelled
                }

            kotlinx.coroutines.delay(50)
            job.cancel()

            try {
                job.join()
            } catch (_: CancellationException) {
                // expected
            }
            kotlinx.coroutines.delay(100)

            val events = session.store.all()
            val cancelledEvents = events.filterIsInstance<CoroutineCancelled>()
            val failedEvents = events.filterIsInstance<CoroutineFailed>()

            assertTrue(
                cancelledEvents.any { it.label == "cancellable-coroutine" },
                "Explicitly cancelled coroutine should emit CoroutineCancelled, " +
                    "got cancelled=${cancelledEvents.map { it.label }}, failed=${failedEvents.map { it.label }}",
            )
        }
}
