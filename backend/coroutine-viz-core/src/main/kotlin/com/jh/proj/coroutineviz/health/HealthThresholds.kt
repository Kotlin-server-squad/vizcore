package com.jh.proj.coroutineviz.health

/** Core health constants (spec 2026-10-21, "Definitions" and C-2/C-3). */
object HealthThresholds {
    /** Running longer than this since the latest start/resume, up to the watermark, is possibly blocked (#232). */
    const val POSSIBLY_BLOCKED_NANOS: Long = 10_000_000_000L

    /** Suspended longer than this is the long-suspended observation (the plugin's LONG_SUSPENDED_THRESHOLD_NANOS). */
    const val LONG_SUSPENDED_NANOS: Long = 30_000_000_000L

    /** Default leak (long-lived) threshold, as `GET …/metrics` uses today. */
    const val DEFAULT_LEAK_THRESHOLD_MS: Long = 30_000L

    /** A never-finishing genuine root needs at least this many direct children to become a container. */
    const val CONTAINER_MIN_DIRECT_CHILDREN: Int = 3

    /** Every health list is capped at this many entries, with its total. */
    const val LIST_CAP: Int = 50

    /** Retained transitions per possibly blocked coroutine. */
    const val TRANSITIONS_CAP: Int = 20

    /** Best-effort target of full summaries per session; finished ones beyond it become stubs. */
    const val DEFAULT_FULL_SUMMARY_TARGET: Int = 50_000

    /** Hard limit of tracked coroutines (summaries plus stubs) per session. */
    const val DEFAULT_TRACKED_CAPACITY: Int = 100_000

    /** Thread-name prefixes of known event loops, for the clue (spec "Event-loop clue"). */
    val EVENT_LOOP_THREAD_PREFIXES: List<String> =
        listOf(
            "lettuce-nioEventLoop-",
            "lettuce-epollEventLoop-",
            "reactor-http-nio-",
            "reactor-tcp-nio-",
            "nioEventLoopGroup-",
            "epollEventLoopGroup-",
        )

    /**
     * A `CoroutineFailed` whose type is a cancellation is a cancellation, not a failure (plugin
     * ProblemDerivation). Unlike the plugin's isRealException, an unknown (`null`) type is NOT a
     * cancellation: the failure is counted, because nothing says it was cancelled.
     */
    fun isCancellation(exceptionType: String?): Boolean = exceptionType?.endsWith("CancellationException") == true
}
