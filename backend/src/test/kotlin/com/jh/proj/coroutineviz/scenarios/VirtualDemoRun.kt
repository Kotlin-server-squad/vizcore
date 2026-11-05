package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * One run of a demo on virtual time: its events in order, the scripted millisecond at which each
 * was sent, and how long the whole run took. An event's own `tsNanos` is real time, which on
 * virtual time says nothing, so the time each event was sent is read from the test's clock.
 */
internal class VirtualDemoRun(
    val events: List<VizEvent>,
    private val millisBySeq: Map<Long, Long>,
    val scriptedMillis: Long,
) {
    /** The scripted millisecond at which [event] was sent. */
    fun at(event: VizEvent): Long = millisBySeq.getValue(event.seq)
}

/**
 * Runs a demo that takes a coroutine context on this test's virtual time, with a demo slot at
 * scale 1, and returns its run once every coroutine has ended.
 */
internal suspend fun TestScope.runOnVirtualTime(
    name: String,
    start: suspend (VizSession, CoroutineContext) -> Job,
): VirtualDemoRun {
    val session = VizSession(name)
    try {
        val sentAt = ConcurrentHashMap<Long, Long>()
        session.onEventEmitted = { session.store.all().forEach { sentAt.putIfAbsent(it.seq, testScheduler.currentTime) } }
        val demoRun = assertNotNull(DemoRuns(maxConcurrent = 0).tryStart())
        withContext(demoRun) { start(session, StandardTestDispatcher(testScheduler)) }.join()
        val scriptedMillis = testScheduler.currentTime
        val events = settled(session)
        return VirtualDemoRun(events, events.associate { it.seq to (sentAt[it.seq] ?: scriptedMillis) }, scriptedMillis)
    } finally {
        session.close()
    }
}

/** The session's events in order, once every coroutine's terminal event is in; those can follow its job's end by a moment. */
private suspend fun settled(session: VizSession): List<VizEvent> =
    withContext(Dispatchers.Default) {
        withTimeout(5.seconds) {
            while (session.store.all().count { it.isTerminal() } < session.store.all().count { it is CoroutineCreated }) {
                delay(5)
            }
        }
        session.store.all().sortedBy { it.seq }
    }

private fun VizEvent.isTerminal() = this is CoroutineCompleted || this is CoroutineCancelled || this is CoroutineFailed
