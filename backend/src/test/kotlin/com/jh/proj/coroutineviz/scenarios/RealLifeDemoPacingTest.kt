package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
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
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.roundToLong
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The Real life demos are paced so a person can follow them, as the other groups' demos are
 * ([SemaphoreDemoPacingTest]): each runs 10-30 s in steps of 0.5-3 s, its coroutines start and end at different
 * moments, and they are named after the story. Before, each slept for random times. Their failure options are paced
 * the same way, and checked here too: `order-processing?fail=true`, `user-registration?failEmail=true` and
 * `report-generation?timeout=true`.
 *
 * The demos run here on real time at [SCALE] of their scripted speed; every time below is scaled back up to scripted
 * time. What must be exact runs on virtual time: the length and moments of each run here, the stories in
 * [RealLifeStoriesTest]. On real time each run gets what holds however the threads run: at least 10 s, its steps,
 * its names and its outcome.
 */
class RealLifeDemoPacingTest {
    private class Run(
        val events: List<VizEvent>,
    ) {
        private val start = events.minOf { it.tsNanos }

        val labels: Set<String> = events.filterIsInstance<CoroutineCreated>().mapNotNull { it.label }.toSet()

        /** Scripted milliseconds from the demo's first event to [event]. */
        fun at(event: VizEvent): Double = (event.tsNanos - start) / 1_000_000.0 / SCALE

        val durationMillis: Double get() = events.maxOf { at(it) }

        /** How each coroutine ended, by label. */
        val outcomes: Map<String, String>
            get() =
                events
                    .mapNotNull {
                        when (it) {
                            is CoroutineCompleted -> it.label!! to "completed"
                            is CoroutineCancelled -> it.label!! to "cancelled"
                            is CoroutineFailed -> it.label!! to "failed"
                            else -> null
                        }
                    }.toMap()
    }

    /**
     * Runs the demo with this id ("order-processing", or with its failure option, "order-processing?fail=true"). Only
     * the real-time runs pass [onReport], so the report tests read what a real-time run reported.
     */
    private suspend fun start(
        id: String,
        session: VizSession,
        context: CoroutineContext = EmptyCoroutineContext,
        onReport: (List<String>) -> Unit = {},
    ): Job =
        when (id) {
            "order-processing" -> RealLifeScenarios.runOrderProcessing(session, context)
            "order-processing?fail=true" -> RealLifeScenarios.runOrderProcessing(session, context, cardDeclined = true)
            "user-registration" -> RealLifeScenarios.runUserRegistration(session, context)
            "user-registration?failEmail=true" -> RealLifeScenarios.runUserRegistration(session, context, firstEmailTimesOut = true)
            "report-generation" -> RealLifeScenarios.runReportGeneration(session, context, onReport = onReport)
            "report-generation?timeout=true" -> RealLifeScenarios.runReportGeneration(session, context, analyticsTimesOut = true, onReport)
            else -> error("not a Real life demo: $id")
        }

    /** Each run happens once; the tests below read different things from the same run. */
    private fun run(id: String): Run = runs.getOrPut(id) { execute(id) }

    private fun execute(id: String): Run =
        runBlocking {
            val session = VizSession("pacing-${id.replace(Regex("[/?=]"), "-")}")
            try {
                val run = assertNotNull(DemoRuns(maxConcurrent = 0, timeScale = SCALE).tryStart())
                withTimeout(20.seconds) {
                    withContext(run) { start(id, session) { reports[id]?.complete(it) } }.join()
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

    /** The run once more on virtual time, for what must be exact: its length and its moments. */
    private fun virtual(id: String): VirtualDemoRun =
        virtualRuns.getOrPut(id) {
            var run: VirtualDemoRun? = null
            runTest {
                run =
                    runOnVirtualTime("virtual-${id.replace(Regex("[/?=]"), "-")}") { session, context -> start(id, session, context) }
            }
            assertNotNull(run)
        }

    private fun VizEvent.isTerminal() = this is CoroutineCompleted || this is CoroutineCancelled || this is CoroutineFailed

    /** Exactly, on virtual time: on real time a slow scheduler only makes a run longer. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("runIds")
    fun `each run lasts 10 to 30 seconds`(id: String) {
        val run = virtual(id)

        assertTrue(run.scriptedMillis in 10_000..30_000, "$id runs for ${run.scriptedMillis} ms")
    }

    /** A delay never ends early, so on real time too each run takes at least its scripted 10 s. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("runIds")
    fun `each run takes at least 10 seconds on real time too`(id: String) {
        val run = run(id)

        assertTrue(run.durationMillis >= 10_000.0, "$id runs for ${run.durationMillis.roundToLong()} ms")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("runIds")
    fun `each step lasts 0,5 to 3 seconds`(id: String) {
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
    @MethodSource("runIds")
    fun `a run shows its work at many separate moments`(id: String) {
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
    @MethodSource("runIds")
    fun `the coroutines of a run are named after its story`(id: String) {
        assertEquals(storyLabels.getValue(id), run(id).labels)
    }

    /**
     * The analytics request answers 1 s of scripted time before its time limit, only 100 ms here: a slow scheduler could
     * let the limit win, so on real time the request may end either way (on virtual time it completes).
     */
    @Test
    fun `without a failure option every coroutine completes`() {
        for (id in listOf("order-processing", "user-registration", "report-generation")) {
            val outcomes = run(id).outcomes
            assertEquals(storyLabels.getValue(id), outcomes.keys, id)
            val mayEndEitherWay = setOf("AnalyticsApi.request")
            assertTrue(outcomes.filterKeys { it !in mayEndEitherWay }.values.all { it == "completed" }, "$id: $outcomes")
        }
    }

    @Test
    fun `a declined card fails the payment and the order, and nothing after it starts`() {
        val outcomes = run("order-processing?fail=true").outcomes

        assertEquals("failed", outcomes["PaymentService.processPayment"])
        assertEquals("failed", outcomes["OrderService.processOrder"])
        assertEquals("completed", outcomes["InventoryService.checkStock"])
    }

    @Test
    fun `the first welcome email fails, the second is sent, and the registration completes`() {
        val outcomes = run("user-registration?failEmail=true").outcomes

        assertEquals("failed", outcomes["EmailService.attempt-1"])
        assertEquals(
            storyLabels.getValue("user-registration?failEmail=true") - "EmailService.attempt-1",
            outcomes
                .filterValues {
                    it ==
                        "completed"
                }.keys,
        )
    }

    /**
     * Whether the time limit beats the answer here depends on 100 ms of real time, in both runs; the exact stories are on
     * virtual time. Either way the report goes out with its four sections.
     */
    @Test
    fun `the report goes out whether or not the analytics api answers in time`() {
        for (id in listOf("report-generation", "report-generation?timeout=true")) {
            assertEquals("completed", run(id).outcomes["ReportService.generateMonthlyReport"], id)
            val sections = runBlocking { withTimeout(5.seconds) { reports.getValue(id).await() } }
            assertEquals(listOf("users", "transactions", "analytics", "stock"), sections.map { it.substringBefore(':') }, id)
        }
    }

    companion object {
        /** Well below 1 so the runs take seconds here, well above 0 so their moments stay apart. */
        private const val SCALE = 0.1

        private val runs = ConcurrentHashMap<String, Run>()
        private val virtualRuns = ConcurrentHashMap<String, VirtualDemoRun>()

        /** The sections each real-time report run reported, by run id. */
        private val reports =
            mapOf("report-generation" to CompletableDeferred<List<String>>(), "report-generation?timeout=true" to CompletableDeferred())

        private val order =
            setOf(
                "OrderService.processOrder",
                "OrderValidator.validate",
                "InventoryService.checkStock",
                "PaymentService.processPayment",
            )
        private val registration =
            setOf(
                "UserService.register",
                "InputValidator.validateUserData",
                "UserRepository.checkExists",
                "UserRepository.createUser",
                "ProfileService.createProfile",
                "SettingsService.createDefaults",
                "AvatarService.generateDefault",
                "EmailService.sendWelcome",
                "EmailService.attempt-1",
                "SlackService.notifyTeam",
                "AnalyticsService.trackSignup",
            )
        private val report =
            setOf(
                "ReportService.generateMonthlyReport",
                "UserApi.fetchActiveUsers",
                "TransactionApi.fetchTransactions",
                "AnalyticsApi.fetchMetrics",
                "AnalyticsApi.request",
                "InventoryApi.fetchStockLevels",
                "DataProcessor.aggregateData",
                "DataProcessor.calculateRevenue",
                "DataProcessor.calculateGrowth",
                "DataProcessor.generateCharts",
                "PdfGenerator.createReport",
                "PdfGenerator.renderHeader",
                "PdfGenerator.renderCharts",
                "PdfGenerator.renderTables",
                "PdfGenerator.finalize",
                "S3Service.uploadReport",
                "EmailService.sendReportEmail",
                "SlackService.notifyChannel",
            )

        private val storyLabels =
            mapOf(
                "order-processing" to
                    order +
                    setOf(
                        "Database.saveOrder",
                        "EmailService.sendConfirmation",
                        "SmsService.sendSms",
                        "AnalyticsService.trackPurchase",
                    ),
                "order-processing?fail=true" to order,
                "user-registration" to registration,
                "user-registration?failEmail=true" to registration + "EmailService.attempt-2",
                "report-generation" to report,
                "report-generation?timeout=true" to report,
            )

        @JvmStatic
        fun runIds(): List<String> = storyLabels.keys.toList()
    }
}
