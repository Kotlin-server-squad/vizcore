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
        fun capture(reason: String): SuspensionPoint = captureWithProvenance(reason).first

        /** [capture] plus where the point came from: [SuspensionProvenance.REAL_FRAME], or `null` for the placeholder. */
        internal fun captureWithProvenance(reason: String): Pair<SuspensionPoint, SuspensionProvenance?> =
            fromFrames(Throwable().stackTrace, reason)

        /** The point for [frames] and its provenance: a real frame was selected, or `null` when only the placeholder fits. */
        internal fun fromFrames(
            frames: Array<StackTraceElement>,
            reason: String,
        ): Pair<SuspensionPoint, SuspensionProvenance?> {
            val relevantFrame = selectCallerFrame(frames)
            val point =
                SuspensionPoint(
                    function = relevantFrame?.methodName ?: UNKNOWN_FUNCTION,
                    fileName = relevantFrame?.fileName,
                    lineNumber = relevantFrame?.lineNumber?.takeIf { it >= 0 },
                    reason = reason,
                )
            return point to relevantFrame?.let { SuspensionProvenance.REAL_FRAME }
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
