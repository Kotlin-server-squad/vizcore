package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.WaitKind
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The Sync: Mutex demos are paced so a person can follow them, as the Channels and Patterns are
 * ([PatternsDemoPacingTest]): each runs 10-30 s in steps of 0.5-3 s, its coroutines start, wait and
 * end at different moments, and they are named after the story the demo tells. Before, each ended
 * within one to four seconds.
 *
 * The demos run here on real time at [SCALE] of their scripted speed (`scenarios.timeScale`); every
 * time below is scaled back up to scripted time. Who waits for a lock and in which order depends
 * on when each coroutine asks, so [MutexStoriesTest] checks the exact stories on virtual time.
 * Here each demo gets only outcomes that hold in any order.
 */
class MutexDemoPacingTest {
    private class Run(
        val events: List<VizEvent>,
    ) {
        private val start = events.minOf { it.tsNanos }

        val labels: Set<String> = events.filterIsInstance<CoroutineCreated>().mapNotNull { it.label }.toSet()

        /** Scripted milliseconds from the demo's first event to [event]. */
        fun at(event: VizEvent): Double = (event.tsNanos - start) / 1_000_000.0 / SCALE

        val durationMillis: Double get() = events.maxOf { at(it) }

        fun completed(label: String) = events.any { it is CoroutineCompleted && it.label == label }

        fun cancelled(label: String) = events.any { it is CoroutineCancelled && it.label == label }

        /** Every suspension recorded as a wait for a mutex. */
        val lockWaits: List<CoroutineSuspended>
            get() = events.filterIsInstance<CoroutineSuspended>().filter { it.waitKind == WaitKind.MUTEX }

        fun resumedAfter(wait: CoroutineSuspended) =
            events.any { it is CoroutineResumed && it.coroutineId == wait.coroutineId && it.seq > wait.seq }
    }

    private suspend fun start(
        id: String,
        session: VizSession,
    ): Job =
        when (id) {
            "sync/mutex/counter" -> SyncScenarios.runCounter(session) { counted[id] = it }
            "sync/mutex/bank-transfer" -> SyncScenarios.runBankTransfer(session) { anna, ben -> balances[id] = anna to ben }
            "sync/mutex/cache" -> SyncScenarios.runCache(session)
            "sync/mutex/deadlock-demo" -> SyncScenarios.runDeadlock(session)
            else -> error("not a Sync: Mutex demo: $id")
        }

    /** Each demo runs once; the tests below read different things from the same run. */
    private fun run(id: String): Run = runs.getOrPut(id) { execute(id) }

    private fun execute(id: String): Run =
        runBlocking {
            val session = VizSession("pacing-${id.replace('/', '-')}")
            try {
                val run = assertNotNull(DemoRuns(maxConcurrent = 0, timeScale = SCALE).tryStart())
                withTimeout(20.seconds) {
                    withContext(run) { start(id, session) }.join()
                    // A parent's terminal event can follow its job's completion by a moment.
                    while (session.store.all().count { it.isTerminal() } < session.store.all().count { it is CoroutineCreated }) {
                        delay(5)
                    }
                }
                Run(session.store.all().sortedBy { it.seq })
            } finally {
                session.close()
            }
        }

    private fun VizEvent.isTerminal() = this is CoroutineCompleted || this is CoroutineCancelled || this is CoroutineFailed

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutexIds")
    fun `each Sync Mutex demo runs for 10 to 30 seconds`(id: String) {
        val run = run(id)

        assertTrue(run.durationMillis in 10_000.0..30_000.0, "$id runs for ${run.durationMillis.roundToLong()} ms")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutexIds")
    fun `each step of a Sync Mutex demo lasts 0,5 to 3 seconds`(id: String) {
        val steps =
            run(id)
                .events
                .filterIsInstance<CoroutineSuspended>()
                .filter { it.reason == "delay" }
                .map { "${it.label}" to (assertNotNull(it.durationMillis) / SCALE).roundToLong() }

        assertTrue(steps.size >= 5, "$id has only ${steps.size} steps")
        assertEquals(emptyList(), steps.filterNot { (_, millis) -> millis in 500..3_000 }, "$id steps outside 0.5-3 s")
    }

    /**
     * Events less than 0.2 s of scripted time apart belong to one moment. A demo the user can
     * follow has many such moments, and no single burst carries most of what it shows.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("mutexIds")
    fun `a Sync Mutex demo shows its work at many separate moments`(id: String) {
        val run = run(id)
        val times = run.events.map { run.at(it) }.sorted()
        val moments =
            times.fold(mutableListOf<MutableList<Double>>()) { moments, t ->
                if (moments.isEmpty() || t - moments.last().last() >= 200.0) moments += mutableListOf(t) else moments.last() += t
                moments
            }

        assertTrue(moments.size >= 8, "$id shows its events at only ${moments.size} moments")
        val largest = moments.maxOf { it.size }
        assertTrue(largest <= times.size / 2, "$id shows $largest of its ${times.size} events at one moment")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutexIds")
    fun `the coroutines of a Sync Mutex demo are named after its story`(id: String) {
        assertEquals(storyLabels.getValue(id), run(id).labels)
    }

    @Test
    fun `the gates count every visitor, and a gate that finds the lock taken is suspended until it gets it`() {
        val run = run("sync/mutex/counter")

        assertEquals(9, counted["sync/mutex/counter"], "no visitor is lost")
        assertTrue(run.lockWaits.isNotEmpty(), "a gate waits for the lock")
        run.lockWaits.forEach { assertTrue(run.resumedAfter(it), "${it.label} resumes once it has the lock") }
        assertTrue(run.labels.all { run.completed(it) })
    }

    @Test
    fun `the transfers keep the household's money, and the holiday is declined`() {
        val run = run("sync/mutex/bank-transfer")

        assertEquals(530 to 970, balances["sync/mutex/bank-transfer"], "in any order: Ben never has 1200 for the holiday")
        assertTrue(run.lockWaits.isNotEmpty(), "a transfer waits for an account")
        run.lockWaits.forEach { assertTrue(run.resumedAfter(it), "${it.label} resumes once it has the account") }
        assertTrue(run.labels.all { run.completed(it) })
    }

    @Test
    fun `each price is loaded from the database once`() {
        val run = run("sync/mutex/cache")
        val loads = run.events.filterIsInstance<CoroutineCreated>().mapNotNull { it.label }.filter { it.startsWith("load-") }

        assertEquals(listOf("load-espresso", "load-grinder"), loads.sorted(), "one load per product, however many requests miss")
        assertTrue(run.labels.all { run.completed(it) })
    }

    @Test
    fun `the deadlocked transfers stay suspended until they are cancelled, and never resume`() {
        val run = run("sync/mutex/deadlock-demo")

        assertEquals(setOf("anna-pays-ben", "ben-pays-anna"), run.lockWaits.mapNotNull { it.label }.toSet())
        for (wait in run.lockWaits) {
            assertTrue(!run.resumedAfter(wait), "${wait.label} never gets the second account")
            assertTrue(run.cancelled(wait.label!!), "${wait.label} is cancelled while it waits")
        }
        assertTrue(run.completed("watchdog") && run.completed("month-end"), "the watchdog ends the deadlock")
        assertTrue(run.events.filterIsInstance<CoroutineEvent>().none { it is CoroutineFailed }, "nothing fails")
    }

    companion object {
        /** Well below 1 so the four demos take seconds here, well above 0 so their moments stay apart. */
        private const val SCALE = 0.1

        private val runs = ConcurrentHashMap<String, Run>()
        private val counted = ConcurrentHashMap<String, Int>()
        private val balances = ConcurrentHashMap<String, Pair<Int, Int>>()

        private val storyLabels =
            mapOf(
                "sync/mutex/counter" to setOf("count-visitors", "gate-1", "gate-2", "gate-3"),
                "sync/mutex/bank-transfer" to setOf("transfers", "pay-rent", "pay-back-dinner", "split-groceries", "book-holiday"),
                "sync/mutex/cache" to setOf("shop-server", "load-espresso", "load-grinder") + (1..8).map { "request-$it" },
                "sync/mutex/deadlock-demo" to setOf("month-end", "anna-pays-ben", "ben-pays-anna", "watchdog"),
            )

        @JvmStatic
        fun mutexIds(): List<String> = storyLabels.keys.toList()
    }
}
