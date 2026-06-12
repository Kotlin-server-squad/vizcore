package com.jh.proj.coroutineviz.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============================================================================
// Anti-Pattern Detection Events
// ============================================================================

/**
 * Severity levels for detected anti-patterns.
 */
@Serializable
enum class AntiPatternSeverity {
    ERROR,
    WARNING,
    INFO,
}

/**
 * Categories of anti-patterns.
 */
@Serializable
enum class AntiPatternType {
    GLOBAL_SCOPE_USAGE,
    BLOCKING_ON_MAIN,
    LEAKED_COROUTINE,
    UNNECESSARY_ASYNC_AWAIT,
    RUN_BLOCKING_MISUSE,
    COROUTINE_EXPLOSION,
    UNHANDLED_EXCEPTION,
    MISSING_CANCELLATION_CHECK,
    SHARED_MUTABLE_STATE,
    WRONG_DISPATCHER,
}

/**
 * Emitted when an anti-pattern is detected in coroutine usage.
 *
 * Anti-patterns are code patterns that, while syntactically valid,
 * lead to bugs, performance issues, or maintenance problems.
 *
 * @property patternType The category of anti-pattern detected
 * @property severity How serious this pattern is
 * @property description Human-readable description of the issue
 * @property suggestion Recommended fix or alternative approach
 * @property coroutineId The coroutine where the pattern was detected, if applicable
 * @property scopeId The scope where the pattern was detected, if applicable
 * @property affectedEntities IDs of coroutines/resources involved
 */
@Serializable
@SerialName("AntiPatternDetected")
data class AntiPatternDetected(
    override val sessionId: String,
    override var seq: Long,
    override val tsNanos: Long,
    val patternType: AntiPatternType,
    val severity: AntiPatternSeverity,
    val description: String,
    val suggestion: String,
    val coroutineId: String? = null,
    val scopeId: String? = null,
    val affectedEntities: List<String> = emptyList(),
) : VizEvent {
    override val kind: String get() = "AntiPatternDetected"
}
