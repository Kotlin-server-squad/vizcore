package com.jh.proj.coroutineviz.session.source.debugprobes

import kotlinx.coroutines.Job
import kotlinx.coroutines.debug.CoroutineInfo
import kotlinx.coroutines.debug.State
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * The ONLY production component that touches `kotlinx.coroutines.debug` types.
 * Maps a real [CoroutineInfo] (or a fakeable [RawInfo] proxy) to the pure
 * [CoroutineSnapshot] the diff/synthesize layers consume.
 *
 * Identity (Research Pitfall 1 / threat T-06-05): keyed on the coroutine's [Job]
 * reference identity ([System.identityHashCode]) while alive. When `job` is null,
 * a synthetic stable key is derived from the creation-stack signature plus a
 * first-seen ordinal — the same identity re-resolves to the same key on re-dump,
 * so a stable coroutine set never double-emits.
 *
 * `CoroutineInfo`'s public constructor takes the `internal` `DebugCoroutineInfo`,
 * so it cannot be hand-built in tests; the adapter is therefore factored around
 * [RawInfo] so field extraction is unit-tested without real DebugProbes
 * ([CoroutineInfoAdapterTest]). The real [toSnapshot] overload only forwards.
 *
 * An adapter instance is stateful (it remembers job→key and synthetic ordinals)
 * and must live for the lifetime of one [DebugProbesSource] poll loop.
 */
class CoroutineInfoAdapter {
    /**
     * Fakeable proxy of the fields the adapter reads off a real [CoroutineInfo].
     * `job` is nullable to exercise the synthetic-key fallback in tests.
     */
    data class RawInfo(
        val state: CoroState,
        val job: Job?,
        val context: CoroutineContext,
        val creationStackTrace: List<StackTraceElement>,
        val lastObservedStackTrace: List<StackTraceElement>,
    )

    // job identity-hash -> assigned key token (stable while the Job is referenced).
    private val jobKeys = ConcurrentHashMap<Int, String>()

    // creation-stack signature -> synthetic key token, for null-job coroutines.
    private val syntheticKeys = ConcurrentHashMap<String, String>()
    private val syntheticOrdinal = AtomicInteger(0)

    private fun keyFor(
        job: Job?,
        creationStackTrace: List<StackTraceElement>,
    ): CoroKey {
        if (job != null) {
            val token = jobKeys.computeIfAbsent(System.identityHashCode(job)) { "job:${System.identityHashCode(job)}" }
            return CoroKey(token)
        }
        // Synthetic fallback: stable across re-dumps for the same creation signature.
        val signature = creationStackTrace.joinToString("|") { "${it.className}#${it.methodName}:${it.lineNumber}" }
        val token =
            syntheticKeys.computeIfAbsent(signature) { "synthetic:${signature.hashCode()}#${syntheticOrdinal.getAndIncrement()}" }
        return CoroKey(token)
    }

    /** Map a fakeable [RawInfo] (the unit-tested path) to a [CoroutineSnapshot]. */
    fun toSnapshot(raw: RawInfo): CoroutineSnapshot {
        val location = SourceAttribution.fromCreationStack(raw.creationStackTrace)
        return CoroutineSnapshot(
            key = keyFor(raw.job, raw.creationStackTrace),
            state = raw.state,
            label = SourceAttribution.label(raw.context),
            dispatcherName = SourceAttribution.dispatcherName(raw.context),
            function = location.function,
            fileName = location.fileName,
            lineNumber = location.lineNumber,
            reason = SourceAttribution.reason(raw.lastObservedStackTrace),
            lastObservedStackTrace = raw.lastObservedStackTrace,
        )
    }

    /** Map a real [CoroutineInfo] by forwarding into the unit-tested [RawInfo] core. */
    fun toSnapshot(info: CoroutineInfo): CoroutineSnapshot =
        toSnapshot(
            RawInfo(
                state = info.state.toCoroState(),
                job = info.job,
                context = info.context,
                creationStackTrace = info.creationStackTrace,
                lastObservedStackTrace = info.lastObservedStackTrace(),
            ),
        )

    private fun State.toCoroState(): CoroState =
        when (this) {
            State.CREATED -> CoroState.CREATED
            State.RUNNING -> CoroState.RUNNING
            State.SUSPENDED -> CoroState.SUSPENDED
        }
}
