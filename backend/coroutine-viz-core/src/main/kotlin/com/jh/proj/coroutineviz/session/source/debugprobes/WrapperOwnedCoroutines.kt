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
 * Marks the snapshots of wrapper-owned coroutines ([isWrapperOwned]) with their wrapper's element, and keeps the wrapper
 * as the parent of the coroutines that inherited its element (launched from its body with the body's context), under
 * the wrapper's own id ([VizCoroutineElement.coroutineId], the id its wrapper events use), so ancestry and rounds stay
 * intact. That holds also once the wrapper's body has returned while such a child still runs (DebugProbes no longer
 * list the wrapper then, though its Job waits for the child), and it does not replace a nearer observed parent that
 * inherited the same element (a descendant of the wrapper). A marked snapshot stays in the diff: its wrapper events
 * give its creation, start and end, and [DebugProbesSource] reconciles its sampled state. [snapshots] are [raws] mapped
 * one to one by [CoroutineInfoAdapter.toSnapshots].
 */
internal fun attributeToWrappers(
    raws: List<CoroutineInfoAdapter.RawInfo>,
    snapshots: List<CoroutineSnapshot>,
    session: VizSession,
): List<CoroutineSnapshot> {
    val elements = raws.map { raw -> raw.context[VizCoroutineElement]?.takeIf { it.owner === session } }
    if (elements.all { it == null }) return snapshots
    val indexByKey = snapshots.indices.associateBy { snapshots[it].key }
    for (i in raws.indices) {
        val element = elements[i] ?: continue
        if (isWrapperOwned(raws[i], session)) {
            snapshots[i].wrapper = element
            continue
        }
        // Inherited: the wrapper is an ancestor. Its id is the parent unless the nearest observed parent is a nearer
        // descendant of the same wrapper (it carries the same element), which keeps its own id.
        val parent = snapshots[i].parentKey?.let(indexByKey::get)
        val parentIsDescendant = parent != null && elements[parent] === element && !isWrapperOwned(raws[parent], session)
        if (!parentIsDescendant) snapshots[i].parentCoroutineId = element.coroutineId
    }
    return snapshots
}
