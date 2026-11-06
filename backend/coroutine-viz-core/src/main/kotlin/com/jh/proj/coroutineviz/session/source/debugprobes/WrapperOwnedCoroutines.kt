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
    val owned = raws.indices.filter { isWrapperOwned(raws[it], session) }.toSet()
    for (i in owned) snapshots[i].wrapper = elements[i]
    val indexByKey = snapshots.indices.associateBy { snapshots[it].key }
    for (i in raws.indices) {
        if (i in owned) continue
        val parent = snapshots[i].parentKey?.let(indexByKey::get)
        val element = elements[i]
        snapshots[i].parentCoroutineId =
            if (element != null) {
                // Inherited: the element names the nearest wrapper ancestor, listed or not (a wrapper whose body returned
                // is not). Only a nearer observed descendant of that wrapper (same element, not the wrapper) keeps its id.
                val nearerDescendant = parent != null && parent !in owned && elements[parent] === element
                if (nearerDescendant) null else element.coroutineId
            } else {
                // No element of this session's (none, or another session's): an observed wrapper parent is named by its
                // own id; any other parent keeps its dp- id.
                parent?.takeIf { it in owned }?.let { elements[it]!!.coroutineId }
            }
    }
    return snapshots
}
