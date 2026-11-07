package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.session.EventStore
import com.jh.proj.coroutineviz.session.EventStoreInterface
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
 *
 * The clock is read as the session stores each event, under its send lock, on the sending thread.
 * Most steps run on the test dispatcher; an event sent from another thread (vizcore's deferred
 * terminal events run on the session scope) is listed in [VirtualDemoRun.offClock]: the clock may
 * have moved on while it was on its way, so its time is not a scripted time.
 */
internal class VirtualDemoRun(
    val events: List<VizEvent>,
    private val millisBySeq: Map<Long, Long>,
    val scriptedMillis: Long,
    /** Seqs first seen from a thread other than the test's: their [at] is when they were seen, not a scripted time. */
    val offClock: Set<Long> = emptySet(),
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
    val sentAt = ConcurrentHashMap<Long, Long>()
    val offClock = ConcurrentHashMap.newKeySet<Long>()
    val testThread = Thread.currentThread()
    // Stamped as the session stores each event, under its send lock and on the sending thread, so
    // every event gets its own time and its own thread.
    val session =
        VizSession(name, eventStoreFactory = {
            StampingStore(EventStore(MAX_EVENTS)) { event ->
                sentAt[event.seq] = testScheduler.currentTime
                if (Thread.currentThread() != testThread) offClock += event.seq
            }
        })
    try {
        val demoRun = assertNotNull(DemoRuns(maxConcurrent = 0).tryStart())
        withContext(demoRun) { start(session, StandardTestDispatcher(testScheduler)) }.join()
        val scriptedMillis = testScheduler.currentTime
        val events = settled(session)
        return VirtualDemoRun(events, events.associate { it.seq to (sentAt[it.seq] ?: scriptedMillis) }, scriptedMillis, offClock.toSet())
    } finally {
        session.close()
    }
}

/** Calls [stamp] with each event the session stores, right after storing it. */
private class StampingStore(
    private val inner: EventStoreInterface,
    private val stamp: (VizEvent) -> Unit,
) : EventStoreInterface by inner {
    override fun record(event: VizEvent) {
        inner.record(event)
        stamp(event)
    }
}

private const val MAX_EVENTS = 100_000

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
