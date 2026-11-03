package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineBodyCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.events.dispatcher.DispatcherSelected
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
 * The Basics demos are paced so a person can follow them: each runs 10-30 s in steps of 0.5-3 s,
 * its coroutines start, suspend and end at different moments, and they are named after the
 * real-life story the demo tells. Before, most of them ended within a second, so a demo the user
 * started showed a finished tree straight away.
 *
 * The demos run here at [SCALE] of their scripted speed (`scenarios.timeScale`); every time below
 * is scaled back up to scripted time.
 */
class BasicsDemoPacingTest {
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

        fun endedAt(label: String): Double {
            val end =
                of<CoroutineCompleted>(label) ?: of<CoroutineCancelled>(label) ?: of<CoroutineFailed>(label)
            return at(assertNotNull(end, "$label never ended"))
        }

        fun parentOf(label: String): String? = coroutines.single { it.label == label }.parentCoroutineId?.let { labelOf[it] }

        fun completed(label: String) = of<CoroutineCompleted>(label) != null

        fun cancelled(label: String) = of<CoroutineCancelled>(label) != null

        fun failed(label: String) = of<CoroutineFailed>(label) != null
    }

    private suspend fun start(
        id: String,
        session: VizSession,
    ): Job =
        when (id) {
            "nested" -> BasicScenarios.runNestedCoroutines(session)
            "parallel" -> BasicScenarios.runParallelExecution(session)
            "cancellation" -> BasicScenarios.runCancellationScenario(session)
            "deep-nesting" -> BasicScenarios.runDeepNesting(session, 5)
            "mixed" -> BasicScenarios.runMixedScenario(session)
            "exception" -> BasicScenarios.runExceptionScenario(session)
            "dispatcher" -> BasicScenarios.runDispatcherScenario(session)
            else -> error("not a Basics demo: $id")
        }

    /** Each demo runs once; the tests below read different things from the same run. */
    private fun run(id: String): Run = runs.getOrPut(id) { execute(id) }

    private fun execute(id: String): Run =
        runBlocking {
            val session = VizSession("pacing-$id")
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
    @MethodSource("basicsIds")
    fun `each Basics demo runs for 10 to 30 seconds`(id: String) {
        val run = run(id)

        assertTrue(run.durationMillis in 10_000.0..30_000.0, "$id runs for ${run.durationMillis.roundToLong()} ms")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("basicsIds")
    fun `each step of a Basics demo lasts 0,5 to 3 seconds`(id: String) {
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
    @MethodSource("basicsIds")
    fun `a Basics demo shows its work at many separate moments`(id: String) {
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
    @MethodSource("basicsIds")
    fun `the coroutines of a Basics demo are named after its story`(id: String) {
        assertEquals(storyLabels.getValue(id), run(id).labels)
    }

    @Test
    fun `the product page completes only after the last request below it, long after its own code ended`() {
        val run = run("nested")

        val bodyEnded = run.at(assertNotNull(run.of<CoroutineBodyCompleted>("load-product-page")))
        val pageEnded = run.endedAt("load-product-page")
        assertTrue(pageEnded - bodyEnded >= 8_000, "the page waits ${pageEnded - bodyEnded} ms for its children")
        assertTrue(run.labels.all { run.endedAt(it) <= pageEnded }, "the page must end last")
        assertTrue(run.labels.all { run.completed(it) })
        assertEquals(
            listOf("fetch-reviews", "load-reviews", "load-product-page"),
            generateSequence(run.parentOf("fetch-review-photos")) { run.parentOf(it) }.toList(),
            "three levels above the review photos",
        )
    }

    @Test
    fun `the dashboard loads its widgets at the same time and renders once the slowest is in`() {
        val run = run("parallel")
        val widgets = listOf("fetch-sales", "fetch-orders", "fetch-visitors", "fetch-alerts", "fetch-top-products")

        assertTrue(widgets.maxOf { run.startedAt(it) } < widgets.minOf { run.endedAt(it) }, "all widgets load at once")
        assertTrue(run.startedAt("render-dashboard") >= widgets.maxOf { run.endedAt(it) })
        val sequential = widgets.sumOf { run.endedAt(it) - run.startedAt(it) }
        assertTrue(run.durationMillis < sequential / 2, "parallel: ${run.durationMillis} ms, one after another: $sequential ms")
        assertTrue(run.labels.all { run.completed(it) })
    }

    @Test
    fun `closing the screen cancels the screen's requests still running, and nothing outside it`() {
        val run = run("cancellation")

        listOf("profile-screen", "fetch-friends", "fetch-activity-feed").forEach {
            assertTrue(run.cancelled(it), "$it must be cancelled")
        }
        listOf("fetch-avatar", "sync-settings", "app-session").forEach { assertTrue(run.completed(it), "$it must complete") }
        val closed = run.endedAt("profile-screen")
        assertTrue(closed >= 5_000, "the screen is closed after the user has seen it load, at $closed ms")
        assertTrue(run.endedAt("fetch-avatar") < closed, "the avatar was loaded before the screen was closed")
        assertTrue(run.endedAt("sync-settings") - closed >= 3_000, "settings sync carries on after the screen is closed")
    }

    @Test
    fun `the indexer walks one folder deeper at a time and completes them from the deepest up`() {
        val run = run("deep-nesting")
        val folders = listOf("index-home", "index-ana", "index-projects", "index-vizcore", "index-backend", "index-src")

        folders.zipWithNext().forEach { (parent, child) -> assertEquals(parent, run.parentOf(child)) }
        folders.zipWithNext().forEach { (parent, child) ->
            assertTrue(run.startedAt(child) - run.startedAt(parent) >= 500, "$child starts after $parent has listed it")
            assertTrue(run.endedAt(parent) - run.endedAt(child) >= 500, "$parent completes after $child")
        }
        assertTrue(folders.all { run.completed(it) })
    }

    @Test
    fun `checkout validates first, then does three things at once, then creates the order and sends the receipt`() {
        val run = run("mixed")
        val together = listOf("reserve-stock", "charge-card", "calculate-shipping")

        assertTrue(together.all { run.startedAt(it) >= run.endedAt("validate-cart") })
        assertTrue(together.maxOf { run.startedAt(it) } < together.minOf { run.endedAt(it) }, "the three run at once")
        assertTrue(run.startedAt("create-order") >= together.maxOf { run.endedAt(it) })
        assertTrue(run.startedAt("send-receipt") >= run.endedAt("create-order"))
        assertTrue(run.labels.all { run.completed(it) })
    }

    @Test
    fun `one failing backend call fails the page and cancels the calls still running, not those already done`() {
        val run = run("exception")

        val failure = assertNotNull(run.of<CoroutineFailed>("fetch-loyalty-points"))
        assertTrue(failure.message.orEmpty().contains("500"), "got ${failure.message}")
        listOf("fetch-orders", "fetch-invoices").forEach { assertTrue(run.cancelled(it), "$it must be cancelled") }
        listOf("fetch-delivery-status", "fetch-profile").forEach { assertTrue(run.completed(it), "$it must complete") }
        assertTrue(!run.completed("load-account-page"), "the page must not complete")
        // Cancelled by the failure: after the loyalty call's last step resumed (where it throws),
        // and long before their own steps would have ended, 15 s in. Events are in the order the
        // session received them, so this holds however the threads were scheduled.
        val thrownAt =
            run.events
                .filterIsInstance<CoroutineResumed>()
                .last { it.label == "fetch-loyalty-points" }
                .seq
        listOf("fetch-orders", "fetch-invoices").forEach {
            val cancelled = assertNotNull(run.of<CoroutineCancelled>(it))
            assertTrue(cancelled.seq > thrownAt, "$it is cancelled after the loyalty call fails")
            assertTrue(run.at(cancelled) < 14_000, "$it is cancelled at ${run.at(cancelled)} ms, before its own steps end")
        }
    }

    @Test
    fun `each photo is downloaded and uploaded on IO and resized on Default`() {
        val run = run("dispatcher")
        val selected =
            run.events
                .filterIsInstance<DispatcherSelected>()
                .groupBy({ it.label.orEmpty() }, { it.dispatcherName })

        (1..3).forEach { photo ->
            assertEquals(setOf("Dispatchers.IO"), selected["download-photo-$photo"]?.toSet(), "download-photo-$photo")
            assertEquals(setOf("Dispatchers.Default"), selected["resize-photo-$photo"]?.toSet(), "resize-photo-$photo")
            assertEquals(setOf("Dispatchers.IO"), selected["upload-thumbnail-$photo"]?.toSet(), "upload-thumbnail-$photo")
        }
        assertTrue(run.startedAt("process-photo-2") < run.endedAt("process-photo-1"), "photos are processed while others are")
        assertTrue(run.labels.all { run.completed(it) })
    }

    companion object {
        /** Well below 1 so the seven demos take seconds here, well above 0 so their moments stay apart. */
        private const val SCALE = 0.1

        private val runs = ConcurrentHashMap<String, Run>()

        private val storyLabels =
            mapOf(
                "nested" to
                    setOf(
                        "load-product-page",
                        "fetch-product",
                        "load-price-box",
                        "fetch-price",
                        "fetch-delivery-date",
                        "load-reviews",
                        "fetch-reviews",
                        "fetch-review-photos",
                        "load-recommendations",
                        "fetch-similar-products",
                        "fetch-thumbnails",
                    ),
                "parallel" to
                    setOf(
                        "load-dashboard",
                        "fetch-sales",
                        "fetch-orders",
                        "fetch-visitors",
                        "fetch-alerts",
                        "fetch-top-products",
                        "render-dashboard",
                    ),
                "cancellation" to
                    setOf("app-session", "profile-screen", "fetch-avatar", "fetch-friends", "fetch-activity-feed", "sync-settings"),
                "deep-nesting" to
                    setOf("index-home", "index-ana", "index-projects", "index-vizcore", "index-backend", "index-src"),
                "mixed" to
                    setOf(
                        "checkout",
                        "validate-cart",
                        "reserve-stock",
                        "charge-card",
                        "calculate-shipping",
                        "create-order",
                        "send-receipt",
                    ),
                "exception" to
                    setOf(
                        "load-account-page",
                        "fetch-delivery-status",
                        "fetch-profile",
                        "fetch-orders",
                        "fetch-invoices",
                        "fetch-loyalty-points",
                    ),
                "dispatcher" to
                    setOf("make-thumbnails") +
                    (1..3).flatMap { listOf("process-photo-$it", "download-photo-$it", "resize-photo-$it", "upload-thumbnail-$it") },
            )

        @JvmStatic
        fun basicsIds(): List<String> = storyLabels.keys.toList()
    }
}
