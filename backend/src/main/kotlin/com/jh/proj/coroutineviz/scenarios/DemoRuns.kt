package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.wrappers.VizScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
 * (`scenarios.maxConcurrent`, env `MAX_CONCURRENT_DEMOS`; zero or less means no limit), and
 * how long one may run (`scenarios.maxRunSeconds`, env `MAX_DEMO_RUN_SECONDS`; zero or less
 * means no limit).
 *
 * A demo holds its slot for as long as its coroutines run, not only while its request is
 * handled: several scenarios answer at once and keep running on the session's scope, where a
 * client disconnect does not stop them either. The slot is freed when they complete, fail or
 * are cancelled; a demo still running at its time limit is cancelled, so one that would never
 * end (a deadlock, a huge custom delay) cannot hold a slot for ever.
 */
class DemoRuns(
    private val maxConcurrent: Int,
    private val maxRunMillis: Long = 0,
) {
    private val running = AtomicInteger(0)

    // Waits for the coroutines of runs whose request has already been answered, and runs the
    // time limits.
    private val watchers = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Number of demos running now. */
    val active: Int get() = running.get()

    /** Takes a slot for a new demo, or returns null when every slot is taken. Never waits. */
    fun tryStart(): DemoRun? {
        while (true) {
            val now = running.get()
            if (maxConcurrent in 1..now) return null
            if (running.compareAndSet(now, now + 1)) return DemoRun(this).also { it.startTimeLimit(maxRunMillis) }
        }
    }

    internal fun release() {
        running.decrementAndGet()
    }

    internal fun watch(block: suspend () -> Unit): Job = watchers.launch { block() }

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

    // Guards [ended] and [timedOut] against a scope being added while the run ends.
    private val lock = Any()
    private var ended = false
    private var timedOut = false
    private val released = AtomicBoolean(false)
    private var timeLimit: Job? = null

    internal fun startTimeLimit(maxRunMillis: Long) {
        if (maxRunMillis <= 0) return
        timeLimit =
            runs.watch {
                delay(maxRunMillis)
                stop()
            }
    }

    internal fun newScope(create: () -> VizScope): VizScope =
        synchronized(lock) {
            check(!ended) { "This demo's request has ended, so it cannot start more work (MAX_CONCURRENT_DEMOS)" }
            check(!timedOut) { "This demo ran past its time limit (MAX_DEMO_RUN_SECONDS)" }
            create().also { scopes += it }
        }

    /**
     * The request is done (answered, failed or cancelled). The slot is freed now if the demo has
     * no coroutines left, otherwise once they have all ended. Safe to call more than once.
     */
    fun handlerEnded() {
        synchronized(lock) {
            if (ended) return
            ended = true
        }
        if (scopes.none { it.hasCoroutines() }) {
            release()
        } else {
            runs.watch {
                try {
                    awaitCoroutines()
                } finally {
                    release()
                }
            }
        }
    }

    private fun release() {
        if (!released.compareAndSet(false, true)) return
        timeLimit?.cancel()
        runs.release()
    }

    private fun stop() {
        synchronized(lock) { timedOut = true }
        val cause = CancellationException("The demo ran past its time limit (MAX_DEMO_RUN_SECONDS)")
        scopes.forEach { it.coroutineContext.job.cancelChildren(cause) }
    }

    // A scope's own Job never completes; its children are the demo's coroutines. One may launch
    // into another of the demo's scopes before it ends, so wait until no scope has any left.
    private suspend fun awaitCoroutines() {
        while (scopes.any { it.hasCoroutines() }) {
            scopes.forEach { scope ->
                scope.coroutineContext.job.children
                    .toList()
                    .joinAll()
            }
        }
    }

    private fun VizScope.hasCoroutines() = coroutineContext.job.children.any()
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
    return run.newScope { VizScope(this, context) }
}
