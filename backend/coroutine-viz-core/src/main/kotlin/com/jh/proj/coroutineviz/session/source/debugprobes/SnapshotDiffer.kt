package com.jh.proj.coroutineviz.session.source.debugprobes

import com.jh.proj.coroutineviz.session.source.debugprobes.CoroutineDelta.Appeared
import com.jh.proj.coroutineviz.session.source.debugprobes.CoroutineDelta.StateChanged
import com.jh.proj.coroutineviz.session.source.debugprobes.CoroutineDelta.Vanished

/**
 * Pure snapshot diff (Research Pattern 2) — no DebugProbes, no clock, no session.
 *
 * Compares two snapshot maps keyed by stable [CoroKey] identity and yields the
 * deltas:
 * - key only in [next]  → [Appeared]
 * - key in both, state changed → [StateChanged]
 * - key only in [prev]  → [Vanished]
 * - key in both, same state → nothing (this is what prevents the double-emit
 *   regression for a stable coroutine set, Research Pitfall 1 / T-06-05).
 *
 * The order is total and deterministic for stable inputs: all [Appeared]
 * (in [next] iteration order), then all [StateChanged] (in [next] iteration
 * order), then all [Vanished] (in [prev] iteration order). Using a
 * `LinkedHashMap` upstream therefore yields stable, testable ordering.
 */
fun diff(
    prev: Map<CoroKey, CoroutineSnapshot>,
    next: Map<CoroKey, CoroutineSnapshot>,
): List<CoroutineDelta> {
    val appeared = mutableListOf<CoroutineDelta>()
    val changed = mutableListOf<CoroutineDelta>()
    val vanished = mutableListOf<CoroutineDelta>()

    for ((key, nextSnap) in next) {
        val prevSnap = prev[key]
        if (prevSnap == null) {
            appeared += Appeared(nextSnap)
        } else if (prevSnap.state != nextSnap.state) {
            changed += StateChanged(prevSnap.state, nextSnap.state, nextSnap)
        }
        // else: present in both, unchanged state → no delta (no double-emit).
    }

    for ((key, prevSnap) in prev) {
        if (key !in next) {
            vanished += Vanished(prevSnap)
        }
    }

    return appeared + changed + vanished
}
