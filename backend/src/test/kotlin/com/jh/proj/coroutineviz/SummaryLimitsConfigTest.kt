package com.jh.proj.coroutineviz

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.jh.proj.coroutineviz.health.SummaryLimits
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.yaml.YamlConfig
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `health.summaries.*`: the summary table's limits from the config, checked at startup. */
class SummaryLimitsConfigTest {
    private fun config(vararg pairs: Pair<String, String>) = MapApplicationConfig(*pairs)

    private fun target(v: String) = "health.summaries.fullSummaryTarget" to v

    private fun capacity(v: String) = "health.summaries.trackedCapacity" to v

    /** The limits read from [pairs] and the warnings logged while reading them. */
    private fun read(vararg pairs: Pair<String, String>): Pair<SummaryLimits, List<String>> {
        val logger = LoggerFactory.getLogger(SummaryLimitsConfig::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val limits = SummaryLimitsConfig.fromConfig(config(*pairs))
            return limits to appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `nothing configured gives the defaults`() {
        assertEquals(SummaryLimits.DEFAULT, SummaryLimitsConfig.fromConfig(MapApplicationConfig()))
    }

    @Test
    fun `application yaml gives the defaults`() {
        assertEquals(SummaryLimits.DEFAULT, SummaryLimitsConfig.fromConfig(YamlConfig("application.yaml")!!))
    }

    @Test
    fun `reads both limits`() {
        val (limits, warnings) = read(target("2000"), capacity("5000"))
        assertEquals(SummaryLimits(fullSummaryTarget = 2000, trackedCapacity = 5000), limits)
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `a value that is not a positive whole number falls back to its default with a warning`() {
        listOf("abc", "0", "-5", "1.5").forEach { bad ->
            val (limits, warnings) = read(target(bad), capacity(bad))
            assertEquals(SummaryLimits.DEFAULT, limits, "for '$bad'")
            assertEquals(2, warnings.size, "for '$bad': $warnings")
            assertTrue(warnings.any { "HEALTH_FULL_SUMMARY_TARGET" in it }, "names the variable: $warnings")
            assertTrue(warnings.any { "HEALTH_TRACKED_CAPACITY" in it }, "names the variable: $warnings")
        }
    }

    @Test
    fun `a blank value, as an empty environment variable gives, is the default without a warning`() {
        val (limits, warnings) = read(target(" "), capacity(""))
        assertEquals(SummaryLimits.DEFAULT, limits)
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `a target above the capacity is lowered to the capacity with a warning`() {
        val (limits, warnings) = read(target("80000"), capacity("20000"))
        assertEquals(SummaryLimits(fullSummaryTarget = 20_000, trackedCapacity = 20_000), limits)
        assertEquals(1, warnings.size, warnings.toString())
        assertTrue("80000" in warnings.single() && "20000" in warnings.single(), warnings.single())
    }

    @Test
    fun `a capacity below the default target lowers the unset target without a warning`() {
        val (limits, warnings) = read(capacity("1000"))
        assertEquals(SummaryLimits(fullSummaryTarget = 1000, trackedCapacity = 1000), limits)
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `a value that is not a single value falls back to its default with a warning`() {
        val logger = LoggerFactory.getLogger(SummaryLimitsConfig::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val config = MapApplicationConfig().apply { put("health.summaries.trackedCapacity", listOf("1", "2")) }
            assertEquals(SummaryLimits.DEFAULT, SummaryLimitsConfig.fromConfig(config))
            assertEquals(1, appender.list.count { it.level == Level.WARN })
        } finally {
            logger.detachAppender(appender)
        }
    }
}
