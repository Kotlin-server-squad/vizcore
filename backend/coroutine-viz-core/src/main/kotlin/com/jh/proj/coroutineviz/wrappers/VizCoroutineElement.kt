package com.jh.proj.coroutineviz.wrappers

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.CoroutineContext

/**
 * CoroutineContext element that carries visualization metadata through the coroutine hierarchy.
 *
 * VizCoroutineElement is automatically added to the coroutine context by [VizScope.vizLaunch]
 * and [VizScope.vizAsync]. It enables:
 * - Parent-child relationship tracking (via [parentCoroutineId])
 * - Event attribution to the correct coroutine
 * - Scope membership tracking
 *
 * Access the current element from any coroutine context:
 * ```kotlin
 * val element = currentCoroutineContext()[VizCoroutineElement]
 * println("Current coroutine: ${element?.coroutineId}")
 * ```
 *
 * Code that cannot suspend finds the element of the coroutine running on its thread with [current]: the element
 * is a [ThreadContextElement], set on the thread while its coroutine runs and restored when it leaves.
 *
 * @property coroutineId Unique identifier for this coroutine
 * @property jobId ID of the associated Job
 * @property parentCoroutineId ID of the parent coroutine, null for root coroutines
 * @property scopeId ID of the owning VizScope
 * @property label Optional human-readable label for this coroutine
 */
data class VizCoroutineElement(
    val coroutineId: String,
    val jobId: String,
    val parentCoroutineId: String?,
    val scopeId: String,
    val label: String?,
) : ThreadContextElement<VizCoroutineElement?> {
    companion object Key : CoroutineContext.Key<VizCoroutineElement> {
        private val onThisThread = ThreadLocal<VizCoroutineElement?>()

        /**
         * The element of the instrumented coroutine running on this thread, or `null` outside one. For code that
         * cannot suspend (`unlock`, `release`, `trySend`), where `currentCoroutineContext()` is not available.
         */
        fun current(): VizCoroutineElement? = onThisThread.get()
    }

    override val key: CoroutineContext.Key<*> = Key

    override fun updateThreadContext(context: CoroutineContext): VizCoroutineElement? =
        onThisThread.get().also { onThisThread.set(this) }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: VizCoroutineElement?,
    ) = onThisThread.set(oldState)
}
