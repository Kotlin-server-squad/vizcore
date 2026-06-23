package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
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
    fun `failing coroutine emits CoroutineFailed not CoroutineCancelled (FIX-03)`(): Unit =
        runBlocking {
            val session = VizSession("test-fix-03")

            // Use a standalone scope with SupervisorJob + CoroutineExceptionHandler so that
            // the deliberate IllegalStateException thrown by 'failing-child' does not propagate
            // to the test runner and fail the test before we can assert on events.
            // swallow the expected exception
            val exceptionHandler = CoroutineExceptionHandler { _, _ -> }
            val isolatedScope = CoroutineScope(SupervisorJob() + exceptionHandler)
            val viz = VizScope(session, context = isolatedScope.coroutineContext)

            val job =
                viz.vizLaunch("parent") {
                    vizLaunch("failing-child") {
                        delay(50)
                        throw IllegalStateException("Intentional failure for regression test")
                    }.join()
                }

            // Wait for all coroutines to complete; the parent is cancelled via structured concurrency
            try {
                job.join()
            } catch (_: Exception) {
                // expected — parent is cancelled due to failing-child
            }
            // Give the invokeOnCompletion handlers time to fire
            delay(100)

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
