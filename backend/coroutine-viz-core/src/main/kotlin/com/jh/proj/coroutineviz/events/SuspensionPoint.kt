package com.jh.proj.coroutineviz.events

import kotlinx.serialization.Serializable

/**
 * Captures information about where a suspension occurred in user code.
 */
@Serializable
data class SuspensionPoint(
    // Method name
    val function: String,
    // Source file
    val fileName: String? = null,
    // Line number
    val lineNumber: Int? = null,
    // "delay", "withContext", "join", "await"
    val reason: String,
) {
    companion object {
        /** Function name of the placeholder [capture] returns when no caller frame qualifies. */
        const val UNKNOWN_FUNCTION = "unknown"

        // Matched as class-name prefixes so their lambdas and inner classes are skipped too, but not user classes.
        private val WRAPPER_CLASSES =
            listOf("com.jh.proj.coroutineviz.wrappers.VizScope", "com.jh.proj.coroutineviz.wrappers.InstrumentedDeferred")
        private val INFRASTRUCTURE_PREFIXES = listOf("kotlinx.coroutines", "kotlin.coroutines")

        /**
         * Captures the suspension point from the current stack: the first frame that is the caller's own code.
         * Frames of this class (incl. `capture$default`), the wrappers that call it, coroutines infrastructure
         * and line-less synthetic bridges are skipped.
         * @param reason The reason for suspension (e.g., "delay", "await")
         */
        fun capture(reason: String): SuspensionPoint {
            val relevantFrame = Throwable().stackTrace.firstOrNull { it.isCallerFrame() }

            return SuspensionPoint(
                function = relevantFrame?.methodName ?: UNKNOWN_FUNCTION,
                fileName = relevantFrame?.fileName,
                lineNumber = relevantFrame?.lineNumber,
                reason = reason,
            )
        }

        private fun StackTraceElement.isCallerFrame(): Boolean =
            lineNumber >= 0 &&
                !className.startsWith(SuspensionPoint::class.java.name) &&
                WRAPPER_CLASSES.none { className.startsWith(it) } &&
                INFRASTRUCTURE_PREFIXES.none { className.startsWith(it) }
    }
}

/** Provenance of a point made by [SuspensionPoint.capture]: a frame it read, or `null` for its "unknown" placeholder. */
fun SuspensionPoint.capturedProvenance(): SuspensionProvenance? =
    if (function == SuspensionPoint.UNKNOWN_FUNCTION && fileName == null) null else SuspensionProvenance.REAL_FRAME
