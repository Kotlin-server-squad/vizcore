package com.jh.coroutinevisualizer.toolwindow

import com.jh.coroutinevisualizer.api.HierarchyNodeDto
import com.jh.coroutinevisualizer.api.SuspensionPointDto
import com.jh.coroutinevisualizer.api.TimelineDto
import com.jh.coroutinevisualizer.api.TimelineEventDto
import java.util.Locale

/** A resolved source location (file:line) plus the reason a coroutine reached it. */
data class SourceRef(
    val fileName: String?,
    val lineNumber: Int?,
    val reason: String?,
)

/** A single lifecycle event, with its time expressed relative to the first event. */
data class InspectorEvent(
    val kind: String,
    val reason: String?,
    val relativeLabel: String,
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
    val jobId: String?,
    val scopeId: String?,
    val activeChildrenCount: Int,
    val childrenCount: Int,
    val running: Boolean,
    val suspendedAt: SourceRef?,
    val launchedAt: SourceRef?,
    val activeLabel: String,
    val suspendedLabel: String,
    val totalLabel: String,
    val lifetimeLabel: String,
    val threadName: String?,
    val dispatcherName: String?,
    val exceptionType: String?,
    val exceptionMessage: String?,
    val events: List<InspectorEvent>,
    val suspensionHistory: List<SourceRef>,
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
         * Builds a view model from the (optional) timeline and hierarchy node. Durations come from the
         * timeline; source refs are fresh-first (timeline events) with a durable node fallback
         * ([HierarchyNodeDto.creationPoint] / [HierarchyNodeDto.lastSuspensionPoint]) so Launched at /
         * Suspended at / jump targets survive the EventStore's 10k DROP_OLDEST eviction. A running
         * coroutine gets a live lifetime computed from [HierarchyNodeDto.createdAtNanos]; [nowNanos] is
         * injectable so that computation is deterministic under test.
         */
        fun from(
            timeline: TimelineDto?,
            node: HierarchyNodeDto?,
            nowNanos: Long = System.nanoTime(),
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
            // Unknown node ≠ running: without this, a long-completed coroutine whose hierarchy node
            // is unavailable would be labelled "running" (null?.completedAtNanos == null is true).
            val running = node != null && node.completedAtNanos == null
            return InspectorViewModel(
                name = name,
                state = state,
                identity = identity,
                jobId = node?.jobId?.takeIf { it.isNotBlank() },
                scopeId = node?.scopeId?.takeIf { it.isNotBlank() },
                activeChildrenCount = node?.activeChildrenCount ?: 0,
                childrenCount = node?.children?.size ?: 0,
                running = running,
                suspendedAt = suspendedRef(events) ?: node?.lastSuspensionPoint?.toSourceRef(),
                launchedAt = launchedRef(events) ?: node?.creationPoint?.toSourceRef(),
                activeLabel = formatApproxNanos(timeline?.activeDuration),
                suspendedLabel = formatApproxNanos(timeline?.suspendedDuration),
                totalLabel = formatApproxNanos(timeline?.totalDuration),
                lifetimeLabel = lifetimeLabel(running, timeline, node, nowNanos),
                threadName = node?.currentThreadName,
                dispatcherName = node?.dispatcherName,
                exceptionType = node?.exceptionType,
                exceptionMessage = node?.exceptionMessage,
                events = inspectorEvents(events),
                suspensionHistory = suspensionHistory(events),
            )
        }

        /**
         * Sequence of suspension sites over the coroutine's life: every coroutine.suspended timeline
         * event that carries a [SuspensionPointDto], in seq order, as jump-to-source targets. The
         * coroutine.created launch frame (15-08/15-09 rides it in [TimelineEventDto.suspensionPoint])
         * is excluded — "Suspended at" must mean suspended, never the launch site. This is the closest
         * thing to a suspension "stack" available from the current API — a full multi-frame trace is
         * not exposed.
         */
        private fun suspensionHistory(events: List<TimelineEventDto>): List<SourceRef> =
            events
                .sortedBy { it.seq }
                .filter { it.kind == "coroutine.suspended" }
                .mapNotNull { event ->
                    val point = event.suspensionPoint ?: return@mapNotNull null
                    val reason = point.reason.ifBlank { event.reason }
                    val label =
                        listOfNotNull(
                            point.function.takeIf { it.isNotBlank() },
                            reason?.takeIf { it.isNotBlank() },
                        ).joinToString(" · ").ifBlank { null }
                    SourceRef(point.fileName, point.lineNumber, label)
                }

        /** Lifecycle events in seq order, each labelled with time since the first event. */
        private fun inspectorEvents(events: List<TimelineEventDto>): List<InspectorEvent> {
            if (events.isEmpty()) return emptyList()
            val firstTsNanos = events.minOf { it.tsNanos }
            return events
                .sortedBy { it.seq }
                .map { event ->
                    InspectorEvent(
                        kind = event.kind,
                        reason = event.reason ?: event.suspensionPoint?.reason,
                        relativeLabel = formatApproxNanos(event.tsNanos - firstTsNanos),
                    )
                }
        }

        /**
         * Last coroutine.suspended event carrying a suspension point → where the coroutine is
         * currently suspended. The kind filter excludes the coroutine.created launch frame so the
         * inspector never captions the launch site as "Suspended at".
         */
        private fun suspendedRef(events: List<TimelineEventDto>): SourceRef? =
            events.lastOrNull { it.kind == "coroutine.suspended" && it.suspensionPoint != null }?.let { event ->
                val point = event.suspensionPoint ?: return@let null
                SourceRef(point.fileName, point.lineNumber, point.reason.ifBlank { event.reason })
            }

        /**
         * First creation/start event with a suspension point → best-effort launch site. The kinds are
         * the REAL wire strings ("coroutine.created" / "coroutine.started"); 15-08/15-09 make
         * coroutine.created carry the launch frame in [TimelineEventDto.suspensionPoint].
         */
        private fun launchedRef(events: List<TimelineEventDto>): SourceRef? =
            events
                .firstOrNull {
                    it.suspensionPoint != null &&
                        (it.kind == "coroutine.created" || it.kind == "coroutine.started")
                }?.let { event ->
                    val point = event.suspensionPoint ?: return@let null
                    SourceRef(point.fileName, point.lineNumber, point.reason.ifBlank { event.reason })
                }

        /**
         * Maps a durable node source ref ([HierarchyNodeDto.creationPoint] /
         * [HierarchyNodeDto.lastSuspensionPoint]) into the inspector's [SourceRef], using the same
         * "function · reason" label idiom the timeline path uses.
         */
        private fun SuspensionPointDto.toSourceRef(): SourceRef {
            val label =
                listOfNotNull(
                    function.takeIf { it.isNotBlank() },
                    reason.takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifBlank { null }
            return SourceRef(fileName, lineNumber, label)
        }

        /**
         * Lifetime label: the timeline's completion-only totalDuration when present; otherwise, for a
         * running coroutine, a live lifetime from [HierarchyNodeDto.createdAtNanos] using the same
         * same-machine System.nanoTime approximation rowFrom (CoroutineRow.kt:32-39) already uses for
         * row ages — so the row and the inspector show consistent numbers. totalLabel stays
         * completion-only (honest).
         */
        private fun lifetimeLabel(
            running: Boolean,
            timeline: TimelineDto?,
            node: HierarchyNodeDto?,
            nowNanos: Long,
        ): String {
            val total = timeline?.totalDuration
            val liveLifetime = node?.takeIf { running && it.createdAtNanos > 0 }
            return when {
                total != null -> formatApproxNanos(total)
                liveLifetime != null ->
                    formatApproxNanos((nowNanos - liveLifetime.createdAtNanos).coerceAtLeast(0))
                else -> DASH
            }
        }
    }
}
