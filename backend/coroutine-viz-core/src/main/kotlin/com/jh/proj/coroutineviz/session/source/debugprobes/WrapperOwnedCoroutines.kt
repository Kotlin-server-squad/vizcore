package com.jh.proj.coroutineviz.session.source.debugprobes

import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.wrappers.VizCoroutineElement

/**
 * Whether [raw] is a coroutine a [com.jh.proj.coroutineviz.wrappers.VizScope] of [session] launched: its wrapper events
 * already describe it, so DebugProbes do not create it again and each coroutine is captured once (spec 2026-10-21,
 * "Origin"). A
 * coroutine whose element was made for it ([VizCoroutineElement.ownerJob] is its Job, or not recorded yet: the wrapper
 * before its body runs) is the wrapper's; a coroutine that inherited the element has another Job. A `VizScope` of another
 * session is not this session's to describe.
 */
internal fun isWrapperOwned(
    raw: CoroutineInfoAdapter.RawInfo,
    session: VizSession,
): Boolean {
    val element = raw.context[VizCoroutineElement] ?: return false
    val ownerJob = element.ownerJob
    return element.owner === session && (ownerJob == null || ownerJob === raw.job)
}

/**
 * Marks the snapshots of wrapper-owned coroutines ([isWrapperOwned]) with their wrapper's element, and points the
 * DebugProbes-captured children of a wrapper at the wrapper's own id ([VizCoroutineElement.coroutineId], the id its
 * wrapper events use), so ancestry and rounds stay intact. A marked snapshot stays in the diff: its wrapper events give
 * its creation, start and end, and DebugProbes add, under the wrapper's id, only the state changes the wrapper cannot see
 * (a plain `delay()` suspends without a wrapper event), see [DebugProbesEventSynthesizer]. [snapshots] are [raws] mapped
 * one to one by [CoroutineInfoAdapter.toSnapshots].
 */
internal fun attributeToWrappers(
    raws: List<CoroutineInfoAdapter.RawInfo>,
    snapshots: List<CoroutineSnapshot>,
    session: VizSession,
): List<CoroutineSnapshot> {
    val owned = raws.indices.filter { isWrapperOwned(raws[it], session) }
    if (owned.isEmpty()) return snapshots
    val wrapperIds = HashMap<CoroKey, String>()
    for (i in owned) {
        val element = raws[i].context[VizCoroutineElement]!!
        snapshots[i].wrapper = element
        wrapperIds[snapshots[i].key] = element.coroutineId
    }
    snapshots.forEach { snapshot -> snapshot.parentKey?.let(wrapperIds::get)?.let { snapshot.parentCoroutineId = it } }
    return snapshots
}
