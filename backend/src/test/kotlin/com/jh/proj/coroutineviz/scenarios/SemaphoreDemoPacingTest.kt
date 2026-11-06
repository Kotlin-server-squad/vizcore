package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.events.SemaphoreCreated
import com.jh.proj.coroutineviz.events.SemaphorePermitAcquired
import com.jh.proj.coroutineviz.events.SemaphorePermitReleased
import com.jh.proj.coroutineviz.events.SemaphoreStateChanged
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.WaitKind
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToLong
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The Sync: Semaphore and Combined demos are paced so a person can follow them, as the Mutex demos are
 * ([MutexDemoPacingTest]): each runs 10-30 s in steps of 0.5-3 s, its coroutines start, wait and end at different
 * moments, and they are named after the story the demo tells. Before, each ended within about a second.
 *
 * The demos run here on real time at [SCALE] of their scripted speed (`scenarios.timeScale`); every time below is
 * scaled back up to scripted time. Who waits for a permit and who gives up depends on when each coroutine asks, so
 * what must be exact runs on virtual time: the length and moments of each demo here, the stories in
 * [SemaphoreStoriesTest]. On real time each demo gets only what holds however the threads run: at least 10 s, its
 * steps, its names, and outcomes that hold in any order.
 */
class SemaphoreDemoPacingTest {
    private class Run(
        val events: List<VizEvent>,
    ) {
        private val start = events.minOf { it.tsNanos }

        val labels: Set<String> = events.filterIsInstance<CoroutineCreated>().mapNotNull { it.label }.toSet()

        /** Scripted milliseconds from the demo's first event to [event]. */
        fun at(event: VizEvent): Double = (event.tsNanos - start) / 1_000_000.0 / SCALE

        val durationMillis: Double get() = events.maxOf { at(it) }

        fun completed(label: String) = events.any { it is CoroutineCompleted && it.label == label }

        /** Every suspension recorded as a wait for a semaphore permit. */
        val permitWaits: List<CoroutineSuspended>
            get() = events.filterIsInstance<CoroutineSuspended>().filter { it.waitKind == WaitKind.SEMAPHORE }

        fun resumedAfter(wait: CoroutineSuspended) =
            events.any { it is CoroutineResumed && it.coroutineId == wait.coroutineId && it.seq > wait.seq }

        /** The most permits of [semaphore] held at once, replaying acquires and releases in seq order. */
        fun mostHeldAtOnce(semaphore: String): Int {
            val id = events.filterIsInstance<SemaphoreCreated>().single { it.semaphoreLabel == semaphore }.semaphoreId
            var held = 0
            var most = 0
            for (event in events) {
                if (event is SemaphorePermitAcquired && event.semaphoreId == id) most = maxOf(most, ++held)
                if (event is SemaphorePermitReleased && event.semaphoreId == id) held--
            }
            return most
        }

        fun allCompleted() = labels.all { completed(it) } && events.none { it is CoroutineCancelled || it is CoroutineFailed }
    }

    private suspend fun start(
        id: String,
        session: VizSession,
    ): Job =
        when (id) {
            "sync/semaphore/connection-pool" -> SyncScenarios.runConnectionPool(session)
            "sync/semaphore/rate-limiter" -> SyncScenarios.runRateLimiter(session) { forecasts.complete(it) }
            "sync/semaphore/file-processor" -> SyncScenarios.runFileProcessor(session)
            "sync/semaphore/resource-timeout" ->
                SyncScenarios.runResourceTimeout(
                    session,
                ) { tried, left -> shoppers.complete(tried to left) }
            "sync/semaphore/producer-consumer" ->
                SyncScenarios.runProducerConsumer(session) { loaves, most ->
                    loavesSold.complete(
                        loaves to most,
                    )
                }
            "sync/combined/ecommerce" -> SyncScenarios.runOrderProcessing(session) { bought, left -> sneakers.complete(bought to left) }
            else -> error("not a Sync: Semaphore or Combined demo: $id")
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

    /** The demo once more on virtual time, for what must be exact: its length and its moments. */
    private fun virtual(id: String): VirtualDemoRun =
        virtualRuns.getOrPut(id) {
            var run: VirtualDemoRun? = null
            runTest { run = runOnVirtualTime("virtual-${id.replace('/', '-')}") { session, context -> startVirtual(id, session, context) } }
            assertNotNull(run)
        }

    private suspend fun startVirtual(
        id: String,
        session: VizSession,
        context: CoroutineContext,
    ): Job =
        when (id) {
            "sync/semaphore/connection-pool" -> SyncScenarios.runConnectionPool(session, context)
            "sync/semaphore/rate-limiter" -> SyncScenarios.runRateLimiter(session, context)
            "sync/semaphore/file-processor" -> SyncScenarios.runFileProcessor(session, context)
            "sync/semaphore/resource-timeout" -> SyncScenarios.runResourceTimeout(session, context)
            "sync/semaphore/producer-consumer" -> SyncScenarios.runProducerConsumer(session, context)
            "sync/combined/ecommerce" -> SyncScenarios.runOrderProcessing(session, context)
            else -> error("not a Sync: Semaphore or Combined demo: $id")
        }

    private fun VizEvent.isTerminal() = this is CoroutineCompleted || this is CoroutineCancelled || this is CoroutineFailed

    /** Exactly, on virtual time: on real time a slow scheduler only makes a run longer. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("demoIds")
    fun `each demo runs for 10 to 30 seconds`(id: String) {
        val run = virtual(id)

        assertTrue(run.scriptedMillis in 10_000..30_000, "$id runs for ${run.scriptedMillis} ms")
    }

    /** A delay never ends early, so on real time too each demo takes at least its scripted 10 s. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("demoIds")
    fun `each demo takes at least 10 seconds on real time too`(id: String) {
        val run = run(id)

        assertTrue(run.durationMillis >= 10_000.0, "$id runs for ${run.durationMillis.roundToLong()} ms")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("demoIds")
    fun `each step of a demo lasts 0,5 to 3 seconds`(id: String) {
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
     * Events less than 0.2 s of scripted time apart belong to one moment. A demo the user can follow has many such
     * moments, and no single burst carries most of what it shows. Checked on virtual time, where the moments are
     * exactly as scripted.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("demoIds")
    fun `a demo shows its work at many separate moments`(id: String) {
        val run = virtual(id)
        val times = run.events.map { run.at(it) }.sorted()
        val moments =
            times.fold(mutableListOf<MutableList<Long>>()) { moments, t ->
                if (moments.isEmpty() || t - moments.last().last() >= 200) moments += mutableListOf(t) else moments.last() += t
                moments
            }

        assertTrue(moments.size >= 8, "$id shows its events at only ${moments.size} moments")
        val largest = moments.maxOf { it.size }
        assertTrue(largest <= times.size / 2, "$id shows $largest of its ${times.size} events at one moment")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("demoIds")
    fun `the coroutines of a demo are named after its story`(id: String) {
        assertEquals(storyLabels.getValue(id), run(id).labels)
    }

    /** In any order: a wait for a permit ends with a resume, whether the permit or a timeout ends it. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("demoIds")
    fun `a coroutine that waits for a permit resumes`(id: String) {
        val run = run(id)

        run.permitWaits.forEach { assertTrue(run.resumedAfter(it), "${it.label} resumes after its wait") }
        assertTrue(run.allCompleted(), "$id: every coroutine completes")
    }

    @Test
    fun `never more than three connections are in use`() {
        assertTrue(run("sync/semaphore/connection-pool").mostHeldAtOnce("db-connections") <= 3)
    }

    @Test
    fun `the weather service gets at most two calls at a time, and the trip page shows every city`() {
        val run = run("sync/semaphore/rate-limiter")

        assertTrue(run.mostHeldAtOnce("weather-api") <= 2)
        val cities = runBlocking { withTimeout(5.seconds) { forecasts.await() } }.map { it.substringBefore(':') }
        assertEquals(listOf("rome", "paris", "vienna", "prague", "lisbon", "oslo", "split", "krakow"), cities)
    }

    @Test
    fun `at most two files are on the disk at a time`() {
        assertTrue(run("sync/semaphore/file-processor").mostHeldAtOnce("disk") <= 2)
    }

    @Test
    fun `every shopper either tries on or leaves, and at most two are in the rooms`() {
        val run = run("sync/semaphore/resource-timeout")
        val (triedOn, left) = runBlocking { withTimeout(5.seconds) { shoppers.await() } }

        assertEquals(listOf("anna", "ben", "carla", "dan", "eva", "finn", "gina"), (triedOn + left).sorted())
        assertEquals(triedOn.size, run.events.filterIsInstance<SemaphorePermitAcquired>().size, "one room each for who tried on")
        assertTrue(run.mostHeldAtOnce("fitting-rooms") <= 2)
    }

    @Test
    fun `every loaf is sold in the order baked, and the rack never holds more than three`() {
        run("sync/semaphore/producer-consumer")
        val (loaves, most) = runBlocking { withTimeout(5.seconds) { loavesSold.await() } }

        assertEquals((1..8).toList(), loaves)
        assertTrue(most <= 3, "$most loaves on the rack at once")
    }

    /**
     * Which orders buy depends on when Ben's pair comes back, which a slow scheduler can move past Eva's check (the
     * exact three sales are checked on virtual time). In any order, no pair is sold twice or lost.
     */
    @Test
    fun `no pair is sold twice or lost, ben never buys one, and at most two payments run at once`() {
        val run = run("sync/combined/ecommerce")
        val (bought, left) = runBlocking { withTimeout(5.seconds) { sneakers.await() } }

        assertEquals(3, bought.size + left, "pairs sold plus pairs left: $bought, $left left")
        assertEquals(bought.size, bought.toSet().size)
        assertTrue("order-ben" !in bought, "ben's card is declined")
        assertTrue(run.mostHeldAtOnce("payment-gateway") <= 2)
    }

    /** In any order, every permit taken comes back: each semaphore ends as it started. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("demoIds")
    fun `every permit a demo takes comes back`(id: String) {
        val run = run(id)
        val created = run.events.filterIsInstance<SemaphoreCreated>()

        for (semaphore in created) {
            val last = run.events.filterIsInstance<SemaphoreStateChanged>().last { it.semaphoreId == semaphore.semaphoreId }
            val atStart = if (semaphore.semaphoreLabel == "loaves-ready") 0 else semaphore.totalPermits
            assertEquals(atStart, last.availablePermits, "$id: ${semaphore.semaphoreLabel} ends as it started")
        }
    }

    companion object {
        /** Well below 1 so the six demos take seconds here, well above 0 so their moments stay apart. */
        private const val SCALE = 0.1

        private val runs = ConcurrentHashMap<String, Run>()
        private val virtualRuns = ConcurrentHashMap<String, VirtualDemoRun>()
        private val forecasts = CompletableDeferred<List<String>>()
        private val shoppers = CompletableDeferred<Pair<List<String>, List<String>>>()
        private val loavesSold = CompletableDeferred<Pair<List<Int>, Int>>()
        private val sneakers = CompletableDeferred<Pair<List<String>, Int>>()

        private val storyLabels =
            mapOf(
                "sync/semaphore/connection-pool" to
                    setOf(
                        "shop-backend",
                        "show-basket",
                        "search-shoes",
                        "order-history",
                        "track-parcel",
                        "write-review",
                        "checkout",
                        "wish-list",
                        "store-hours",
                        "loyalty-points",
                    ),
                "sync/semaphore/rate-limiter" to
                    setOf("trip-planner", "trip-page") +
                    listOf("rome", "paris", "vienna", "prague", "lisbon", "oslo", "split", "krakow").map { "weather-$it" },
                "sync/semaphore/file-processor" to
                    setOf(
                        "tax-folder",
                        "payslips.pdf",
                        "bank-statements.pdf",
                        "receipts.zip",
                        "insurance.pdf",
                        "donations.pdf",
                        "pension.pdf",
                        "childcare.pdf",
                        "invoices.zip",
                        "doctor-bills.pdf",
                    ),
                "sync/semaphore/resource-timeout" to setOf("clothes-shop", "anna", "ben", "carla", "dan", "eva", "finn", "gina"),
                "sync/semaphore/producer-consumer" to setOf("bakery", "baker", "shop-assistant"),
                "sync/combined/ecommerce" to
                    setOf("sneaker-drop", "order-anna", "order-ben", "order-carla", "order-dan", "order-eva", "order-finn"),
            )

        @JvmStatic
        fun demoIds(): List<String> = storyLabels.keys.toList()
    }
}
