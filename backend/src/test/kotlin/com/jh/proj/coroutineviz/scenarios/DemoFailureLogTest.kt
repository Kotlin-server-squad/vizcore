package com.jh.proj.coroutineviz.scenarios

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Several demos fail on purpose, and the session shows that failure. Those failures are logged in
 * one line; any other failure in a demo is a bug and keeps its stack trace at ERROR.
 */
class DemoFailureLogTest {
    private fun failureLogged(fail: () -> Nothing): ILoggingEvent =
        runBlocking {
            val logger = LoggerFactory.getLogger("DemoRuns") as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            val session = VizSession("demo-failure-log")
            try {
                val run = assertNotNull(DemoRuns(maxConcurrent = 1).tryStart())
                withContext(run) {
                    session.demoScope().vizLaunch("failing") { fail() }
                }
                withTimeout(5.seconds) {
                    while (appender.list.isEmpty()) delay(10)
                }
                appender.list.single()
            } finally {
                logger.detachAppender(appender)
                session.close()
            }
        }

    @Test
    fun `a failure the demo throws on purpose is logged in one line`() {
        val event = failureLogged { throw IllegalStateException("Payment declined").intendedByDemo() }

        assertEquals(Level.INFO, event.level)
        assertEquals(true, event.formattedMessage.contains("Payment declined"), event.formattedMessage)
        assertNull(event.throwableProxy, "an intended failure needs no stack trace")
    }

    @Test
    fun `an intended failure that also carries an unexpected one is logged as an error`() {
        // Kotlin adds further child and cleanup failures to the first as suppressed exceptions.
        val event =
            failureLogged {
                throw demoFailure("Payment declined").apply { addSuppressed(NullPointerException("a real bug")) }
            }

        assertEquals(Level.ERROR, event.level)
        assertNotNull(event.throwableProxy)
    }

    @Test
    fun `any other failure in a demo is logged as an error with its stack trace`() {
        val event = failureLogged { throw IllegalArgumentException("a real bug") }

        assertEquals(Level.ERROR, event.level)
        assertEquals("a real bug", assertNotNull(event.throwableProxy).message)
    }

    /**
     * The real-life demos' failure options (`?fail=true`, `?timeout=true`) end the demo with a
     * failure on purpose; the failing child cancels its parent's join, which is no error either.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = ["order-processing", "report-generation"])
    fun `a real-life demo's designed failure logs no error`(demo: String) =
        runBlocking {
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            root.addAppender(appender)
            val session = VizSession("demo-designed-failure")
            try {
                val run = assertNotNull(DemoRuns(maxConcurrent = 1).tryStart())
                withContext(run) {
                    val job =
                        when (demo) {
                            "order-processing" -> ScenarioRunner.runOrderProcessingScenario(session, shouldFail = true)
                            else -> ScenarioRunner.runReportGenerationScenario(session, shouldTimeout = true)
                        }
                    withTimeout(60.seconds) { listOf(job).joinAll() }
                }
                withTimeout(5.seconds) {
                    while (appender.list.none { it.formattedMessage.contains("failed on purpose") }) delay(10)
                }
                assertEquals(emptyList(), appender.list.filter { it.level == Level.ERROR }.map { it.formattedMessage })
            } finally {
                root.detachAppender(appender)
                session.close()
            }
        }
}
