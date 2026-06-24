package com.jh.proj.coroutineviz.session.source.debugprobes

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.session.EventSampler
import com.jh.proj.coroutineviz.session.EventStore
import com.jh.proj.coroutineviz.session.EventStoreInterface
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

    @Test
    fun `stop deregisters the close listener so it does not leak into SessionManager (CR-02)`() =
        runTest {
            assertEquals(0, SessionManager.onSessionClosedListenerCount(), "registry starts clean")

            val session = VizSession("dp-leak")
            val source =
                DebugProbesSource(
                    session = session,
                    pollInterval = interval,
                    scope = backgroundScope,
                    installProbes = false,
                    dump = { listOf(snap("a", CoroState.RUNNING)) },
                )

            source.start()
            assertEquals(1, SessionManager.onSessionClosedListenerCount(), "start registers exactly one close hook")

            source.stop()
            assertEquals(
                0,
                SessionManager.onSessionClosedListenerCount(),
                "stop() MUST deregister its close hook — otherwise the lambda (and the source + " +
                    "VizSession/EventStore it captures) leaks into the singleton registry forever (CR-02)",
            )
        }

    @Test
    fun `many start-stop cycles do not accumulate close listeners (CR-02 leak)`() =
        runTest {
            val session = VizSession("dp-leak-cycles")
            val source =
                DebugProbesSource(
                    session = session,
                    pollInterval = interval,
                    scope = backgroundScope,
                    installProbes = false,
                    dump = { listOf(snap("a", CoroState.RUNNING)) },
                )

            repeat(5) {
                source.start()
                source.stop()
            }

            assertEquals(
                0,
                SessionManager.onSessionClosedListenerCount(),
                "repeated start/stop must not accrete listeners",
            )
        }

    @Test
    fun `after a stop-start restart the close hook is re-registered and still tears down on close (CR-02)`() =
        runTest {
            val session = SessionManager.createSession("dp-restart-close")
            val source =
                DebugProbesSource(
                    session = session,
                    pollInterval = interval,
                    scope = backgroundScope,
                    installProbes = false,
                    dump = { listOf(snap("a", CoroState.RUNNING)) },
                )

            source.start()
            source.stop()
            assertEquals(0, SessionManager.onSessionClosedListenerCount(), "stop deregisters")

            // Restart: the close hook MUST be re-registered (closeListenerRegistered
            // is reset on stop). Before the CR-02 fix, the flag was never reset so a
            // restarted loop was never torn down on session close.
            source.start()
            runCurrent()
            assertTrue(source.isRunning)
            assertEquals(1, SessionManager.onSessionClosedListenerCount(), "restart re-registers the hook")

            SessionManager.deleteSession(session.sessionId)
            assertFalse(source.isRunning, "restarted source must still stop on session close")
        }

    @Test
    fun `a mid-batch send failure does not replay the already-delivered prefix on the next tick (WR-02)`() =
        runTest {
            // The diff yields Appeared(a) then Appeared(b) (next-iteration order).
            // We inject a store that THROWS while recording b's events on the first
            // tick — simulating a send failure mid-batch after a was delivered.
            val failOnB = java.util.concurrent.atomic.AtomicBoolean(true)
            val session =
                VizSession(
                    sessionId = "dp-wr02",
                    eventStoreFactory = {
                        FailingStore(EventStore(100_000)) { event ->
                            failOnB.get() && (event as? CoroutineEvent)?.coroutineId == "dp-b"
                        }
                    },
                )
            val source =
                DebugProbesSource(
                    session = session,
                    pollInterval = interval,
                    scope = backgroundScope,
                    installProbes = false,
                    dump = { listOf(snap("a", CoroState.RUNNING), snap("b", CoroState.RUNNING)) },
                )

            source.start()
            runCurrent() // tick1: a recorded; b's first event throws → tick caught, prev advanced past a only.
            assertTrue(source.isRunning, "loop survives the mid-batch failure")
            val afterTick1 = session.store.all().map { (it as CoroutineEvent).coroutineId to it.kind }
            assertEquals(2, afterTick1.count { it.first == "dp-a" }, "a's Created+Started delivered")
            assertEquals(0, afterTick1.count { it.first == "dp-b" }, "b never delivered (threw)")

            // Stop failing; next tick must recover ONLY b, never replay a.
            failOnB.set(false)
            advanceTimeBy(interval)
            runCurrent()

            val all = session.store.all().map { (it as CoroutineEvent).coroutineId to it.kind }
            assertEquals(
                1,
                all.count { it == ("dp-a" to "CoroutineCreated") },
                "a must NOT be re-Appeared — its delta was committed despite b's failure (WR-02)",
            )
            assertEquals(
                1,
                all.count { it == ("dp-b" to "CoroutineCreated") },
                "b recovers exactly once on the next successful tick",
            )

            source.stop()
        }

    /**
     * Delegating store that throws on [record] for events matching [failWhen],
     * else forwards to [delegate]. Used to simulate a mid-batch send failure
     * (WR-02) without depending on EventStore internals.
     */
    private class FailingStore(
        private val delegate: EventStore,
        private val failWhen: (VizEvent) -> Boolean,
    ) : EventStoreInterface {
        override fun record(event: VizEvent) {
            if (failWhen(event)) error("simulated record failure")
            delegate.record(event)
        }

        override fun all() = delegate.all()

        override fun since(seq: Long) = delegate.since(seq)

        override fun byCoroutine(coroutineId: String) = delegate.byCoroutine(coroutineId)

        override fun count() = delegate.count()

        override fun clear() = delegate.clear()
    }
}
