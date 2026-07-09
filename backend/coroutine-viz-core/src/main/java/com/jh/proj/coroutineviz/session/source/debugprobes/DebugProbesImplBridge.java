package com.jh.proj.coroutineviz.session.source.debugprobes;

import java.util.ArrayList;
import java.util.List;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.debug.internal.DebugCoroutineInfo;
import kotlinx.coroutines.debug.internal.DebugProbesImpl;

/**
 * JVM-level bridge to the impl-level DebugProbes dump (15-08 Task 2,
 * GAP-ENRICHMENT-EMPTY).
 *
 * <p>The PUBLIC {@code kotlinx.coroutines.debug.CoroutineInfo} wrapper DROPS
 * {@code lastObservedThread} (its fields are context/state/stacks only), so the
 * thread a coroutine was last observed on is unreachable from the public dump.
 * {@code DebugProbesImpl.dumpCoroutinesInfo()} — the exact list the public
 * {@code DebugProbes.dumpCoroutinesInfo()} wraps — retains it, but both
 * {@code DebugProbesImpl} and {@code DebugCoroutineInfo} are Kotlin-{@code
 * internal} (JVM-public, blocked only by Kotlin metadata). javac sees the JVM
 * level only, so this Java bridge is the one compile-time-checked, reflection-free
 * access point; it maps straight into the adapter's fakeable
 * {@link CoroutineInfoAdapter.RawInfo} so all Kotlin code stays on public types.
 *
 * <p>Version coupling is bounded: the agent fat-jar bundles its own
 * kotlinx-coroutines, so the bridge always runs against the version it compiled
 * against.
 */
final class DebugProbesImplBridge {
    private DebugProbesImplBridge() {
    }

    /**
     * Dump all observed coroutines as {@link CoroutineInfoAdapter.RawInfo},
     * including {@code lastObservedThread}. Throws (like the public dump) when
     * probes are not installed; the source's per-tick isolation contains it.
     */
    static List<CoroutineInfoAdapter.RawInfo> dumpRawInfos() {
        List<DebugCoroutineInfo> infos = DebugProbesImpl.INSTANCE.dumpCoroutinesInfo();
        List<CoroutineInfoAdapter.RawInfo> raws = new ArrayList<>(infos.size());
        for (DebugCoroutineInfo info : infos) {
            raws.add(
                new CoroutineInfoAdapter.RawInfo(
                    // Impl-level state is a raw String matching the enum names
                    // (CREATED/RUNNING/SUSPENDED); valueOf mirrors the debug
                    // module's own State.valueOf strictness.
                    CoroState.valueOf(info.getState()),
                    (Job) info.getContext().get(Job.Key),
                    info.getContext(),
                    info.getCreationStackTrace(),
                    info.lastObservedStackTrace(),
                    info.getLastObservedThread()
                )
            );
        }
        return raws;
    }
}
