package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A demo run holds one of MAX_CONCURRENT_DEMOS slots for as long as its coroutines run, not
 * only while its request is handled: several scenarios answer at once and keep running on the
 * session's scope, where a client disconnect does not stop them either.
 */
class DemoRunsTest {
    private suspend fun DemoRuns.awaitActive(expected: Int) =
        withTimeout(5.seconds) {
            while (active != expected) delay(10)
        }

    @Test
    fun `a run is refused while every slot is taken, and accepted again once one is free`() {
        val runs = DemoRuns(maxConcurrent = 2)
        val first = assertNotNull(runs.tryStart())
        assertNotNull(runs.tryStart())

        assertNull(runs.tryStart(), "both slots are taken")

        first.handlerEnded()
        assertNotNull(runs.tryStart(), "the first run started no coroutines, so its slot is free")
    }

    @Test
    fun `a run keeps its slot until the coroutines it launched have ended`() =
        runBlocking {
            val runs = DemoRuns(maxConcurrent = 1)
            val session = VizSession("demo-runs-background")
            val finish = CompletableDeferred<Unit>()
            val run = assertNotNull(runs.tryStart())

            withContext(run) {
                session.demoScope().vizLaunch("background") { finish.await() }
            }
            run.handlerEnded()

            assertEquals(1, runs.active, "the response is sent, but the demo is still running")
            assertNull(runs.tryStart())

            finish.complete(Unit)
            runs.awaitActive(0)
            session.close()
        }

    @Test
    fun `a run's slot is freed when its coroutines are cancelled`() =
        runBlocking {
            val runs = DemoRuns(maxConcurrent = 1)
            val session = VizSession("demo-runs-cancelled")
            val run = assertNotNull(runs.tryStart())

            withContext(run) {
                session.demoScope().vizLaunch("forever") { CompletableDeferred<Unit>().await() }
            }
            run.handlerEnded()
            assertEquals(1, runs.active)

            // Deleting the session cancels everything still running in it.
            session.close()
            runs.awaitActive(0)
        }

    @Test
    fun `a slot is freed once, however often the run is reported ended`() {
        val runs = DemoRuns(maxConcurrent = 2)
        val run = assertNotNull(runs.tryStart())
        assertNotNull(runs.tryStart())

        run.handlerEnded()
        run.handlerEnded()

        assertEquals(1, runs.active)
    }

    @Test
    fun `zero or a negative limit means no limit`() {
        listOf(0, -1).forEach { limit ->
            val runs = DemoRuns(maxConcurrent = limit)
            repeat(50) { assertNotNull(runs.tryStart(), "limit $limit must not refuse a run") }
        }
    }

    @Test
    fun `a demo scope can only be made inside a run, so no demo escapes the limit`() =
        runBlocking {
            val session = VizSession("demo-runs-outside")
            val failure = assertFailsWith<IllegalStateException> { session.demoScope() }
            assert(failure.message.orEmpty().contains("MAX_CONCURRENT_DEMOS")) { "got ${failure.message}" }
            session.close()
        }

    @Test
    fun `a run past its time limit has its coroutines cancelled and frees its slot`() =
        runBlocking {
            val runs = DemoRuns(maxConcurrent = 1, maxRunMillis = 200)
            val session = VizSession("demo-runs-deadline")
            val run = assertNotNull(runs.tryStart())

            // A demo that would never end on its own, like a deadlock or a huge custom delay.
            val job =
                withContext(run) {
                    session.demoScope().vizLaunch("stuck") { CompletableDeferred<Unit>().await() }
                }
            run.handlerEnded()
            assertEquals(1, runs.active)

            runs.awaitActive(0)
            assertTrue(job.isCancelled, "the time limit must stop the demo's coroutines, not only free the slot")
            session.close()
        }

    @Test
    fun `the time limit also stops a demo whose request is still waiting for it`() =
        runBlocking {
            val runs = DemoRuns(maxConcurrent = 1, maxRunMillis = 200)
            val session = VizSession("demo-runs-deadline-in-request")
            val run = assertNotNull(runs.tryStart())

            withTimeout(5.seconds) {
                withContext(run) {
                    // Several demos join their coroutines before they answer.
                    session.demoScope().vizLaunch("stuck") { CompletableDeferred<Unit>().await() }.join()
                }
            }
            run.handlerEnded()

            runs.awaitActive(0)
            session.close()
        }

    @Test
    fun `a run whose request has ended cannot start more work`() =
        runBlocking {
            val runs = DemoRuns(maxConcurrent = 1)
            val session = VizSession("demo-runs-late")
            val run = assertNotNull(runs.tryStart())
            run.handlerEnded()

            assertFailsWith<IllegalStateException> {
                withContext(run) { session.demoScope().vizLaunch("late") { delay(10_000) } }
            }
            assertEquals(0, runs.active)
            session.close()
        }
}
