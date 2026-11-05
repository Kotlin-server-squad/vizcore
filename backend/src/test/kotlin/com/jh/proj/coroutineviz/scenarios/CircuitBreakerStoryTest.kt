package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The Circuit Breaker demo's story, on virtual time. Whether order 5 still meets the open breaker,
 * and whether the provider is back for order 6, depends on a clock; on real time a slow scheduler
 * could change the story. Here the steps, the provider's recovery and the breaker's cooldown all
 * read one virtual clock, so the story is exact on every run. [PatternsDemoPacingTest] checks the
 * demo's pacing on real time.
 */
class CircuitBreakerStoryTest {
    @Test
    fun `the breaker opens after three failures, rejects orders 4 and 5 at once, then lets a trial through and closes`() =
        runTest {
            val session = VizSession("breaker-story")
            try {
                val run = assertNotNull(DemoRuns(maxConcurrent = 0).tryStart())
                val clock = { testScheduler.currentTime * 1_000_000 }
                withContext(run) { PatternScenarios.runCircuitBreaker(session, StandardTestDispatcher(testScheduler), clock) }.join()
                val scriptedMillis = testScheduler.currentTime
                val events = settled(session)
                val created = events.filterIsInstance<CoroutineCreated>()
                val labelOf = created.associate { it.coroutineId to it.label }

                fun childrenOf(label: String) = created.filter { labelOf[it.parentCoroutineId] == label }.mapNotNull { it.label }

                fun failed(label: String) = events.any { it is CoroutineFailed && it.label == label }

                fun completed(label: String) = events.any { it is CoroutineCompleted && it.label == label }

                fun steps(label: String) = events.count { it is CoroutineSuspended && it.label == label }

                assertEquals(
                    setOf("checkout") + (1..8).map { "order-$it" } + listOf(1, 2, 3, 6, 7, 8).map { "charge-$it" },
                    created.mapNotNull { it.label }.toSet(),
                    "only orders 1-3 and 6-8 call the provider",
                )
                for (n in 1..3) {
                    assertEquals(listOf("charge-$n"), childrenOf("order-$n"), "order-$n calls the provider")
                    assertTrue(failed("charge-$n") && failed("order-$n"), "order-$n fails: the provider is down")
                }
                for (n in 4..5) {
                    assertTrue(failed("order-$n"), "order-$n is rejected")
                    assertEquals(emptyList(), childrenOf("order-$n"), "a rejected order does not call the provider")
                    assertEquals(0, steps("order-$n"), "a rejected order fails at once, without waiting")
                }
                for (n in 6..8) {
                    assertEquals(listOf("charge-$n"), childrenOf("order-$n"), "order-$n calls the provider")
                    assertTrue(completed("charge-$n") && completed("order-$n"), "order-$n is paid: the provider is back")
                }
                assertTrue(completed("checkout"), "the failed orders do not stop the checkout")
                // 3 failed charges of 1.5 s, 2 rejections, 3 paid charges of 1 s, and 7 gaps of 2 s.
                assertEquals(21_500, scriptedMillis, "the story takes its scripted time")
            } finally {
                session.close()
            }
        }

    /** The session's events once every coroutine's terminal event is in; those can follow its job's end by a moment. */
    private suspend fun settled(session: VizSession): List<VizEvent> =
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                while (session.store.all().count { it.isTerminal() } < session.store.all().count { it is CoroutineCreated }) {
                    delay(5)
                }
            }
            session.store.all()
        }

    private fun VizEvent.isTerminal() = this is CoroutineCompleted || this is CoroutineCancelled || this is CoroutineFailed
}
