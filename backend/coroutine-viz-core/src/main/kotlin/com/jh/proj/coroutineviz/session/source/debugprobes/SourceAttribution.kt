package com.jh.proj.coroutineviz.session.source.debugprobes

import com.jh.proj.coroutineviz.events.CoroutineOrigin
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/**
 * Pure RCO-03 attribution extractor. Pulls a coroutine's source location from a
 * creation `List<StackTraceElement>`, plus label/dispatcher from a
 * `CoroutineContext`. No DebugProbes dependency — exercised with hand-built
 * stacks/contexts in tests.
 *
 * Frame selection: the first frame whose `className` is NOT coroutines infrastructure
 * (`kotlinx.coroutines*` / `kotlin.coroutines*`). Unlike
 * [com.jh.proj.coroutineviz.events.SuspensionPoint.capture] it does not skip wrapper classes
 * or frames without a line number.
 */
object SourceAttribution {
    /** The SDK client's package: a coroutine created with one of its frames on the stack is vizcore's (spec "Origin"). */
    const val SDK_CLIENT_PACKAGE: String = "com.jh.proj.coroutineviz.client."

    /**
     * Who launched a coroutine, read from its creation stack (spec 2026-10-21, "Origin"): [CoroutineOrigin.VIZCORE] when
     * any frame belongs to the SDK client package, [CoroutineOrigin.APP] otherwise (the public wrapper API is app code),
     * and [CoroutineOrigin.UNKNOWN] without a creation stack (DebugProbes installed without creation stacks, #217).
     */
    fun origin(creationStackTrace: List<StackTraceElement>): CoroutineOrigin =
        when {
            creationStackTrace.isEmpty() -> CoroutineOrigin.UNKNOWN
            creationStackTrace.any { it.className.startsWith(SDK_CLIENT_PACKAGE) } -> CoroutineOrigin.VIZCORE
            else -> CoroutineOrigin.APP
        }

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
     * Extract function + file:line from the first user (non-infrastructure)
     * frame of ANY stack (15-08 Task 3). Returns all nulls for an empty or
     * all-infrastructure stack. `lineNumber` is kept only when `>= 0` (a
     * negative line means "unknown" in [StackTraceElement]).
     */
    fun fromStack(stack: List<StackTraceElement>): Location {
        val frame = stack.firstUserFrame()
        return Location(
            function = frame?.methodName,
            fileName = frame?.fileName,
            lineNumber = frame?.lineNumber?.takeIf { it >= 0 },
        )
    }

    /**
     * Extract function + file:line from the creation stack (RCO-03). Delegates
     * to the generic [fromStack] first-user-frame extraction.
     */
    fun fromCreationStack(creationStackTrace: List<StackTraceElement>): Location = fromStack(creationStackTrace)

    /**
     * Best-effort suspension reason: the method name of the first
     * non-infrastructure frame of the last observed stack. Null when none.
     */
    fun reason(lastObservedStackTrace: List<StackTraceElement>): String? = lastObservedStackTrace.firstUserFrame()?.methodName

    /** `CoroutineName.name` → event `label`. Null when unnamed. */
    fun label(context: CoroutineContext): String? = context[CoroutineName]?.name

    /**
     * Dispatcher (`ContinuationInterceptor`) → `ThreadAssigned.dispatcherName`.
     * Null when absent.
     *
     * Normalizes the standard dispatchers to STABLE, human-meaningful names
     * (`Dispatchers.Default`/`IO`/`Main`/`Unconfined`) rather than the raw
     * `toString()`, which for many dispatchers embeds a hashcode or worker-pool
     * identity (e.g. `DefaultDispatcher@1a2b[...]`) — neither stable nor
     * meaningful, and it differs run-to-run, producing unstable FE thread lanes
     * (WR-04). Unknown dispatchers fall back to their simple class name instead of
     * the full (often hash-bearing) `toString()`.
     */
    fun dispatcherName(context: CoroutineContext): String? {
        val interceptor = context[ContinuationInterceptor] ?: return null
        return when (interceptor) {
            Dispatchers.Default -> "Dispatchers.Default"
            Dispatchers.IO -> "Dispatchers.IO"
            Dispatchers.Main -> "Dispatchers.Main"
            Dispatchers.Unconfined -> "Dispatchers.Unconfined"
            else -> interceptor::class.simpleName ?: interceptor.toString()
        }
    }
}
