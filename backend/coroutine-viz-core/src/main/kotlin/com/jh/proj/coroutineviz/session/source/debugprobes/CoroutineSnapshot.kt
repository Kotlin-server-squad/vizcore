package com.jh.proj.coroutineviz.session.source.debugprobes

/**
 * The three coroutine states DebugProbes exposes, modelled locally so the pure
 * diff/synthesize layers never depend on `kotlinx.coroutines.debug` types
 * (keeps this layer fully fakeable — Research §"Validation Architecture" Wave 0).
 *
 * [kotlinx.coroutines.debug.State] has exactly `CREATED`, `RUNNING`, `SUSPENDED`.
 */
enum class CoroState { CREATED, RUNNING, SUSPENDED }

/**
 * Stable identity key for a coroutine across polls.
 *
 * `CoroutineInfo` has no public `equals`/`hashCode` and no public sequence
 * number, so it cannot be a map key (Research Pitfall 1). Identity is derived
 * once by the real adapter — from `info.job` reference identity while alive, or
 * a synthetic creation-stack-hash + first-seen ordinal fallback when `job` is
 * null — and wrapped here as an opaque, value-equal token so it works correctly
 * as a `Map` key in the pure diff layer.
 *
 * The pure layer treats the key as opaque: equality/hashing come from [token].
 */
@JvmInline
value class CoroKey(val token: String)

/**
 * A pure value type the diff/synthesize layers operate on, decoupled from
 * DebugProbes' `CoroutineInfo`. The real `CoroutineInfo -> CoroutineSnapshot`
 * adapter (Plan 06-02 Task 3) is the ONLY place that touches DebugProbes types;
 * everything downstream reads this snapshot.
 *
 * Attribution fields (RCO-03) are populated by [SourceAttribution] in Task 2:
 * [function]/[fileName]/[lineNumber] come from the first non-infrastructure
 * creation-stack frame; [label] from `CoroutineName`; [dispatcherName] from the
 * context's `ContinuationInterceptor`.
 *
 * [reason] and [lastObservedStackTrace] (06-REVIEWS.md cross-task fix) carry the
 * best-effort suspension reason + last observed stack so the synthesizer (Task
 * 2/3) can build `SuspensionPoint(function, fileName, lineNumber, reason)` for a
 * RUNNING→SUSPENDED transition WITHOUT reaching back into DebugProbes. `reason`
 * is derived by the adapter as the method name of the top non-infrastructure
 * frame of [lastObservedStackTrace]. `StackTraceElement` is a JDK type — declaring
 * it here keeps the pure layer DebugProbes-free.
 */
data class CoroutineSnapshot(
    /** Stable identity key — see [CoroKey]. */
    val key: CoroKey,
    /** Current observed lifecycle state. */
    val state: CoroState,
    /** `CoroutineName.name` → event `label`. Null when unnamed. */
    val label: String? = null,
    /** Dispatcher (from `ContinuationInterceptor`) → `ThreadAssigned.dispatcherName`. */
    val dispatcherName: String? = null,
    /** Method name of the first non-infrastructure creation-stack frame (RCO-03). */
    val function: String? = null,
    /** Source file of that frame (RCO-03). */
    val fileName: String? = null,
    /** Line number of that frame, only when >= 0 (RCO-03). */
    val lineNumber: Int? = null,
    /** Best-effort suspension reason (top non-infra method of [lastObservedStackTrace]). */
    val reason: String? = null,
    /** Last observed stack at dump time (JDK type; DebugProbes-free). */
    val lastObservedStackTrace: List<StackTraceElement>? = null,
    /**
     * Identity key of this coroutine's nearest OBSERVED ancestor (Phase 8, D-01/D-02),
     * issued by the SAME adapter `jobKeys` cache as [key] so a child's [parentKey]
     * equals its parent coroutine's own [key] and tree edges connect. Null for a
     * tree root (no observed ancestor) or the synthetic null-job path where
     * DebugProbes gives no reliable parent. All-defaulted so no existing call site
     * breaks; a pure internal value, not a wire DTO (no serialization annotation).
     */
    val parentKey: CoroKey? = null,
)

/**
 * The pure result of comparing two snapshot maps (Research Pattern 2). Each
 * delta carries the relevant [CoroutineSnapshot](s) so downstream layers read
 * attribution (incl. [CoroutineSnapshot.reason] / [CoroutineSnapshot.lastObservedStackTrace])
 * directly off the delta.
 */
sealed interface CoroutineDelta {
    /** Coroutine present in `next` but not in `prev`. */
    data class Appeared(val now: CoroutineSnapshot) : CoroutineDelta

    /** Same key in both, with a changed [CoroState]. */
    data class StateChanged(
        val from: CoroState,
        val to: CoroState,
        val now: CoroutineSnapshot,
    ) : CoroutineDelta

    /** Coroutine present in `prev` but not in `next`. */
    data class Vanished(val last: CoroutineSnapshot) : CoroutineDelta
}
