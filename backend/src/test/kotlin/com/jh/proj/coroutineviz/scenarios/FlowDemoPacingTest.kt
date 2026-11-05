package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.events.flow.FlowValueEmitted
import com.jh.proj.coroutineviz.events.flow.FlowValueFiltered
import com.jh.proj.coroutineviz.events.flow.FlowValueTransformed
import com.jh.proj.coroutineviz.events.flow.SharedFlowEmission
import com.jh.proj.coroutineviz.events.flow.StateFlowValueChanged
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
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
 * The Flow demos are paced so a person can follow them, as the Basics are: each runs 10-30 s in
 * steps of 0.5-3 s, its coroutines are named after the real-life story it tells, and the values
 * travel through the flows at moments far enough apart to see. Before, each ended within a second.
 *
 * The demos run here at [SCALE] of their scripted speed (`scenarios.timeScale`); every time below
 * is scaled back up to scripted time.
 */
class FlowDemoPacingTest {
    private class Run(
        val events: List<VizEvent>,
    ) {
        private val start = events.minOf { it.tsNanos }
        private val coroutines = events.filterIsInstance<CoroutineCreated>()
        private val labelOf = coroutines.associate { it.coroutineId to it.label }

        val labels: Set<String> = coroutines.mapNotNull { it.label }.toSet()

        /** Scripted milliseconds from the demo's first event to [event]. */
        fun at(event: VizEvent): Double = (event.tsNanos - start) / 1_000_000.0 / SCALE

        val durationMillis: Double get() = events.maxOf { at(it) }

        inline fun <reified T : CoroutineEvent> of(label: String): T? = events.filterIsInstance<T>().firstOrNull { it.label == label }

        fun startedAt(label: String) = at(assertNotNull(of<CoroutineStarted>(label), "$label never started"))

        fun completed(label: String) = of<CoroutineCompleted>(label) != null

        /** The values a coroutine received from the flows it collected, in order. */
        fun received(label: String): List<FlowValueEmitted> =
            events.filterIsInstance<FlowValueEmitted>().filter { labelOf[it.coroutineId] == label }.sortedBy { it.seq }
    }

    private suspend fun start(
        id: String,
        session: VizSession,
    ): Job =
        when (id) {
            "flow/simple" -> FlowScenarios.simpleFlow(session)
            "flow/operators" -> FlowScenarios.flowOperators(session)
            "flow/stateflow" -> FlowScenarios.stateFlowDemo(session)
            "flow/sharedflow" -> FlowScenarios.sharedFlowDemo(session)
            else -> error("not a Flow demo: $id")
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
    @MethodSource("flowIds")
    fun `each Flow demo runs for 10 to 30 seconds`(id: String) {
        val run = run(id)

        assertTrue(run.durationMillis in 10_000.0..30_000.0, "$id runs for ${run.durationMillis.roundToLong()} ms")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("flowIds")
    fun `each step of a Flow demo lasts 0,5 to 3 seconds`(id: String) {
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
    @MethodSource("flowIds")
    fun `a Flow demo shows its work at many separate moments`(id: String) {
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
    @MethodSource("flowIds")
    fun `the coroutines of a Flow demo are named after its story, and all complete`(id: String) {
        val run = run(id)

        assertEquals(storyLabels.getValue(id), run.labels)
        assertTrue(run.labels.all { run.completed(it) }, "$id: every coroutine completes")
    }

    @Test
    fun `the ticker's cold flow runs again from the first price for every screen that collects it`() {
        val run = run("flow/simple")
        val prices = listOf("101.2", "101.8", "100.9", "102.4", "103.1", "102.7")

        assertEquals(prices, run.received("price-chart").map { it.valuePreview })
        assertEquals(prices, run.received("price-alert").map { it.valuePreview })
        val alertFirst = run.received("price-alert").first().seq
        val chartBefore = run.received("price-chart").count { it.seq < alertFirst }
        assertTrue(chartBefore >= 2, "the alert opens later and still gets the first price; the chart had $chartBefore by then")
    }

    @Test
    fun `search as you type waits for a pause in typing, drops short queries, then asks for results`() {
        val run = run("flow/operators")

        val searched =
            run.events
                .filterIsInstance<FlowValueTransformed>()
                .sortedBy { it.seq }
                .map { it.inputValuePreview }
        assertEquals(listOf("kotlin", "kotlin flow"), searched)
        val dropped =
            run.events
                .filterIsInstance<FlowValueFiltered>()
                .filter { !it.passed }
                .map { it.valuePreview }
        assertEquals(listOf("ko"), dropped, "the debounced \"ko\" is too short to search")
        // The keystroke "kotlin" is the one the search box received last before the search started.
        val typedKotlin =
            run.events
                .filterIsInstance<SharedFlowEmission>()
                .single { it.valuePreview == "kotlin" }
        val searchedKotlin = run.events.filterIsInstance<FlowValueTransformed>().minBy { it.seq }
        assertTrue(
            run.at(searchedKotlin) - run.at(typedKotlin) >= 1_000,
            "the search starts after the user stopped typing, ${run.at(searchedKotlin) - run.at(typedKotlin)} ms later",
        )
    }

    /**
     * At the smallest time scale a keystroke gap (5 ms) is close to the debounce timeout (12 ms),
     * so "kotlin" can be merged with the keys after it and only one search happens. The demo must
     * still end: it stops after the results for the whole query, not after a count of searches.
     */
    @Test
    fun `search as you type ends even when the typing pauses blur together`() =
        runBlocking {
            repeat(10) {
                val session = VizSession("pacing-operators-fast-$it")
                try {
                    val run = assertNotNull(DemoRuns(maxConcurrent = 0, timeScale = DemoPace.MIN_SCALE).tryStart())
                    withTimeout(3.seconds) { withContext(run) { FlowScenarios.flowOperators(session) }.join() }
                } finally {
                    session.close()
                }
            }
        }

    /** The case that hung with take(2): "kotlin" merged into the later typing, so only one search came. */
    @Test
    fun `the results screen stops after the results for the whole query, however many searches came before`() =
        runBlocking {
            val onlyTheLastSearch =
                flow<String> {
                    emit(FlowScenarios.resultsFor("kotlin flow"))
                    awaitCancellation() // the keystrokes are a SharedFlow: they never complete
                }

            val shown = withTimeout(5.seconds) { onlyTheLastSearch.untilResultsFor("kotlin flow").toList() }
            assertEquals(listOf(FlowScenarios.resultsFor("kotlin flow")), shown)
        }

    @Test
    fun `the cart total always has a value, and a checkout opened later gets the latest total at once`() {
        val run = run("flow/stateflow")

        assertEquals(listOf("0", "25", "40", "52", "64", "70"), run.received("cart-badge").map { it.valuePreview })
        val checkout = run.received("checkout-summary")
        assertEquals(listOf("52", "64", "70"), checkout.map { it.valuePreview })
        // At once: before the next total is set, which the user does about 2 s after the checkout opens.
        val next = run.events.filterIsInstance<StateFlowValueChanged>().single { it.newValuePreview == "64" }
        assertTrue(checkout.first().seq < next.seq, "the checkout gets the current total before the next change")
    }

    @Test
    fun `each notification reaches every open screen at the same moment, and a screen opened later only what follows`() {
        val run = run("flow/sharedflow")
        val notifications =
            listOf("Ana sent you a message", "Your order has shipped", "Meeting in 10 minutes", "Battery at 15%", "Ana is calling")
        val screens = listOf("home-screen", "chat-screen", "settings-screen")

        screens.forEach { assertEquals(notifications, run.received(it).map { e -> e.valuePreview }, it) }
        notifications.indices.forEach { i ->
            val times = screens.map { run.at(run.received(it)[i]) }
            // Together: well within the 2.5 s between notifications, with room for the threads' scheduling.
            assertTrue(times.max() - times.min() < 1_000, "notification ${i + 1} reaches the screens together: $times")
        }
        assertEquals(notifications.takeLast(3), run.received("profile-screen").map { it.valuePreview })
    }

    companion object {
        /** Well below 1 so the four demos take seconds here, well above 0 so their moments stay apart. */
        private const val SCALE = 0.1

        private val runs = ConcurrentHashMap<String, Run>()

        private val storyLabels =
            mapOf(
                "flow/simple" to setOf("stock-ticker", "price-chart", "price-alert"),
                "flow/operators" to setOf("search-screen", "show-results", "type-query"),
                "flow/stateflow" to setOf("shopping-cart", "cart-badge", "add-items", "checkout-summary"),
                "flow/sharedflow" to
                    setOf("notification-center", "home-screen", "chat-screen", "settings-screen", "push-server", "profile-screen"),
            )

        @JvmStatic
        fun flowIds(): List<String> = storyLabels.keys.toList()
    }
}
