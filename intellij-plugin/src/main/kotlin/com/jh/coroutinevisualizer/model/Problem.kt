package com.jh.coroutinevisualizer.model

/**
 * A diagnostic problem category. Declaration order IS severity (D-07): exceptions are worst,
 * then leaks, then long-suspended. The problems list is sorted by this ordinal, then by recency.
 */
enum class ProblemCategory { EXCEPTION, LEAK, LONG_SUSPENDED }

/**
 * One problem row: a coroutine flagged as an exception, leak, or long-suspended. [ageLabel] and
 * [why] are approximate ("~") framed strings recomputed each poll (never false precision).
 */
data class Problem(
    val category: ProblemCategory,
    val coroutineId: String,
    val name: String,
    val ageLabel: String,
    val why: String,
    val sortKeyNanos: Long,
)
