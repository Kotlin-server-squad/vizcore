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
        /**
         * Helper to capture suspension point from current stack trace.
         * @param reason The reason for suspension (e.g., "delay", "await")
         * @param skipFrames Number of stack frames to skip (default 2)
         */
        fun capture(
            reason: String,
            skipFrames: Int = 2,
        ): SuspensionPoint {
            val stackTrace = Throwable().stackTrace

            // Find first non-coroutines-infrastructure frame
            val relevantFrame =
                stackTrace
                    .drop(skipFrames)
                    .firstOrNull { frame ->
                        !frame.className.startsWith("kotlinx.coroutines") &&
                            !frame.className.contains("VizScope")
                    }

            return SuspensionPoint(
                function = relevantFrame?.methodName ?: "unknown",
                fileName = relevantFrame?.fileName,
                lineNumber = relevantFrame?.lineNumber?.takeIf { it >= 0 },
                reason = reason,
            )
        }
    }
}

/** Provenance of a point made by [SuspensionPoint.capture]: a frame it read, or `null` for its "unknown" placeholder. */
fun SuspensionPoint.capturedProvenance(): SuspensionProvenance? =
    if (function == "unknown" && fileName == null) null else SuspensionProvenance.REAL_FRAME
