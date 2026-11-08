package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.wrappers.VizScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The Real life demos: an order, a sign-up and a monthly report, as a backend runs them, with service-style
 * coroutine names (`PaymentService.processPayment` …) that the session's pipeline views read. Like the other groups
 * ([PatternScenarios], [SyncScenarios]) their steps are paced with [pause] so a person can follow them: 0.5-3 s a
 * step, 10-30 s a demo, at the configured `scenarios.timeScale`. Each returns the demo's root coroutine at once; the
 * root only starts its children and waits for those the next step needs. Each takes a [CoroutineContext] so a test
 * can run it on virtual time.
 *
 * Each demo has a failure option, and each shows what really happens in structured concurrency:
 * - Order Processing, a declined card: a failed `launch` child cancels its parent, which fails with the child's
 *   exception, so nothing after the payment starts. (`join()` does not throw the child's exception; a try/catch
 *   around it would never see it.)
 * - User Registration, a mail server that times out once: each try runs inside `coroutineScope`, which hands a
 *   failed child's exception back to the caller instead of cancelling it, so the caller can catch it and try again.
 * - Report Generation, an analytics API that does not answer in time: `withTimeoutOrNull` cancels the call when time
 *   is up and returns null, and the report goes out without analytics.
 */
object RealLifeScenarios {
    private val logger = LoggerFactory.getLogger(RealLifeScenarios::class.java)

    /** The bank turned the card down. Thrown on purpose by the Order Processing demo ([intendedByDemo]). */
    private class PaymentDeclined : RuntimeException("Payment declined: insufficient funds")

    /** The mail server did not answer. Thrown, and caught, by the User Registration demo. */
    private class SmtpTimeout : RuntimeException("SMTP server timeout")

    /**
     * Order processing, as Anna buying running shoes: the order is checked (0-3.5 s), the warehouse asked for her size
     * (3.5-6 s), her card charged (6-10 s: the payment provider, her confirmation in the banking app, the bank's answer)
     * and the order saved (10-11.5 s), each step joined before the next. Then `processOrder` starts the confirmation
     * email, the SMS and the analytics event at once and waits for them; it completes with the email at 14.5 s.
     *
     * With [cardDeclined] the bank turns the card down at 10 s: `processPayment` fails, and so does `processOrder`,
     * which was waiting for it in `join()`. Nothing is saved and nothing is sent.
     */
    suspend fun runOrderProcessing(
        session: VizSession,
        context: CoroutineContext = EmptyCoroutineContext,
        cardDeclined: Boolean = false,
    ): Job =
        coroutineScope {
            logger.info("Starting Order Processing scenario (cardDeclined=$cardDeclined) in session: ${session.sessionId}")

            val viz = session.demoScope(context)

            viz.vizLaunch("OrderService.processOrder") {
                vizLaunch("OrderValidator.validate") {
                    pause(1000) // the items in the basket
                    pause(1000) // their prices
                    pause(1500) // the delivery address
                }.join()
                vizLaunch("InventoryService.checkStock") {
                    pause(2500) // ask the warehouse for her size
                }.join()
                // A declined card fails this child, and its failure cancels processOrder here in join(): the order
                // stops, and processOrder fails with PaymentDeclined. join() itself never throws that exception.
                vizLaunch("PaymentService.processPayment") {
                    pause(1000) // contact the payment provider
                    pause(2000) // Anna confirms in her banking app
                    pause(1000) // the bank's answer
                    if (cardDeclined) throw PaymentDeclined().intendedByDemo()
                }.join()
                vizLaunch("Database.saveOrder") {
                    pause(1500)
                }.join()
                // The notifications go out at once; processOrder waits for its children.
                vizLaunch("EmailService.sendConfirmation") {
                    pause(1500) // write it
                    pause(1500) // send it
                }
                vizLaunch("SmsService.sendSms") { pause(2000) }
                vizLaunch("AnalyticsService.trackPurchase") { pause(1000) }
            }
        }

    /**
     * User registration, as Ben joining a recipe app: his details are checked (0-2 s), the email looked up (2-4 s) and
     * the account created (4-6.5 s). Then his profile, default settings and avatar are set up at once, and `register`
     * waits for all three (`joinAll`, 6.5-9 s) before it starts the notifications: the welcome email, a Slack message
     * to the team and an analytics event. It completes with the email at 11.5 s.
     *
     * The welcome email runs each try in a child, `EmailService.attempt-1`, `-2`. With [firstEmailTimesOut] the mail
     * server does not answer the first try: it fails at 12 s, `sendWelcome` catches the timeout, waits 1 s and tries
     * again; the second try sends the email and the registration completes at 15.5 s.
     */
    suspend fun runUserRegistration(
        session: VizSession,
        context: CoroutineContext = EmptyCoroutineContext,
        firstEmailTimesOut: Boolean = false,
    ): Job =
        coroutineScope {
            logger.info("Starting User Registration scenario (firstEmailTimesOut=$firstEmailTimesOut) in session: ${session.sessionId}")

            val viz = session.demoScope(context)

            viz.vizLaunch("UserService.register") {
                vizLaunch("InputValidator.validateUserData") {
                    pause(1000) // the email's format
                    pause(1000) // the password rules
                }.join()
                vizLaunch("UserRepository.checkExists") {
                    pause(2000) // look up the email
                }.join()
                vizLaunch("UserRepository.createUser") {
                    pause(1000) // hash the password
                    pause(1500) // save the account
                }.join()
                listOf(
                    vizLaunch("ProfileService.createProfile") { pause(2500) },
                    vizLaunch("SettingsService.createDefaults") { pause(1500) },
                    vizLaunch("AvatarService.generateDefault") { pause(1000) },
                ).joinAll()
                // The notifications go out at once; register waits for its children.
                vizLaunch("EmailService.sendWelcome") { sendWelcome(timesOutFirst = firstEmailTimesOut) }
                vizLaunch("SlackService.notifyTeam") { pause(2000) }
                vizLaunch("AnalyticsService.trackSignup") { pause(1000) }
            }
        }

    /**
     * Sends the welcome email in up to [EMAIL_TRIES] tries, each in a child of its own so the tree shows it. A try
     * runs inside `coroutineScope`, which hands its failure back here instead of cancelling `sendWelcome`; only a
     * [SmtpTimeout] is caught (cancellation passes through), and after it `sendWelcome` waits 1 s. A failure of the
     * last try leaves `sendWelcome` and fails the registration.
     */
    private suspend fun VizScope.sendWelcome(timesOutFirst: Boolean) {
        for (attempt in 1..EMAIL_TRIES) {
            try {
                coroutineScope {
                    vizLaunch("EmailService.attempt-$attempt") {
                        pause(500) // connect to the mail server
                        if (timesOutFirst && attempt == 1) {
                            pause(2500) // wait for an answer that never comes
                            throw SmtpTimeout()
                        }
                        pause(2000) // send the email
                    }
                }
                return
            } catch (e: SmtpTimeout) {
                if (attempt == EMAIL_TRIES) throw e
                logger.debug("Welcome email, try $attempt: ${e.message}; trying again in 1 s")
                pause(1000)
            }
        }
    }

    /**
     * Report generation, as a shop's monthly sales report: four sources are fetched at once (`async`) and the report
     * awaits them in order: the users (2.5 s), the transactions (4.5 s), the analytics (3 s), the stock (2 s). The
     * analytics API gets at most 4 s (`withTimeoutOrNull`); its call runs in a child, `AnalyticsApi.request`. Then
     * the numbers are added up, three calculations at once (4.5-7 s), the PDF written part by part (7-12.5 s), and
     * the report delivered three ways at once; it completes with the upload at 15 s. [onReport] gets its sections.
     *
     * With [analyticsTimesOut] the analytics API would answer only at 5 s: at 4 s the time limit cancels the request,
     * `fetchMetrics` gets null, and the report goes out with "analytics: not available this month".
     */
    suspend fun runReportGeneration(
        session: VizSession,
        context: CoroutineContext = EmptyCoroutineContext,
        analyticsTimesOut: Boolean = false,
        onReport: (sections: List<String>) -> Unit = {},
    ): Job =
        coroutineScope {
            logger.info("Starting Report Generation scenario (analyticsTimesOut=$analyticsTimesOut) in session: ${session.sessionId}")

            val viz = session.demoScope(context)
            var report: List<String> = emptyList()

            viz
                .vizLaunch("ReportService.generateMonthlyReport") {
                    val sources =
                        listOf(
                            vizAsync("UserApi.fetchActiveUsers") {
                                pause(1000) // query
                                pause(1500) // count
                                "users: 1,234 active"
                            },
                            vizAsync("TransactionApi.fetchTransactions") {
                                pause(2000) // first page
                                pause(2500) // second page
                                "transactions: 5,678"
                            },
                            vizAsync("AnalyticsApi.fetchMetrics") {
                                val metrics = withTimeoutOrNull(scaled(ANALYTICS_TIME_LIMIT_MILLIS)) { requestAnalytics(analyticsTimesOut) }
                                metrics ?: "analytics: not available this month"
                            },
                            vizAsync("InventoryApi.fetchStockLevels") {
                                pause(2000)
                                "stock: 412 products"
                            },
                        )
                    report = sources.map { it.await() }

                    vizLaunch("DataProcessor.aggregateData") {
                        vizLaunch("DataProcessor.calculateRevenue") { pause(1500) }
                        vizLaunch("DataProcessor.calculateGrowth") { pause(2000) }
                        vizLaunch("DataProcessor.generateCharts") {
                            pause(1000)
                            pause(1500)
                        }
                    }.join()
                    vizLaunch("PdfGenerator.createReport") {
                        vizLaunch("PdfGenerator.renderHeader") { pause(1000) }.join()
                        vizLaunch("PdfGenerator.renderCharts") { pause(2000) }.join()
                        vizLaunch("PdfGenerator.renderTables") { pause(1500) }.join()
                        vizLaunch("PdfGenerator.finalize") { pause(1000) }.join()
                    }.join()
                    // The deliveries go out at once; the report waits for its children.
                    vizLaunch("S3Service.uploadReport") { pause(2500) }
                    vizLaunch("EmailService.sendReportEmail") { pause(2000) }
                    vizLaunch("SlackService.notifyChannel") { pause(1000) }
                }.also { root -> root.invokeOnCompletion { if (it == null) onReport(report) } }
        }

    /** The analytics API's answer, in a child of its own; when [overloaded] it would come only after 5 s. */
    private suspend fun VizScope.requestAnalytics(overloaded: Boolean): String =
        vizAsync("AnalyticsApi.request") {
            pause(1000) // connect
            pause(2000) // wait for the answer
            if (overloaded) pause(2000) // the overloaded API is still not done
            "analytics: 3 metrics"
        }.await()

    /** How many tries the welcome email gets. */
    private const val EMAIL_TRIES = 3

    /** How long the analytics API may take, in scripted ms. */
    private const val ANALYTICS_TIME_LIMIT_MILLIS = 4000L
}
