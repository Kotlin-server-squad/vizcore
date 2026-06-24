package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.delay

/**
 * Real-time poll for terminal-ordering tests.
 *
 * VizScope launches its coroutines on [VizSession.sessionScope], which uses the REAL
 * [kotlinx.coroutines.Dispatchers.Default]; its terminal `invokeOnCompletion` handlers
 * therefore emit in wall-clock time, NOT under a `runTest` virtual-time scheduler. A fixed
 * `delay(...)` inside `runTest` advances virtual time instantly without waiting for those
 * real-dispatcher handlers, which is the race that made the terminal-ordering tests flaky on
 * loaded CI runners (the expected CoroutineCancelled/CoroutineFailed was simply not in the
 * store yet when the assertion read it).
 *
 * Call this from a [kotlinx.coroutines.runBlocking] body after triggering completion: it polls
 * [VizSession.store] until an event matching [predicate] appears, or [timeoutMs] elapses. On
 * timeout it returns quietly and lets the caller's assertion produce the meaningful failure
 * message (the same behaviour the test had before, just without the race).
 */
internal suspend fun awaitTerminalLabel(
    session: VizSession,
    timeoutMs: Long = 5_000,
    pollMs: Long = 25,
    predicate: (VizEvent) -> Boolean,
) {
    var waited = 0L
    while (waited < timeoutMs) {
        if (session.store.all().any(predicate)) return
        delay(pollMs)
        waited += pollMs
    }
}
