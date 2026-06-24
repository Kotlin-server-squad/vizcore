package com.jh.proj.coroutineviz.session.source.debugprobes

import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.session.EventSampler
import com.jh.proj.coroutineviz.session.SessionManager
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Deterministic poll-loop tests driven by `runTest` virtual time + an injected
 * SCRIPTED `dump: () -> List<CoroutineSnapshot>`. No real DebugProbes, no real
 * coroutines, no real timing (Research §"Validation Architecture").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebugProbesSourceTest {
    @BeforeEach
    fun resetInstall() {
        DebugProbesInstall.resetForTest()
        SessionManager.clearSessionListeners()
    }

    @AfterEach
    fun cleanup() {
        DebugProbesInstall.resetForTest()
        SessionManager.clearSessionListeners()
    }

    private fun snap(
        token: String,
        state: CoroState,
        reason: String? = null,
        function: String? = null,
    ) = CoroutineSnapshot(
        key = CoroKey(token),
        state = state,
        function = function,
        reason = reason,
    )

    private val interval = 150.milliseconds

    @Test
    fun `scripted appear-suspend-vanish produces Created+Started, Suspended, Completed`() =
        runTest {
            val session = VizSession("s1")
            val dumps =
                listOf(
                    listOf(snap("a", CoroState.RUNNING)),
                    listOf(snap("a", CoroState.SUSPENDED, reason = "delay", function = "fetch")),
                    emptyList(),
                )
            var i = 0
            val source =
                DebugProbesSource(
                    session = session,
                    pollInterval = interval,
                    scope = backgroundScope,
                    installProbes = false,
                    dump = { dumps[i.coerceAtMost(dumps.size - 1)].also { i++ } },
                )

            source.start()
            runCurrent() // tick 1
            assertEquals(
                listOf("CoroutineCreated", "CoroutineStarted"),
                session.store.all().map { it.kind },
            )

            advanceTimeBy(interval) // tick 2
            runCurrent()
            assertTrue(session.store.all().any { it is CoroutineSuspended })

            advanceTimeBy(interval) // tick 3
            runCurrent()
            assertTrue(session.store.all().any { it is CoroutineCompleted })

            source.stop()
        }

    @Test
    fun `unchanged coroutine across polls produces no duplicate Created or Completed`() =
        runTest {
            val session = VizSession("s2")
            val source =
                DebugProbesSource(
                    session = session,
                    pollInterval = interval,
                    scope = backgroundScope,
                    installProbes = false,
                    dump = { listOf(snap("a", CoroState.RUNNING)) },
                )

            source.start()
            repeat(5) {
                runCurrent()
                advanceTimeBy(interval)
            }
            source.stop()

            val kinds = session.store.all().map { it.kind }
            assertEquals(1, kinds.count { it == "CoroutineCreated" })
            assertEquals(1, kinds.count { it == "CoroutineStarted" })
            assertEquals(0, kinds.count { it == "CoroutineCompleted" })
        }

    @Test
    fun `start is idempotent and stop is restartable`() =
        runTest {
            val session = VizSession("s3")
            val source =
                DebugProbesSource(
                    session = session,
                    pollInterval = interval,
                    scope = backgroundScope,
                    installProbes = false,
                    dump = { listOf(snap("a", CoroState.RUNNING)) },
                )

            source.start()
            source.start() // second start is a no-op
            assertTrue(source.isRunning)
            runCurrent()

            source.stop()
            assertFalse(source.isRunning)

            source.start() // restart
            assertTrue(source.isRunning)
            source.stop()
        }

    @Test
    fun `sampler gates non-lifecycle events but lifecycle always passes`() =
        runTest {
            val session = VizSession("s4")
            // Drop ALL non-lifecycle (suspend/resume) events.
            val sampler = EventSampler(defaultRate = 0.0)
            val dumps =
                listOf(
                    listOf(snap("a", CoroState.RUNNING)),
                    listOf(snap("a", CoroState.SUSPENDED, reason = "delay", function = "f")),
                    listOf(snap("a", CoroState.RUNNING)),
                )
            var i = 0
            val source =
                DebugProbesSource(
                    session = session,
                    pollInterval = interval,
                    scope = backgroundScope,
                    sampler = sampler,
                    installProbes = false,
                    dump = { dumps[i.coerceAtMost(dumps.size - 1)].also { i++ } },
                )

            source.start()
            runCurrent()
            advanceTimeBy(interval)
            runCurrent()
            advanceTimeBy(interval)
            runCurrent()
            source.stop()

            val kinds = session.store.all().map { it.kind }
            // Lifecycle (Created/Started) kept; suspend/resume dropped by the 0.0 sampler.
            assertTrue(kinds.contains("CoroutineCreated"))
            assertTrue(kinds.contains("CoroutineStarted"))
            assertEquals(0, kinds.count { it == "CoroutineSuspended" })
            assertEquals(0, kinds.count { it == "CoroutineResumed" })
        }

    @Test
    fun `a throwing tick is caught and the loop survives and recovers on the next tick`() =
        runTest {
            val session = VizSession("s5")
            // tick1: ok (appear RUNNING); tick2: throws; tick3: ok (now SUSPENDED).
            var i = 0
            val source =
                DebugProbesSource(
                    session = session,
                    pollInterval = interval,
                    scope = backgroundScope,
                    installProbes = false,
                    dump = {
                        when (i++) {
                            0 -> listOf(snap("a", CoroState.RUNNING))
                            1 -> error("boom on tick 2")
                            else -> listOf(snap("a", CoroState.SUSPENDED, reason = "delay", function = "f"))
                        }
                    },
                )

            source.start()
            runCurrent() // tick1 ok
            assertTrue(session.store.all().any { it is CoroutineCreated })

            advanceTimeBy(interval) // tick2 throws
            runCurrent()
            assertTrue(source.isRunning, "loop must survive a throwing tick")

            advanceTimeBy(interval) // tick3 recovers: prev not advanced on failure
            runCurrent()
            assertTrue(
                session.store.all().any { it is CoroutineSuspended },
                "missed RUNNING->SUSPENDED delta recovers because prev was not advanced on the failed tick",
            )

            source.stop()
        }

    @Test
    fun `resume transition emits CoroutineResumed`() =
        runTest {
            val session = VizSession("s6")
            val dumps =
                listOf(
                    listOf(snap("a", CoroState.SUSPENDED, reason = "delay", function = "f")),
                    listOf(snap("a", CoroState.RUNNING)),
                )
            var i = 0
            val source =
                DebugProbesSource(
                    session = session,
                    pollInterval = interval,
                    scope = backgroundScope,
                    installProbes = false,
                    dump = { dumps[i.coerceAtMost(dumps.size - 1)].also { i++ } },
                )

            source.start()
            runCurrent()
            advanceTimeBy(interval)
            runCurrent()
            source.stop()

            assertTrue(session.store.all().any { it is CoroutineResumed })
        }

    @Test
    fun `closing the bound session via SessionManager invokes stop`() =
        runTest {
            val session = SessionManager.createSession("dp-close")
            val source =
                DebugProbesSource(
                    session = session,
                    pollInterval = interval,
                    scope = backgroundScope,
                    installProbes = false,
                    dump = { listOf(snap("a", CoroState.RUNNING)) },
                )

            source.start()
            runCurrent()
            assertTrue(source.isRunning)

            // Deleting the session fires onSessionClosed listeners → source.stop().
            SessionManager.deleteSession(session.sessionId)
            assertFalse(source.isRunning, "stop() must run when the bound session is closed")
        }
}
