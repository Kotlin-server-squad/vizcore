package com.jh.coroutinevisualizer.toolwindow

import com.intellij.ui.JBColor
import java.awt.Color
import java.util.Locale

/**
 * Pure, theme-aware styling logic for coroutine tree rows. No Swing rendering lives here so the
 * decision logic (state buckets, badge text, age labels) stays unit-testable. The renderer pulls
 * colors and labels from these helpers.
 */
enum class CoroutineStateStyle {
    RUNNING,
    SUSPENDED,
    COMPLETED,
    FAILED,
    CREATED,
    OTHER,
    ;

    companion object {
        private const val MILLIS_PER_SECOND = 1000L

        /** Maps a backend state string (any case) to a style bucket. CANCELLED folds into FAILED. */
        fun of(state: String): CoroutineStateStyle =
            when (state.uppercase()) {
                "RUNNING" -> RUNNING
                "SUSPENDED" -> SUSPENDED
                "COMPLETED" -> COMPLETED
                "FAILED", "CANCELLED" -> FAILED
                "CREATED" -> CREATED
                else -> OTHER
            }

        /** Lowercased state label used as the row's state badge text. */
        fun badgeText(state: String): String = state.lowercase()

        /**
         * Approximate human age: "~120ms" below one second, otherwise "~1.2s" (one decimal).
         */
        fun ageLabel(ms: Long): String =
            if (ms < MILLIS_PER_SECOND) {
                "~${ms}ms"
            } else {
                val seconds = ms.toDouble() / MILLIS_PER_SECOND
                "~${String.format(Locale.ROOT, "%.1f", seconds)}s"
            }

        /** Theme-aware color for a style bucket, from the vizcore palette. Leaks are amber, not red. */
        fun color(style: CoroutineStateStyle): JBColor =
            when (style) {
                RUNNING -> palette(VizPalette.BLUE)
                SUSPENDED -> palette(VizPalette.AMBER)
                COMPLETED -> palette(VizPalette.GREEN)
                FAILED -> palette(VizPalette.RED)
                CREATED -> palette(VizPalette.GRAY)
                OTHER -> palette(VizPalette.GRAY)
            }

        /** Amber accent used for leak rows (never red). */
        fun leakColor(): JBColor = palette(VizPalette.AMBER)

        private fun palette(rgb: Int): JBColor = JBColor(Color(rgb), Color(rgb))
    }
}

/** vizcore palette hex values, shared by light/dark for v1. */
private object VizPalette {
    const val BLUE = 0x006FEE
    const val AMBER = 0xF5A524
    const val GREEN = 0x17C964
    const val RED = 0xF31260
    const val GRAY = 0x8B8B94
}

/**
 * Tracks the last-seen state per coroutine id so the renderer can flash a row only when its state
 * actually changed between polls. First sighting never flashes. Not thread-safe; used from the EDT.
 */
class FlashTracker {
    private val last = mutableMapOf<String, String>()

    /** True only when this id was seen before with a different state. Always records the new state. */
    fun shouldFlash(
        id: String,
        state: String,
    ): Boolean {
        val previous = last.put(id, state)
        return previous != null && previous != state
    }
}
