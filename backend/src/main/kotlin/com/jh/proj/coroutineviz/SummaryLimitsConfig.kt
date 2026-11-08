package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.health.SummaryLimits
import io.ktor.server.config.ApplicationConfig
import org.slf4j.LoggerFactory

/**
 * The summary table's limits from `health.summaries.*` (spec C-2), read once at startup. Like the other settings, a bad
 * value does not stop the backend: a value that is not a positive whole number falls back to its default with a warning,
 * and a target above the capacity is lowered to the capacity (with a warning when the target was set).
 */
internal object SummaryLimitsConfig {
    private val logger = LoggerFactory.getLogger(SummaryLimitsConfig::class.java)

    private const val TARGET_KEY = "health.summaries.fullSummaryTarget"
    private const val CAPACITY_KEY = "health.summaries.trackedCapacity"

    fun fromConfig(config: ApplicationConfig): SummaryLimits {
        val target = positive(config, TARGET_KEY, "HEALTH_FULL_SUMMARY_TARGET")
        val capacity = positive(config, CAPACITY_KEY, "HEALTH_TRACKED_CAPACITY") ?: SummaryLimits.DEFAULT.trackedCapacity
        val wanted = target ?: SummaryLimits.DEFAULT.fullSummaryTarget
        if (target != null && target > capacity) {
            logger.warn(
                "{} ({}) is above {} ({}); using {} full summaries",
                TARGET_KEY,
                target,
                CAPACITY_KEY,
                capacity,
                capacity,
            )
        }
        return SummaryLimits(fullSummaryTarget = minOf(wanted, capacity), trackedCapacity = capacity)
    }

    /** The value at [key] when it is a positive whole number; null when unset or blank (silently) or anything else (warned). */
    private fun positive(
        config: ApplicationConfig,
        key: String,
        envName: String,
    ): Int? {
        val raw =
            config.propertyOrNull(key)?.let { property ->
                runCatching { property.getString().trim() }.getOrElse { "(not a single value)" }
            }
        val value = raw?.toIntOrNull()?.takeIf { it > 0 }
        if (value == null && !raw.isNullOrEmpty()) {
            logger.warn("{} ({}) '{}' is not a positive whole number; using the default", key, envName, raw)
        }
        return value
    }
}
