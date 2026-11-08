package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Real life demos' stories, on virtual time: who starts and ends when, under which parent and in which state,
 * down to the scripted millisecond, for each demo and for its failure option. These demos have no locks, so their
 * order follows from `join`, `await` and the analytics time limit; virtual time also makes the times exact and the
 * one race (the time limit against the analytics answer) certain. [RealLifeDemoPacingTest] checks their pacing on
 * real time.
 */
class RealLifeStoriesTest {
    /** One coroutine's life: its parent, when it started and ended (scripted ms) and how it ended. */
    private data class Life(
        val parent: String?,
        val start: Long,
        val end: Long,
        val how: String,
    )

    /** One run of a demo on virtual time. */
    private class Story(
        private val run: VirtualDemoRun,
    ) {
        val events: List<VizEvent> = run.events
        val scriptedMillis = run.scriptedMillis
        private val created = events.filterIsInstance<CoroutineCreated>()
        private val labelOf = created.associate { it.coroutineId to it.label }

        /** Scripted ms from the demo's first event, so several stories can share one test's virtual clock. */
        private val start = run.at(events.first())

        fun at(event: VizEvent) = run.at(event) - start

        /** Every coroutine's life, by label. */
        val lives: Map<String, Life>
            get() =
                created.associate { c ->
                    val own = events.filterIsInstance<CoroutineEvent>().filter { it.coroutineId == c.coroutineId }
                    val started = own.first { it is CoroutineStarted }
                    val terminal = own.last { it is CoroutineCompleted || it is CoroutineCancelled || it is CoroutineFailed }
                    val how =
                        when (terminal) {
                            is CoroutineCompleted -> "completed"
                            is CoroutineCancelled -> "cancelled"
                            else -> "failed"
                        }
                    c.label!! to Life(labelOf[c.parentCoroutineId], at(started), at(terminal), how)
                }

        fun failure(label: String) = events.filterIsInstance<CoroutineFailed>().single { it.label == label }

        /** What [label] is suspended in at scripted [millis] ("delay", "await"), or null if it is not suspended. */
        fun suspendedIn(
            label: String,
            millis: Long,
        ): String? {
            val own = events.filterIsInstance<CoroutineEvent>().filter { it.label == label && at(it) <= millis }
            return (own.lastOrNull { it is CoroutineSuspended || it is CoroutineResumed } as? CoroutineSuspended)?.reason
        }

        /** The scripted ms of [label]'s suspensions in a step, in order. */
        fun steps(label: String) =
            events.filterIsInstance<CoroutineSuspended>().filter { it.label == label && it.reason == "delay" }.map { at(it) }
    }

    @Test
    fun `anna's order is checked, paid and saved one step at a time, then three notifications go out at once`() =
        runTest {
            val story = runStory { session, context -> RealLifeScenarios.runOrderProcessing(session, context) }
            val root = "OrderService.processOrder"

            assertEquals(
                mapOf(
                    root to Life(null, 0, 14_500, "completed"),
                    "OrderValidator.validate" to Life(root, 0, 3_500, "completed"),
                    "InventoryService.checkStock" to Life(root, 3_500, 6_000, "completed"),
                    "PaymentService.processPayment" to Life(root, 6_000, 10_000, "completed"),
                    "Database.saveOrder" to Life(root, 10_000, 11_500, "completed"),
                    "EmailService.sendConfirmation" to Life(root, 11_500, 14_500, "completed"),
                    "SmsService.sendSms" to Life(root, 11_500, 13_500, "completed"),
                    "AnalyticsService.trackPurchase" to Life(root, 11_500, 12_500, "completed"),
                ),
                story.lives,
            )
            assertEquals(listOf(0L, 1_000, 2_000), story.steps("OrderValidator.validate"))
            assertEquals(listOf(6_000L, 7_000, 9_000), story.steps("PaymentService.processPayment"))
            assertEquals(emptyList(), story.steps(root), "the root only starts its steps")
            assertEquals(14_500, story.scriptedMillis)
        }

    @Test
    fun `a declined card fails the payment and with it the order, so nothing is saved or sent`() =
        runTest {
            val story = runStory { session, context -> RealLifeScenarios.runOrderProcessing(session, context, cardDeclined = true) }
            val root = "OrderService.processOrder"

            assertEquals(
                mapOf(
                    root to Life(null, 0, 10_000, "failed"),
                    "OrderValidator.validate" to Life(root, 0, 3_500, "completed"),
                    "InventoryService.checkStock" to Life(root, 3_500, 6_000, "completed"),
                    "PaymentService.processPayment" to Life(root, 6_000, 10_000, "failed"),
                ),
                story.lives,
            )
            assertEquals("PaymentDeclined", story.failure("PaymentService.processPayment").exceptionType)
            assertEquals("PaymentDeclined", story.failure(root).exceptionType, "the order fails with the payment's own exception")
            assertEquals(10_000, story.scriptedMillis)
        }

    @Test
    fun `ben's account is created, set up three ways at once, and welcomed`() =
        runTest {
            val story = runStory { session, context -> RealLifeScenarios.runUserRegistration(session, context) }
            val root = "UserService.register"

            val firstTry = Life("EmailService.sendWelcome", 9_000, 11_500, "completed")
            assertEquals(registrationUntil(9_000) + welcome(attempts = listOf(firstTry), end = 11_500), story.lives)
            assertEquals(listOf(9_000L, 9_500), story.steps("EmailService.attempt-1"))
            assertEquals(emptyList(), story.steps(root), "the root only starts its steps")
            assertEquals(11_500, story.scriptedMillis)
        }

    @Test
    fun `the mail server times out once, and the welcome email is sent on the second try`() =
        runTest {
            val story = runStory { session, context -> RealLifeScenarios.runUserRegistration(session, context, firstEmailTimesOut = true) }
            val send = "EmailService.sendWelcome"

            assertEquals(
                registrationUntil(9_000) +
                    welcome(
                        attempts = listOf(Life(send, 9_000, 12_000, "failed"), Life(send, 13_000, 15_500, "completed")),
                        end = 15_500,
                    ),
                story.lives,
            )
            assertEquals("SmtpTimeout", story.failure("EmailService.attempt-1").exceptionType)
            assertEquals(listOf(12_000L), story.steps(send), "sendWelcome waits 1 s before it tries again")
            assertEquals(listOf(13_000L, 13_500), story.steps("EmailService.attempt-2"))
            assertEquals(15_500, story.scriptedMillis)
        }

    @Test
    fun `the monthly report fetches four sources at once, adds them up, writes the pdf and delivers it`() =
        runTest {
            val sections = CompletableDeferred<List<String>>()
            val story = runStory { session, context -> RealLifeScenarios.runReportGeneration(session, context) { sections.complete(it) } }

            assertEquals(report(analyticsEnd = 3_000, request = Life("AnalyticsApi.fetchMetrics", 0, 3_000, "completed")), story.lives)
            assertEquals(
                listOf("users: 1,234 active", "transactions: 5,678", "analytics: 3 metrics", "stock: 412 products"),
                sections.await(),
            )
            assertEquals("await", story.suspendedIn("ReportService.generateMonthlyReport", 1_000))
            assertEquals(15_000, story.scriptedMillis)
        }

    @Test
    fun `the analytics api does not answer within 4 s, so its request is cancelled and the report goes out without it`() =
        runTest {
            val sections = CompletableDeferred<List<String>>()
            val story =
                runStory { session, context ->
                    RealLifeScenarios.runReportGeneration(session, context, analyticsTimesOut = true) { sections.complete(it) }
                }

            assertEquals(report(analyticsEnd = 4_000, request = Life("AnalyticsApi.fetchMetrics", 0, 4_000, "cancelled")), story.lives)
            assertEquals(listOf(0L, 1_000, 3_000), story.steps("AnalyticsApi.request"), "it would have answered at 5 s")
            assertEquals(
                listOf("users: 1,234 active", "transactions: 5,678", "analytics: not available this month", "stock: 412 products"),
                sections.await(),
            )
            assertEquals(15_000, story.scriptedMillis)
        }

    /**
     * Each guide's picture shows its demo's tree at one moment. Its caption says what each coroutine is doing then:
     * true here, at the picture's moment, from the events.
     */
    @Test
    fun `each picture's caption is true at its moment`() =
        runTest {
            val order = runStory { session, context -> RealLifeScenarios.runOrderProcessing(session, context) }
            assertEquals("delay", order.suspendedIn("EmailService.sendConfirmation", 12_750), "the email is being sent")
            assertEquals("delay", order.suspendedIn("SmsService.sendSms", 12_750), "the SMS is being sent")
            assertNull(order.suspendedIn("OrderService.processOrder", 12_750), "processOrder runs no step of its own")

            val signup = runStory { session, context -> RealLifeScenarios.runUserRegistration(session, context) }
            assertEquals("delay", signup.suspendedIn("ProfileService.createProfile", 8_500), "the profile is being created")
            assertNull(signup.suspendedIn("UserService.register", 8_500), "register is active, waiting in joinAll")

            val monthly = runStory { session, context -> RealLifeScenarios.runReportGeneration(session, context) }
            assertEquals(
                "await",
                monthly.suspendedIn("ReportService.generateMonthlyReport", 3_750),
                "the report waits for the transactions",
            )
            assertEquals("delay", monthly.suspendedIn("TransactionApi.fetchTransactions", 3_750))
        }

    /** The registration up to [setupDone]: three checks one after the other, then the setup three ways at once. */
    private fun registrationUntil(setupDone: Long): Map<String, Life> {
        val root = "UserService.register"
        return mapOf(
            "InputValidator.validateUserData" to Life(root, 0, 2_000, "completed"),
            "UserRepository.checkExists" to Life(root, 2_000, 4_000, "completed"),
            "UserRepository.createUser" to Life(root, 4_000, 6_500, "completed"),
            "ProfileService.createProfile" to Life(root, 6_500, setupDone, "completed"),
            "SettingsService.createDefaults" to Life(root, 6_500, 8_000, "completed"),
            "AvatarService.generateDefault" to Life(root, 6_500, 7_500, "completed"),
        )
    }

    /** The notifications from 9 s: the welcome email's tries ([attempts], in order), Slack and analytics; the root ends at [end]. */
    private fun welcome(
        attempts: List<Life>,
        end: Long,
    ): Map<String, Life> {
        val root = "UserService.register"
        return mapOf(
            root to Life(null, 0, end, "completed"),
            "EmailService.sendWelcome" to Life(root, 9_000, end, "completed"),
            "SlackService.notifyTeam" to Life(root, 9_000, 11_000, "completed"),
            "AnalyticsService.trackSignup" to Life(root, 9_000, 10_000, "completed"),
        ) + attempts.mapIndexed { i, life -> "EmailService.attempt-${i + 1}" to life }
    }

    /** The monthly report: the same every run, except when the analytics fetch ends and how its [request] ends. */
    private fun report(
        analyticsEnd: Long,
        request: Life,
    ): Map<String, Life> {
        val root = "ReportService.generateMonthlyReport"
        val pdf = "PdfGenerator.createReport"
        val aggregate = "DataProcessor.aggregateData"
        return mapOf(
            root to Life(null, 0, 15_000, "completed"),
            "UserApi.fetchActiveUsers" to Life(root, 0, 2_500, "completed"),
            "TransactionApi.fetchTransactions" to Life(root, 0, 4_500, "completed"),
            "AnalyticsApi.fetchMetrics" to Life(root, 0, analyticsEnd, "completed"),
            "AnalyticsApi.request" to request,
            "InventoryApi.fetchStockLevels" to Life(root, 0, 2_000, "completed"),
            aggregate to Life(root, 4_500, 7_000, "completed"),
            "DataProcessor.calculateRevenue" to Life(aggregate, 4_500, 6_000, "completed"),
            "DataProcessor.calculateGrowth" to Life(aggregate, 4_500, 6_500, "completed"),
            "DataProcessor.generateCharts" to Life(aggregate, 4_500, 7_000, "completed"),
            pdf to Life(root, 7_000, 12_500, "completed"),
            "PdfGenerator.renderHeader" to Life(pdf, 7_000, 8_000, "completed"),
            "PdfGenerator.renderCharts" to Life(pdf, 8_000, 10_000, "completed"),
            "PdfGenerator.renderTables" to Life(pdf, 10_000, 11_500, "completed"),
            "PdfGenerator.finalize" to Life(pdf, 11_500, 12_500, "completed"),
            "SlackService.notifyChannel" to Life(root, 12_500, 13_500, "completed"),
            "EmailService.sendReportEmail" to Life(root, 12_500, 14_500, "completed"),
            "S3Service.uploadReport" to Life(root, 12_500, 15_000, "completed"),
        )
    }

    /** Runs a demo on this test's virtual time and returns its story once every coroutine has ended. */
    private suspend fun TestScope.runStory(start: suspend (VizSession, CoroutineContext) -> Job): Story =
        Story(runOnVirtualTime("real-life-story", start))
}
