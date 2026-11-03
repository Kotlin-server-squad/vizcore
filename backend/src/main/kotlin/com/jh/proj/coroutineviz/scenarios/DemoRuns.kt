package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.wrappers.VizScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
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
    // Suspends until a session is deleted; null where nothing deletes sessions (tests).
    private val awaitSessionRemoved: (suspend (VizSession) -> Unit)? = null,
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

    internal fun watchRemoval(
        run: DemoRun,
        scope: VizScope,
        session: VizSession,
    ) {
        val awaitRemoved = awaitSessionRemoved ?: return
        val watcher =
            watch {
                awaitRemoved(session)
                run.sessionRemoved(scope)
            }
        // Stop listening once the scope ends, whether the session was deleted or not.
        scope.coroutineContext.job.invokeOnCompletion { watcher.cancel() }
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

    // Guards [ended], [timedOut] and [request]: a scope or request is never added while the run ends.
    private val lock = Any()
    private var ended = false
    private var timedOut = false
    private val released = AtomicBoolean(false)

    @Volatile
    private var timeLimit: Job? = null

    // Guarded by [lock].
    private var request: Job? = null

    /**
     * Runs the demo's request [block] in this slot, and frees the slot when the request and the
     * demo's coroutines are done. At the time limit the request is stopped too, so a request that
     * never gets to start a coroutine (a client sending its body very slowly) cannot keep the slot.
     */
    suspend fun <T> handle(block: suspend () -> T): T =
        try {
            withContext(this) {
                // Under the lock, so the time limit either sees this request or this request sees
                // that the time limit has passed.
                synchronized(lock) {
                    if (timedOut) throw CancellationException(TIME_LIMIT_MESSAGE)
                    request = coroutineContext.job
                }
                block()
            }
        } finally {
            handlerEnded()
        }

    internal fun startTimeLimit(maxRunMillis: Long) {
        if (maxRunMillis <= 0) return
        timeLimit =
            runs.watch {
                delay(maxRunMillis)
                stop()
            }
    }

    internal fun newScope(
        session: VizSession,
        create: () -> VizScope,
    ): VizScope =
        synchronized(lock) {
            check(!ended) { "This demo's request has ended, so it cannot start more work (MAX_CONCURRENT_DEMOS)" }
            check(!timedOut) { TIME_LIMIT_MESSAGE }
            create().also { scope ->
                scopes += scope
                runs.watchRemoval(this, scope, session)
            }
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
        // The demo is over: a scope it handed out must not run more work outside the slot.
        endScopes(CancellationException("The demo has ended"))
        runs.release()
    }

    /** True once the time limit has stopped this demo. */
    internal val stoppedByTimeLimit: Boolean get() = synchronized(lock) { timedOut }

    private fun stop() {
        val running =
            synchronized(lock) {
                timedOut = true
                request
            }
        val cause = CancellationException(TIME_LIMIT_MESSAGE)
        // Cancelling the scopes themselves, not only their coroutines, also stops anything
        // launched into them afterwards.
        endScopes(cause)
        running?.cancel(cause)
    }

    private fun endScopes(cause: CancellationException) = scopes.forEach { it.coroutineContext.job.cancel(cause) }

    /** The session a demo scope runs in was deleted: its coroutines stop, which frees the slot. */
    internal fun sessionRemoved(scope: VizScope) = scope.coroutineContext.job.cancel(CancellationException("The session was deleted"))

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

private const val TIME_LIMIT_MESSAGE = "The demo ran past its time limit (MAX_DEMO_RUN_SECONDS)"

private val demoLogger = LoggerFactory.getLogger("DemoRuns")

/**
 * Marks this exception as thrown on purpose by a demo, to show a failure in the session. Its type
 * and message are unchanged, so the session shows the same failure.
 */
internal fun <T : Throwable> T.intendedByDemo(): T = apply { addSuppressed(IntendedByDemo) }

/** The failure a demo shows on purpose; see [intendedByDemo]. */
internal fun demoFailure(message: String): IllegalStateException = IllegalStateException(message).intendedByDemo()

private object IntendedByDemo : Throwable("thrown on purpose by a demo", null, false, false)

private fun Throwable.isIntendedByDemo(): Boolean =
    generateSequence(this) { it.cause }.any { e -> e.suppressed.any { it === IntendedByDemo } }

/**
 * Several demos fail on purpose (Exception Handling, Retry, Supervisor, Circuit Breaker, the
 * real-life demos' failure options, a custom "throw" action). Without a handler the exception
 * reached the JVM's default handler, which printed a stack trace on every run of a working demo.
 * Those are logged in one line; any other failure is a bug and keeps its stack trace.
 */
private val demoFailureHandler =
    CoroutineExceptionHandler { context, e ->
        val name = context[CoroutineName]?.name
        if (e.isIntendedByDemo()) {
            demoLogger.info("A demo coroutine failed on purpose ({}): {}", name, e.toString())
        } else {
            demoLogger.error("A demo coroutine failed ({})", name, e)
        }
    }

/**
 * The scope a demo runs its coroutines in. It counts against the demo's slot in [DemoRuns], so
 * it can only be made while the demo holds one: a demo that does not, fails instead of running
 * outside the limit. A [context] with its own exception handler replaces [demoFailureHandler].
 */
suspend fun VizSession.demoScope(context: CoroutineContext = EmptyCoroutineContext): VizScope {
    val run =
        checkNotNull(currentCoroutineContext()[DemoRun]) {
            "A demo can only run while it holds a slot (MAX_CONCURRENT_DEMOS); start it through demoPost or demoGet"
        }
    return run.newScope(this) { VizScope(this, demoFailureHandler + context) }
}
