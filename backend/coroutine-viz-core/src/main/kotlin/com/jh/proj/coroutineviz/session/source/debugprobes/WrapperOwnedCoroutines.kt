package com.jh.proj.coroutineviz.session.source.debugprobes

import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.wrappers.VizCoroutineElement

/**
 * Whether [raw] is a coroutine a [com.jh.proj.coroutineviz.wrappers.VizScope] of [session] launched: its wrapper events
 * already describe it, so DebugProbes leave it out and each coroutine is captured once (spec 2026-10-21, "Origin"). A
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

/** [raws] without the coroutines [isWrapperOwned] leaves to their wrapper events. */
internal fun excludeWrapperOwned(
    raws: List<CoroutineInfoAdapter.RawInfo>,
    session: VizSession,
): List<CoroutineInfoAdapter.RawInfo> = raws.filterNot { isWrapperOwned(it, session) }

/**
 * Drops the snapshots of wrapper-owned coroutines ([isWrapperOwned]) after the hierarchy was rebuilt with them, and points
 * their DebugProbes-captured children at the wrapper's own id ([VizCoroutineElement.coroutineId], the id its wrapper events
 * use), so ancestry and rounds stay intact. [snapshots] are [raws] mapped one to one by [CoroutineInfoAdapter.toSnapshots].
 */
internal fun leaveToWrappers(
    raws: List<CoroutineInfoAdapter.RawInfo>,
    snapshots: List<CoroutineSnapshot>,
    session: VizSession,
): List<CoroutineSnapshot> {
    val owned = raws.indices.filter { isWrapperOwned(raws[it], session) }.toSet()
    if (owned.isEmpty()) return snapshots
    val wrapperIds = owned.associate { snapshots[it].key to raws[it].context[VizCoroutineElement]!!.coroutineId }
    return snapshots
        .filterIndexed { i, _ -> i !in owned }
        .onEach { snapshot -> snapshot.parentKey?.let(wrapperIds::get)?.let { snapshot.parentCoroutineId = it } }
}
