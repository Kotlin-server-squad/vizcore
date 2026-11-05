package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.channel.ChannelBufferStateChanged
import com.jh.proj.coroutineviz.events.channel.ChannelReceiveCompleted
import com.jh.proj.coroutineviz.events.channel.ChannelReceiveStarted
import com.jh.proj.coroutineviz.events.channel.ChannelSendCompleted
import com.jh.proj.coroutineviz.events.channel.ChannelSendStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The Patterns demos are paced so a person can follow them, as the Basics and the Channels are
 * ([ChannelsDemoPacingTest]): each runs 10-30 s in steps of 0.5-3 s, its coroutines start, wait and
 * end at different moments, and they are named after the story the demo tells. Before, each ended
 * within two seconds. Each demo also does what its name says: the retry retries with growing waits,
 * the supervisor keeps the other children running after one fails, and the circuit breaker opens,
 * rejects and half-opens. Before, the first failure cancelled the retry, the supervisor's siblings
 * and the circuit breaker's whole run.
 *
 * The demos run here at [SCALE] of their scripted speed (`scenarios.timeScale`); every time below
 * is scaled back up to scripted time.
 */
class PatternsDemoPacingTest {
    private class Run(
        val events: List<VizEvent>,
    ) {
        private val start = events.minOf { it.tsNanos }
        private val coroutines = events.filterIsInstance<CoroutineCreated>()
        private val idOf = coroutines.associate { it.label to it.coroutineId }
        private val labelOf = coroutines.associate { it.coroutineId to it.label }

        val labels: Set<String> = coroutines.mapNotNull { it.label }.toSet()

        /** Scripted milliseconds from the demo's first event to [event]. */
        fun at(event: VizEvent): Double = (event.tsNanos - start) / 1_000_000.0 / SCALE

        val durationMillis: Double get() = events.maxOf { at(it) }

        inline fun <reified T : CoroutineEvent> of(label: String): T? = events.filterIsInstance<T>().firstOrNull { it.label == label }

        fun startedAt(label: String) = at(assertNotNull(of<CoroutineStarted>(label), "$label never started"))

        fun endedAt(label: String): Double {
            val end =
                of<CoroutineCompleted>(label) ?: of<CoroutineCancelled>(label) ?: of<CoroutineFailed>(label)
            return at(assertNotNull(end, "$label never ended"))
        }

        fun completed(label: String) = of<CoroutineCompleted>(label) != null

        fun failed(label: String) = of<CoroutineFailed>(label) != null

        fun childrenOf(label: String): List<String> =
            coroutines.filter { it.parentCoroutineId != null && labelOf[it.parentCoroutineId] == label }.mapNotNull { it.label }

        private fun id(label: String) = assertNotNull(idOf[label], "no coroutine $label")

        /** Each send by [label] as (started, completed), in scripted ms, in order. */
        fun sendsBy(label: String): List<Pair<Double, Double>> {
            val id = id(label)
            val started = events.filterIsInstance<ChannelSendStarted>().filter { it.coroutineId == id }.sortedBy { it.seq }
            val completed = events.filterIsInstance<ChannelSendCompleted>().filter { it.coroutineId == id }.sortedBy { it.seq }
            return started.zip(completed) { s, c -> at(s) to at(c) }
        }

        /** Each receive by [label] that got a value, as (started, completed), in scripted ms, in order. */
        fun receivesBy(label: String): List<Pair<Double, Double>> {
            val id = id(label)
            val started = events.filterIsInstance<ChannelReceiveStarted>().filter { it.coroutineId == id }.sortedBy { it.seq }
            val completed = events.filterIsInstance<ChannelReceiveCompleted>().filter { it.coroutineId == id }.sortedBy { it.seq }
            return started.zip(completed) { s, c -> at(s) to at(c) }
        }

        /** The values [label] received, in order. */
        fun receivedValuesBy(label: String): List<String> {
            val id = id(label)
            return events
                .filterIsInstance<ChannelReceiveCompleted>()
                .filter { it.coroutineId == id }
                .sortedBy { it.seq }
                .map { it.valueDescription }
        }

        /** How full the demo's channels are after each change, in the order the session received them. */
        fun bufferSizes(): List<Int> = events.filterIsInstance<ChannelBufferStateChanged>().sortedBy { it.seq }.map { it.currentSize }
    }

    private suspend fun start(
        id: String,
        session: VizSession,
    ): Job =
        when (id) {
            "patterns/retry" -> PatternScenarios.runRetry(session)
            "patterns/producer-consumer" -> PatternScenarios.runProducerConsumer(session)
            "patterns/fan-out-fan-in" -> PatternScenarios.runFanOutFanIn(session)
            "patterns/supervisor" -> PatternScenarios.runSupervisor(session)
            "patterns/circuit-breaker" -> PatternScenarios.runCircuitBreaker(session)
            else -> error("not a Patterns demo: $id")
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
                Run(session.store.all())
            } finally {
                session.close()
            }
        }

    private fun VizEvent.isTerminal() = this is CoroutineCompleted || this is CoroutineCancelled || this is CoroutineFailed

    @ParameterizedTest(name = "{0}")
    @MethodSource("patternIds")
    fun `each Patterns demo runs for 10 to 30 seconds`(id: String) {
        val run = run(id)

        assertTrue(run.durationMillis in 10_000.0..30_000.0, "$id runs for ${run.durationMillis.roundToLong()} ms")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("patternIds")
    fun `each step of a Patterns demo lasts 0,5 to 3 seconds`(id: String) {
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
    @MethodSource("patternIds")
    fun `a Patterns demo shows its work at many separate moments`(id: String) {
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
    @MethodSource("patternIds")
    fun `the coroutines of a Patterns demo are named after its story`(id: String) {
        assertEquals(storyLabels.getValue(id), run(id).labels)
    }

    @Test
    fun `the forecast is retried after waits that double each time, and the fourth try gets it`() {
        val run = run("patterns/retry")

        (1..3).forEach { assertTrue(run.failed("attempt-$it"), "attempt-$it fails: the server is overloaded") }
        assertTrue(run.completed("attempt-4"), "the fourth try gets the forecast")
        // The backoff as the app scripts it: three waits, each twice the one before, then showing the forecast.
        val waits =
            run.events
                .filterIsInstance<CoroutineSuspended>()
                .filter { it.label == "load-forecast" && it.reason == "delay" }
                .map { (assertNotNull(it.durationMillis) / SCALE).roundToLong() }
        assertEquals(listOf(750L, 1_500L, 3_000L, 1_000L), waits)
        // And each next try really starts only after its wait (broadly: these are real, scaled times).
        val gaps = (1..3).map { run.startedAt("attempt-${it + 1}") - run.endedAt("attempt-$it") }
        listOf(750.0, 1_500.0, 3_000.0).zip(gaps).forEach { (scripted, gap) ->
            assertTrue(gap >= scripted * 0.8, "the next try waits about $scripted ms, waited $gap: $gaps")
        }
        assertTrue(run.completed("load-forecast"), "the failures were handled, so the forecast loads")
    }

    @Test
    fun `the cashier waits when the rail is full, and the barista waits when it is empty`() {
        val run = run("patterns/producer-consumer")

        assertEquals(3, run.bufferSizes().max(), "the rail fills up")
        val cashierWaits = run.sendsBy("cashier").map { (started, completed) -> completed - started }
        assertEquals(10, cashierWaits.size, "the cashier takes ten orders")
        assertTrue(cashierWaits.any { it >= 500 }, "the cashier waits for a free place on the rail: $cashierWaits")
        val baristaWaits = run.receivesBy("barista").drop(1).map { (started, completed) -> completed - started }
        assertTrue(baristaWaits.count { it >= 500 } >= 2, "in the quiet spell the barista waits for orders: $baristaWaits")
        assertEquals((1..10).map { "order-$it" }, run.receivedValuesBy("barista"), "every order, in order")
        assertTrue(run.labels.all { run.completed(it) })
    }

    @Test
    fun `three resizers share the photos, and the album collects them as they are done`() {
        val run = run("patterns/fan-out-fan-in")
        val photos = (1..9).map { "photo-$it" }
        val byResizer = (1..3).associate { "resizer-$it" to run.receivedValuesBy("resizer-$it") }

        assertEquals(photos, byResizer.values.flatten().sortedBy { it.removePrefix("photo-").toInt() }, "every photo, exactly once")
        byResizer.forEach { (resizer, got) -> assertTrue(got.size >= 2, "$resizer resized only $got") }
        val album = run.receivedValuesBy("album")
        assertEquals(photos.toSet(), album.toSet(), "the album gets every photo")
        assertEquals(9, album.size, "the album gets each photo once")
        assertNotEquals(photos, album, "the album gets the photos as they are finished, not in the order they were picked")
        assertTrue(run.labels.all { run.completed(it) })
    }

    @Test
    fun `the message backup fails, and photos and contacts keep backing up`() {
        val run = run("patterns/supervisor")
        val failedAt = run.endedAt("backup-messages")

        assertTrue(run.failed("backup-messages"), "the message backup fails")
        for (backup in listOf("backup-photos", "backup-contacts")) {
            assertTrue(run.completed(backup), "$backup is not cancelled by its sibling's failure")
            assertTrue(run.endedAt(backup) >= failedAt + 2_000, "$backup keeps going after the failure")
        }
        val lastBackup = maxOf(run.endedAt("backup-photos"), run.endedAt("backup-contacts"))
        assertTrue(run.startedAt("send-report") >= lastBackup, "the report comes after all three")
        assertTrue(run.completed("nightly-backup"), "a supervisor does not fail with its child")
    }

    @Test
    fun `the breaker opens after three failures, rejects orders, then lets one trial through and closes`() {
        val run = run("patterns/circuit-breaker")

        for (n in 1..3) {
            assertEquals(listOf("charge-$n"), run.childrenOf("order-$n"), "order-$n calls the provider")
            assertTrue(run.failed("charge-$n") && run.failed("order-$n"), "order-$n fails: the provider is down")
        }
        for (n in 4..5) {
            assertTrue(run.failed("order-$n"), "order-$n is rejected")
            assertEquals(emptyList(), run.childrenOf("order-$n"), "a rejected order does not call the provider")
            // "At once" next to a charge's 1.5 s; broad, since at test scale 1 s is 100 ms of real time.
            assertTrue(run.endedAt("order-$n") - run.startedAt("order-$n") < 1_000, "a rejected order fails at once")
        }
        // A delay never ends early, so the threads can only make an order later than scripted. Order 6
        // is safely past the cooldown that way; order 5 needs room before it, or a slow run turns it
        // into the trial.
        val open = PatternScenarios.BREAKER_OPEN_MILLIS
        val order5 = run.startedAt("order-5") - run.endedAt("charge-3")
        assertTrue(order5 <= open - 1_200, "order 5 comes $order5 ms after the breaker opened, too close to its $open ms")
        assertTrue(run.startedAt("order-6") - run.endedAt("charge-3") >= open, "the trial order comes after the cooldown")
        for (n in 6..8) {
            assertEquals(listOf("charge-$n"), run.childrenOf("order-$n"), "order-$n calls the provider")
            assertTrue(run.completed("charge-$n") && run.completed("order-$n"), "order-$n is paid: the provider is back")
        }
        assertTrue(run.completed("checkout"), "the failed orders do not stop the checkout")
    }

    companion object {
        /** Well below 1 so the five demos take seconds here, well above 0 so their moments stay apart. */
        private const val SCALE = 0.1

        private val runs = ConcurrentHashMap<String, Run>()

        private val storyLabels =
            mapOf(
                "patterns/retry" to setOf("load-forecast", "attempt-1", "attempt-2", "attempt-3", "attempt-4"),
                "patterns/producer-consumer" to setOf("coffee-bar", "cashier", "barista"),
                "patterns/fan-out-fan-in" to setOf("make-album", "pick-photos", "resizer-1", "resizer-2", "resizer-3", "album"),
                "patterns/supervisor" to setOf("nightly-backup", "backup-photos", "backup-contacts", "backup-messages", "send-report"),
                "patterns/circuit-breaker" to
                    setOf("checkout") + (1..8).map { "order-$it" } + listOf(1, 2, 3, 6, 7, 8).map { "charge-$it" },
            )

        @JvmStatic
        fun patternIds(): List<String> = storyLabels.keys.toList()
    }
}
