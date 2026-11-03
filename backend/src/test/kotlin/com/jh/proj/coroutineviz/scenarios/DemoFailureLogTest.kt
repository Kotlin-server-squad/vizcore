package com.jh.proj.coroutineviz.scenarios

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.delay
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
    fun `any other failure in a demo is logged as an error with its stack trace`() {
        val event = failureLogged { throw IllegalArgumentException("a real bug") }

        assertEquals(Level.ERROR, event.level)
        assertEquals("a real bug", assertNotNull(event.throwableProxy).message)
    }
}
