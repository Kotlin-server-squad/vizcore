package com.jh.coroutinevisualizer.poll

import com.intellij.openapi.diagnostic.Logger
import com.jh.coroutinevisualizer.api.VizcoreApiClient
import com.jh.coroutinevisualizer.model.SessionModel
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Plain, testable poll loop that drives the live view (replaces the deleted browser/loopback path).
 *
 * Lifecycle: [start] schedules a fixed-delay task on a daemon [ScheduledExecutorService]. Each tick
 * first resolves the correlation to a session id (404 = not bound yet, stays null, retried next
 * tick), then fetches `/hierarchy` + `/metrics` and delivers a built [SessionModel] via `onModel`.
 * Any exception in a tick goes to `onError` and the task keeps running (a thrown exception would
 * silently kill a scheduled task). [freeze] suspends deliveries without tearing down; [stop] cancels
 * the task and shuts the scheduler down.
 *
 * Structured concurrency: owns a single named daemon thread; never uses GlobalScope.
 */
class SessionPoller(
    private val client: VizcoreApiClient,
    private val intervalMs: Long,
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "vizcore-poller").apply { isDaemon = true } },
) {
    @Volatile var sessionId: String? = null

    @Volatile var frozen = false

    /** The resolved session id for the current correlation, or null until the agent binds it. */
    fun currentSessionId(): String? = sessionId

    private var future: ScheduledFuture<*>? = null
    private var onModel: ((SessionModel) -> Unit)? = null
    private var onError: ((Throwable) -> Unit)? = null

    fun start(
        correlation: String,
        onModel: (SessionModel) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        this.onModel = onModel
        this.onError = onError
        future = scheduler.scheduleWithFixedDelay({ tick(correlation) }, 0, intervalMs, TimeUnit.MILLISECONDS)
    }

    // Resilience: a scheduled task that throws is silently cancelled, so every failure mode
    // (transport, deserialization, callback) must be swallowed and surfaced via onError.
    @Suppress("TooGenericExceptionCaught")
    private fun tick(correlation: String) {
        if (frozen) return
        try {
            val id = sessionId
            if (id == null) {
                // 404 until the agent binds the correlation; resolve() returns null, retried next tick.
                sessionId = client.resolve(correlation)
                return
            }
            val hierarchy = client.hierarchy(id)
            val model = SessionModel.from(hierarchy, client.metrics(id))
            onModel?.invoke(model)
        } catch (e: Exception) {
            // Never let the scheduled task die; surface and keep polling.
            LOG.warn("Coroutine visualizer poll failed", e)
            onError?.invoke(e)
        }
    }

    fun freeze() {
        frozen = true
    }

    fun unfreeze() {
        frozen = false
    }

    fun stop() {
        future?.cancel(true)
        scheduler.shutdownNow()
    }

    private companion object {
        private val LOG = Logger.getInstance(SessionPoller::class.java)
    }
}
