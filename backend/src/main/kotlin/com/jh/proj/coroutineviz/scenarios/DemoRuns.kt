package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.wrappers.VizScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Caps how many demos run on the server at the same time, across all clients
 * (`scenarios.maxConcurrent`, env `MAX_CONCURRENT_DEMOS`; zero or less means no limit).
 *
 * A demo holds its slot for as long as its coroutines run, not only while its request is
 * handled: several scenarios answer at once and keep running on the session's scope, where a
 * client disconnect does not stop them either. The slot is freed when they complete, fail or
 * are cancelled (for example when the session is deleted).
 */
class DemoRuns(
    private val maxConcurrent: Int,
) {
    private val running = AtomicInteger(0)

    // Waits for the coroutines of runs whose request has already been answered.
    private val watchers = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Number of demos running now. */
    val active: Int get() = running.get()

    /** Takes a slot for a new demo, or returns null when every slot is taken. Never waits. */
    fun tryStart(): DemoRun? {
        while (true) {
            val now = running.get()
            if (maxConcurrent in 1..now) return null
            if (running.compareAndSet(now, now + 1)) return DemoRun(this)
        }
    }

    internal fun release() {
        running.decrementAndGet()
    }

    internal fun releaseWhenDone(run: DemoRun) {
        watchers.launch {
            try {
                run.awaitCoroutines()
            } finally {
                release()
            }
        }
    }

    /** Stops waiting for running demos; called when the application stops. */
    fun close() = watchers.cancel()
}

/**
 * One demo's slot in [DemoRuns]. It is in the coroutine context of the request that runs the
 * demo, so every [demoScope] made for the demo counts against the slot.
 */
class DemoRun internal constructor(
    private val runs: DemoRuns,
) : AbstractCoroutineContextElement(DemoRun) {
    companion object Key : CoroutineContext.Key<DemoRun>

    private val scopes = CopyOnWriteArrayList<VizScope>()
    private val ended = AtomicBoolean(false)

    internal fun register(scope: VizScope) {
        scopes += scope
    }

    /**
     * The request is done (answered, failed or cancelled). The slot is freed now if the demo has
     * no coroutines left, otherwise once they have all ended. Safe to call more than once.
     */
    fun handlerEnded() {
        if (!ended.compareAndSet(false, true)) return
        val running =
            scopes.any {
                it.coroutineContext.job.children
                    .any()
            }
        if (!running) {
            runs.release()
        } else {
            runs.releaseWhenDone(this)
        }
    }

    internal suspend fun awaitCoroutines() {
        scopes.forEach { it.coroutineContext.job.awaitChildren() }
    }

    // A scope's own Job never completes; its children are the demo's coroutines, and a child may
    // launch a sibling before it ends, so wait until none is left.
    private suspend fun Job.awaitChildren() {
        while (true) {
            val children = children.toList()
            if (children.isEmpty()) return
            children.joinAll()
        }
    }
}

/**
 * The scope a demo runs its coroutines in. It counts against the demo's slot in [DemoRuns], so
 * it can only be made while the demo holds one: a demo that does not, fails instead of running
 * outside the limit.
 */
suspend fun VizSession.demoScope(context: CoroutineContext = EmptyCoroutineContext): VizScope {
    val run =
        checkNotNull(currentCoroutineContext()[DemoRun]) {
            "A demo can only run while it holds a slot (MAX_CONCURRENT_DEMOS); start it through demoPost or demoGet"
        }
    return VizScope(this, context).also(run::register)
}
