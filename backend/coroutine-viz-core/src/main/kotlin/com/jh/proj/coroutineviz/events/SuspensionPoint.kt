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

        // Skipped by exact class, its nested/lambda classes (`Name$...`) or its file class (`NameKt`), never by a
        // bare prefix: a user class that merely starts with the same letters is the caller.
        private val SKIPPED_CLASSES =
            listOf(
                SuspensionPoint::class.java.name,
                "com.jh.proj.coroutineviz.wrappers.VizScope",
                "com.jh.proj.coroutineviz.wrappers.InstrumentedDeferred",
            )
        private val INFRASTRUCTURE_PREFIXES = listOf("kotlinx.coroutines", "kotlin.coroutines")

        /**
         * Captures the suspension point from the current stack: the first frame that is the caller's own code.
         * Frames of this class (incl. `capture$default`), the wrappers that call it and coroutines infrastructure
         * are skipped, as are line-less synthetic bridges unless nothing else qualifies (a stripped build keeps
         * the method name then, without a line).
         * @param reason The reason for suspension (e.g., "delay", "await")
         */
        fun capture(reason: String): SuspensionPoint {
            val relevantFrame = selectCallerFrame(Throwable().stackTrace)

            return SuspensionPoint(
                function = relevantFrame?.methodName ?: UNKNOWN_FUNCTION,
                fileName = relevantFrame?.fileName,
                lineNumber = relevantFrame?.lineNumber?.takeIf { it >= 0 },
                reason = reason,
            )
        }

        /** Kept for source and binary compatibility; frames are chosen by what they are, so [skipFrames] is ignored. */
        @Deprecated("frames are chosen by what they are", ReplaceWith("capture(reason)"))
        fun capture(
            reason: String,
            skipFrames: Int = 2,
        ): SuspensionPoint = capture(reason)

        /** The first caller frame with a line number, else the first caller frame without one, else `null`. */
        internal fun selectCallerFrame(frames: Array<StackTraceElement>): StackTraceElement? {
            val callers = frames.asSequence().filter { it.isCallerCode() }
            return callers.firstOrNull { it.lineNumber >= 0 } ?: callers.firstOrNull()
        }

        private fun StackTraceElement.isCallerCode(): Boolean =
            SKIPPED_CLASSES.none { className == it || className.startsWith("$it$") || className == "${it}Kt" } &&
                INFRASTRUCTURE_PREFIXES.none { className.startsWith(it) }
    }
}

/** Provenance of a point made by [SuspensionPoint.capture]: a frame it read, or `null` for its "unknown" placeholder. */
internal fun SuspensionPoint.capturedProvenance(): SuspensionProvenance? =
    if (function == SuspensionPoint.UNKNOWN_FUNCTION && fileName == null) null else SuspensionProvenance.REAL_FRAME
