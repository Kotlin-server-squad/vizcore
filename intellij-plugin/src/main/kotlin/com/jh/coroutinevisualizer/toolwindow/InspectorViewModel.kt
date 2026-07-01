package com.jh.coroutinevisualizer.toolwindow

import com.jh.coroutinevisualizer.api.HierarchyNodeDto
import com.jh.coroutinevisualizer.api.TimelineDto
import com.jh.coroutinevisualizer.api.TimelineEventDto
import java.util.Locale

/** A resolved source location (file:line) plus the reason a coroutine reached it. */
data class SourceRef(
    val fileName: String?,
    val lineNumber: Int?,
    val reason: String?,
)

/**
 * Pure presentation model for the selected-coroutine inspector. All mapping and duration formatting
 * lives here (no Swing) so the logic stays unit-testable. Durations from [TimelineDto] are in
 * NANOSECONDS; the formatter converts to approximate ms/s for display.
 */
data class InspectorViewModel(
    val name: String,
    val state: String,
    val identity: String,
    val suspendedAt: SourceRef?,
    val launchedAt: SourceRef?,
    val activeLabel: String,
    val suspendedLabel: String,
    val totalLabel: String,
) {
    companion object {
        private const val DASH = "—"
        private const val NANOS_PER_MS = 1_000_000L
        private const val MS_PER_SECOND = 1000L

        /**
         * Approximate human duration from nanoseconds: null → "—"; below one second → "~340ms";
         * otherwise one decimal second → "~1.2s".
         */
        fun formatApproxNanos(nanos: Long?): String {
            if (nanos == null) return DASH
            val ms = nanos / NANOS_PER_MS
            return if (ms < MS_PER_SECOND) {
                "~${ms}ms"
            } else {
                "~" + String.format(Locale.ROOT, "%.1f", ms.toDouble() / MS_PER_SECOND) + "s"
            }
        }

        /**
         * Builds a view model from the (optional) timeline and hierarchy node. Falls back to node
         * fields when the timeline is absent so a coroutine can always be inspected.
         */
        fun from(
            timeline: TimelineDto?,
            node: HierarchyNodeDto?,
        ): InspectorViewModel {
            val name = timeline?.name ?: node?.name ?: DASH
            val state = timeline?.state ?: node?.state ?: DASH
            val identity =
                listOfNotNull(
                    node?.jobId?.takeIf { it.isNotBlank() }?.let { "job $it" },
                    node?.scopeId?.takeIf { it.isNotBlank() }?.let { "scope $it" },
                    node?.dispatcherName,
                ).joinToString(" · ")

            val events = timeline?.events.orEmpty()
            return InspectorViewModel(
                name = name,
                state = state,
                identity = identity,
                suspendedAt = suspendedRef(events),
                launchedAt = launchedRef(events),
                activeLabel = formatApproxNanos(timeline?.activeDuration),
                suspendedLabel = formatApproxNanos(timeline?.suspendedDuration),
                totalLabel = formatApproxNanos(timeline?.totalDuration),
            )
        }

        /** Last event carrying a suspension point → where the coroutine is currently suspended. */
        private fun suspendedRef(events: List<TimelineEventDto>): SourceRef? =
            events.lastOrNull { it.suspensionPoint != null }?.let { event ->
                val point = event.suspensionPoint ?: return@let null
                SourceRef(point.fileName, point.lineNumber, point.reason.ifBlank { event.reason })
            }

        /** First creation/start event with a suspension point → best-effort launch site. */
        private fun launchedRef(events: List<TimelineEventDto>): SourceRef? =
            events
                .firstOrNull {
                    it.suspensionPoint != null && (it.kind == "CREATED" || it.kind == "STARTED")
                }?.let { event ->
                    val point = event.suspensionPoint ?: return@let null
                    SourceRef(point.fileName, point.lineNumber, point.reason.ifBlank { event.reason })
                }
    }
}
