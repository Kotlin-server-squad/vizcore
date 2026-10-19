package com.jh.proj.coroutineviz.session.source.debugprobes

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/**
 * Marks a coroutine as vizcore's own, so [DebugProbesSource] leaves it out of the app's session.
 * Children inherit it through their context. vizcore adds it where it launches its own work (the
 * SDK client's loops, the poll loop, the session projections), never to a scope that app code is
 * handed, so app work launched there stays visible.
 */
object VizcoreInternal : CoroutineContext.Element {
    object Key : CoroutineContext.Key<VizcoreInternal>

    override val key: CoroutineContext.Key<*> get() = Key

    override fun toString(): String = "VizcoreInternal"
}

/**
 * Removes the coroutines vizcore itself runs from a DebugProbes dump, so an app's session
 * shows the app's work only (#225).
 *
 * A coroutine is vizcore's own when:
 * - its context carries [VizcoreInternal]; or
 * - it runs on one of [internalDispatchers], the dispatcher the SDK gives its Ktor client.
 *   Ktor builds its engine and WebSocket contexts from scratch, so a marker from the caller
 *   never reaches them, but the engine dispatcher does; or
 * - it shares its root Job with a coroutine on [internalDispatchers]. Ktor runs a WebSocket
 *   session's coroutines as siblings under one request Job, and some of them run on
 *   `Unconfined`. Those request Jobs belong to the SDK's client alone. A [VizcoreInternal]
 *   coroutine's root is not used this way: app code may share it.
 *
 * Pass the same [transportRoots] on every poll: it remembers those request Jobs.
 *
 * Still visible, because nothing ties them to the SDK: Ktor's socket reader and writer for the
 * SDK's connection (no parent Job, on `Dispatchers.IO`), `cio-request-timeout` while the SDK
 * makes an HTTP request, and Ktor's process-wide nonce generator.
 */
internal fun excludeSdkOwned(
    raws: List<CoroutineInfoAdapter.RawInfo>,
    internalDispatchers: Set<ContinuationInterceptor>,
    transportRoots: MutableSet<Job> = HashSet(),
): List<CoroutineInfoAdapter.RawInfo> {
    val onInternalDispatcher = BooleanArray(raws.size) { raws[it].context[ContinuationInterceptor] in internalDispatchers }
    val roots = RootCache()
    // Kept across dumps by the caller: a Job detaches from its parent once it completes, so a
    // transport coroutine that finished after the dump no longer leads to the shared root. A
    // sibling that itself completes between the dump and this walk detaches too and can show up
    // once; Job ancestry can't be read as of the dump.
    raws.indices.filter { onInternalDispatcher[it] }.mapNotNullTo(transportRoots) { raws[it].job?.let(roots::of) }
    return raws.filterIndexed { i, raw ->
        raw.context[VizcoreInternal.Key] == null &&
            !onInternalDispatcher[i] &&
            (transportRoots.isEmpty() || raw.job?.let(roots::of) !in transportRoots)
    }
}

/** Root Job lookups for one dump; Jobs on a shared chain are walked once. */
private class RootCache {
    private val known = HashMap<Job, Job>()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun of(job: Job): Job {
        known[job]?.let { return it }
        val chain = ArrayList<Job>()
        var current = job
        while (true) {
            known[current]?.let { root ->
                chain.forEach { known[it] = root }
                return root
            }
            chain += current
            current = current.parent ?: break
        }
        chain.forEach { known[it] = current }
        return current
    }
}
