package com.jh.proj.coroutineviz.scenarios

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The Circuit Breaker demo's breaker, on a clock the test moves by hand, so its decisions do not
 * depend on how fast the threads run: [PatternsDemoPacingTest] checks the same story at real (scaled) speed.
 */
class CircuitBreakerTest {
    private var now = 0L
    private val breaker = CircuitBreaker(failureThreshold = 3, openNanos = 5_000, clock = { now })
    private var calls = 0

    private fun failing(): Nothing {
        calls++
        error("HTTP 503")
    }

    private fun working(): String {
        calls++
        return "paid"
    }

    private fun failThreeTimes() =
        runBlocking {
            repeat(3) { assertFailsWith<IllegalStateException> { breaker.call { failing() } } }
        }

    @Test
    fun `it lets calls through until three fail in a row`() =
        runBlocking {
            assertFailsWith<IllegalStateException> { breaker.call { failing() } }
            assertFailsWith<IllegalStateException> { breaker.call { failing() } }
            assertEquals("paid", breaker.call { working() }, "a success resets the count")
            failThreeTimes()

            assertEquals(6, calls, "every call so far reached the provider")
        }

    @Test
    fun `once open, it rejects calls at once without making them`() =
        runBlocking {
            failThreeTimes()
            now += 4_999

            assertFailsWith<CircuitOpen> { breaker.call { working() } }
            assertEquals(3, calls, "a rejected call never reaches the provider")
        }

    @Test
    fun `after the open time, one trial goes through, and a success closes it`() =
        runBlocking {
            failThreeTimes()
            now += 5_000

            assertEquals("paid", breaker.call { working() }, "the trial")
            assertEquals("paid", breaker.call { working() }, "closed again")
            assertFailsWith<IllegalStateException> { breaker.call { failing() } }
            assertEquals("paid", breaker.call { working() }, "one failure does not open a closed breaker")
        }

    @Test
    fun `a failed trial opens it again at once`() =
        runBlocking {
            failThreeTimes()
            now += 5_000
            assertFailsWith<IllegalStateException> { breaker.call { failing() } }
            now += 4_999

            assertFailsWith<CircuitOpen> { breaker.call { working() } }
            now += 1
            assertEquals("paid", breaker.call { working() })
        }

    @Test
    fun `a cancelled call is not a failure`() =
        runBlocking {
            repeat(5) { assertFailsWith<CancellationException> { breaker.call { throw CancellationException("screen closed") } } }

            assertEquals("paid", breaker.call { working() })
        }
}
