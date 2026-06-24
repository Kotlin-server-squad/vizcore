package com.jh.proj.coroutineviz.session.source.debugprobes

import kotlinx.coroutines.CoroutineName
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/**
 * Pure RCO-03 attribution extractor. Pulls a coroutine's source location from a
 * creation `List<StackTraceElement>`, plus label/dispatcher from a
 * `CoroutineContext`. No DebugProbes dependency — exercised with hand-built
 * stacks/contexts in tests.
 *
 * Frame selection mirrors [com.jh.proj.coroutineviz.events.SuspensionPoint.capture]:
 * the first frame whose `className` is NOT coroutines infrastructure
 * (`kotlinx.coroutines*` / `kotlin.coroutines*`).
 */
object SourceAttribution {
    /** The function/file/line of the first user (non-infrastructure) frame. */
    data class Location(
        val function: String?,
        val fileName: String?,
        val lineNumber: Int?,
    )

    private fun StackTraceElement.isInfrastructure(): Boolean =
        className.startsWith("kotlinx.coroutines") || className.startsWith("kotlin.coroutines")

    private fun List<StackTraceElement>.firstUserFrame(): StackTraceElement? = firstOrNull { !it.isInfrastructure() }

    /**
     * Extract function + file:line from the creation stack (RCO-03). Returns all
     * nulls for an empty or all-infrastructure stack. `lineNumber` is kept only
     * when `>= 0` (a negative line means "unknown" in [StackTraceElement]).
     */
    fun fromCreationStack(creationStackTrace: List<StackTraceElement>): Location {
        val frame = creationStackTrace.firstUserFrame()
        return Location(
            function = frame?.methodName,
            fileName = frame?.fileName,
            lineNumber = frame?.lineNumber?.takeIf { it >= 0 },
        )
    }

    /**
     * Best-effort suspension reason: the method name of the first
     * non-infrastructure frame of the last observed stack. Null when none.
     */
    fun reason(lastObservedStackTrace: List<StackTraceElement>): String? = lastObservedStackTrace.firstUserFrame()?.methodName

    /** `CoroutineName.name` → event `label`. Null when unnamed. */
    fun label(context: CoroutineContext): String? = context[CoroutineName]?.name

    /** Dispatcher (`ContinuationInterceptor`) → `ThreadAssigned.dispatcherName`. Null when absent. */
    fun dispatcherName(context: CoroutineContext): String? = context[ContinuationInterceptor]?.toString()
}
