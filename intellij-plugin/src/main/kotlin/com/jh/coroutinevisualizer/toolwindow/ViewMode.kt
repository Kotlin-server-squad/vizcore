package com.jh.coroutinevisualizer.toolwindow

import java.util.Locale

/**
 * The two live-view cadences (D-19/D-21). LIVE polls at the settings interval and shows the graph;
 * ALL polls slowly ([ALL_INTERVAL_MS]) and hides the graph — the strip/tiles simply update less often
 * in All mode. Every decision is a pure helper so the unit gate (ViewModeTest) proves it without Swing.
 */
enum class ViewMode {
    LIVE,
    ALL,
    ;

    companion object {
        /** Slow "All" cadence within the settings 50..5000 clamp (D-19, A5). */
        const val ALL_INTERVAL_MS = 1_500L

        /** LIVE keeps the caller's live interval; ALL always drops to the slow cadence. */
        fun intervalMsFor(
            mode: ViewMode,
            liveIntervalMs: Long,
        ): Long = if (mode == ALL) ALL_INTERVAL_MS else liveIntervalMs

        /** The graph view is meaningful only for the live snapshot; All hides it (D-20). */
        fun graphEnabled(mode: ViewMode): Boolean = mode == LIVE

        /** Sketch 006-A toggle label: "All 2,841" (Locale.ROOT thousands grouping). */
        fun allLabel(n: Int): String = "All " + String.format(Locale.ROOT, "%,d", n)
    }
}
