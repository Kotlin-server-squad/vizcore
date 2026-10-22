package com.jh.proj.coroutineviz.health

import com.jh.proj.coroutineviz.events.CoroutineOrigin
import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.SuspensionProvenance
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.models.CoroutineState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SummaryUpdaterTest {
    private fun fold(script: EventScript): CoroutineSummary {
        val created = script.events.first() as CoroutineCreated
        return script.events.drop(1).fold(SummaryUpdater.created(created)) { s, e -> SummaryUpdater.update(s, e as CoroutineEvent) }
    }

    @Test
    fun `created records identity, origin and first observation`() {
        val script = EventScript()
        val point = SuspensionPoint(function = "handle", fileName = "Handler.kt", lineNumber = 7, reason = "creation")
        script.created("a", at = 100, parent = "p", label = "GET /x", origin = CoroutineOrigin.VIZCORE, creationPoint = point)

        val s = fold(script)

        assertEquals("p", s.parentId)
        assertEquals(CoroutineOrigin.VIZCORE, s.origin)
        assertEquals("GET /x", s.name)
        assertEquals(point, s.creationLocation)
        assertEquals(Transition(TransitionKind.CREATED, 100, 1), s.firstObserved)
        assertEquals(CoroutineState.CREATED, s.state)
        assertEquals(1, s.lastModifiedSeq)
    }

    @Test
    fun `start and resume are run starts, suspension sets everSuspended`() {
        val script = EventScript()
        script.created("a", at = 1)
        script.started("a", at = 2)
        script.suspended("a", at = 3)
        script.resumed("a", at = 4)

        val s = fold(script)

        assertEquals(Transition(TransitionKind.RESUMED, 4, 4), s.lastRunStart)
        assertTrue(s.everSuspended)
        assertEquals(CoroutineState.ACTIVE, s.state)
    }

    @Test
    fun `a real frame is kept with its provenance, a frame-less suspension marks it stale`() {
        val script = EventScript()
        val frame = SuspensionPoint(function = "load", fileName = "Repo.kt", lineNumber = 42, reason = "delay")
        script.created("a", at = 1)
        script.started("a", at = 2)
        script.suspended("a", at = 3, point = frame, provenance = SuspensionProvenance.REAL_FRAME)
        script.resumed("a", at = 4)
        script.suspended("a", at = 5, point = null, provenance = null)

        val s = fold(script)

        assertEquals(frame, s.lastSuspension)
        assertEquals(SuspensionProvenance.STALE, s.lastSuspensionProvenance)
    }

    @Test
    fun `provenance from an older producer stays unknown`() {
        val script = EventScript()
        script.created("a", at = 1)
        script.suspended("a", at = 2, provenance = null)

        val s = fold(script)
        assertNull(s.lastSuspensionProvenance)
        assertEquals(SuspensionPoint(function = "work", fileName = "App.kt", lineNumber = 10, reason = "delay"), s.lastSuspension)
    }

    @Test
    fun `a cancelled coroutine is cancelled and terminal`() {
        val script = EventScript()
        script.created("a", at = 1)
        script.cancelled("a", at = 2)

        val s = fold(script)
        assertTrue(s.cancelled)
        assertFalse(s.failed)
        assertEquals(CoroutineState.CANCELLED, s.state)
        assertEquals(2, s.terminal?.seq)
    }

    @Test
    fun `a dispatcher selection overwrites the dispatcher and bumps the seq`() {
        val script = EventScript()
        script.created("a", at = 1)
        script.dispatcherSelected("a", at = 2, name = "Dispatchers.IO")

        val s = fold(script)
        assertEquals("Dispatchers.IO", s.dispatcher)
        assertEquals(2, s.lastModifiedSeq)
    }

    @Test
    fun `a thread assignment without a dispatcher keeps the previous one`() {
        val script = EventScript()
        script.created("a", at = 1)
        script.dispatcherSelected("a", at = 2, name = "Dispatchers.IO")
        script.thread("a", at = 3, name = "t", dispatcher = null)

        val s = fold(script)
        assertEquals("Dispatchers.IO", s.dispatcher)
        assertEquals("t", s.lastThread?.threadName)
    }

    @Test
    fun `suspension provenance moves from fallback to stale to real`() {
        val script = EventScript()
        val fallback = SuspensionPoint(function = "create", fileName = "A.kt", lineNumber = 1, reason = "creation")
        val real = SuspensionPoint(function = "load", fileName = "B.kt", lineNumber = 2, reason = "delay")
        script.created("a", at = 1)
        script.suspended("a", at = 2, point = fallback, provenance = SuspensionProvenance.CREATION_FALLBACK)
        val first = fold(script)
        script.resumed("a", at = 3)
        script.suspended("a", at = 4, point = null, provenance = null)
        val second = fold(script)
        script.resumed("a", at = 5)
        script.suspended("a", at = 6, point = real, provenance = SuspensionProvenance.REAL_FRAME)
        val third = fold(script)

        assertEquals(SuspensionProvenance.CREATION_FALLBACK, first.lastSuspensionProvenance)
        assertEquals(fallback, first.lastSuspension)
        assertEquals(SuspensionProvenance.STALE, second.lastSuspensionProvenance)
        assertEquals(fallback, second.lastSuspension)
        assertEquals(SuspensionProvenance.REAL_FRAME, third.lastSuspensionProvenance)
        assertEquals(real, third.lastSuspension)
    }

    @Test
    fun `a completed body waits for children`() {
        val script = EventScript()
        script.created("a", at = 1)
        script.started("a", at = 2)
        script.bodyCompleted("a", at = 3)

        val s = fold(script)
        assertEquals(CoroutineState.WAITING_FOR_CHILDREN, s.state)
        assertFalse(s.isTerminal)
    }

    @Test
    fun `thread and dispatcher are recorded with the assignment seq`() {
        val script = EventScript()
        script.created("a", at = 1)
        script.started("a", at = 2)
        script.thread("a", at = 3, name = "lettuce-nioEventLoop-5-1", threadId = 51, dispatcher = "Dispatchers.Unconfined")

        val s = fold(script)

        assertEquals(ThreadObservation(51, "lettuce-nioEventLoop-5-1", 3), s.lastThread)
        assertEquals("Dispatchers.Unconfined", s.dispatcher)
        assertEquals(3, s.lastModifiedSeq)
    }

    @Test
    fun `a real failure is a failure, a cancellation exception is not`() {
        val real = EventScript()
        real.created("a", at = 1)
        real.failed("a", at = 2, type = "java.lang.IllegalStateException", message = "boom")
        val cancelled = EventScript()
        cancelled.created("a", at = 1)
        cancelled.failed("a", at = 2, type = "kotlinx.coroutines.JobCancellationException", message = "parent failed")

        val r = fold(real)
        val c = fold(cancelled)

        assertTrue(r.failed)
        assertEquals(FailureInfo("java.lang.IllegalStateException", "boom"), r.failure)
        assertEquals(CoroutineState.FAILED, r.state)
        assertFalse(c.failed)
        assertTrue(c.cancelled)
        assertEquals(CoroutineState.CANCELLED, c.state)
    }

    @Test
    fun `a failure of unknown type is a failure, not a cancellation`() {
        val script = EventScript()
        script.created("a", at = 1)
        script.failed("a", at = 2, type = null, message = null)

        val s = fold(script)
        assertTrue(s.failed)
        assertFalse(s.cancelled)
    }

    @Test
    fun `any event about a coroutine advances its last observation`() {
        val script = EventScript()
        script.created("a", at = sec(0))
        script.waitingForChildren("a", at = sec(40))

        val s = fold(script)
        assertEquals(sec(40), s.lastObservedNanos)
        assertEquals(2, s.lastModifiedSeq)
        assertEquals(TransitionKind.CREATED, s.lastTransition.kind)
    }

    @Test
    fun `a terminal summary ignores later events`() {
        val script = EventScript()
        script.created("a", at = 1)
        script.completed("a", at = 2)
        val done = fold(script)
        script.thread("a", at = 3, name = "late")

        assertSame(done, SummaryUpdater.update(done, script.events.last() as CoroutineEvent))
    }
}
