package com.jh.proj.coroutineviz.session.source.debugprobes

import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.events.dispatcher.DispatcherSelected
import com.jh.proj.coroutineviz.events.dispatcher.ThreadAssigned
import com.jh.proj.coroutineviz.session.VizSession
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Maps every row of the State-transition → VizEvent table (Research §RCO-02) and
 * asserts subtype, order, and RCO-03 attribution fields. Uses a real [VizSession]
 * so the [com.jh.proj.coroutineviz.session.EventContext] extension functions fill
 * seq/ts exactly as the FE renders them.
 */
class DebugProbesEventSynthesizerTest {
    private val synthesizer = DebugProbesEventSynthesizer()

    private fun session() = VizSession(sessionId = "test-session")

    private fun snap(
        keyToken: String,
        state: CoroState,
        label: String? = null,
        dispatcherName: String? = null,
        function: String? = null,
        fileName: String? = null,
        lineNumber: Int? = null,
        reason: String? = null,
        parentKey: CoroKey? = null,
        threadId: Long? = null,
        threadName: String? = null,
        lastObservedStackTrace: List<StackTraceElement>? = null,
    ) = CoroutineSnapshot(
        key = CoroKey(keyToken),
        state = state,
        label = label,
        dispatcherName = dispatcherName,
        function = function,
        fileName = fileName,
        lineNumber = lineNumber,
        reason = reason,
        lastObservedStackTrace = lastObservedStackTrace,
        parentKey = parentKey,
        threadId = threadId,
        threadName = threadName,
    )

    @Test
    fun `Appeared CREATED yields CoroutineCreated`() {
        val s = session()
        val events =
            synthesizer.synthesize(CoroutineDelta.Appeared(snap("a", CoroState.CREATED, label = "task")), s)

        assertEquals(1, events.size)
        val created = events.single()
        assertTrue(created is CoroutineCreated)
        assertEquals("task", created.label)
        assertEquals("debugprobes", created.scopeId)
        assertNull(created.parentCoroutineId)
    }

    @Test
    fun `Appeared RUNNING yields Created then Started`() {
        val s = session()
        val events = synthesizer.synthesize(CoroutineDelta.Appeared(snap("a", CoroState.RUNNING)), s)

        assertEquals(2, events.size)
        assertTrue(events[0] is CoroutineCreated)
        assertTrue(events[1] is CoroutineStarted)
        // seq is monotonic: Created before Started.
        assertTrue(events[0].seq < events[1].seq)
    }

    @Test
    fun `Appeared SUSPENDED yields Created, Started, Suspended`() {
        val s = session()
        val events =
            synthesizer.synthesize(
                CoroutineDelta.Appeared(
                    snap("a", CoroState.SUSPENDED, function = "fetch", fileName = "App.kt", lineNumber = 7, reason = "delay"),
                ),
                s,
            )

        assertEquals(3, events.size)
        assertTrue(events[0] is CoroutineCreated)
        assertTrue(events[1] is CoroutineStarted)
        val suspended = events[2]
        assertTrue(suspended is CoroutineSuspended)
        assertEquals("delay", suspended.reason)
        assertEquals("fetch", suspended.suspensionPoint?.function)
        assertEquals("App.kt", suspended.suspensionPoint?.fileName)
        assertEquals(7, suspended.suspensionPoint?.lineNumber)
    }

    @Test
    fun `StateChanged CREATED to RUNNING yields Started`() {
        val s = session()
        val events =
            synthesizer.synthesize(
                CoroutineDelta.StateChanged(CoroState.CREATED, CoroState.RUNNING, snap("a", CoroState.RUNNING)),
                s,
            )

        assertEquals(1, events.size)
        assertTrue(events.single() is CoroutineStarted)
    }

    @Test
    fun `StateChanged RUNNING to SUSPENDED yields Suspended with suspension point from snapshot`() {
        val s = session()
        val events =
            synthesizer.synthesize(
                CoroutineDelta.StateChanged(
                    CoroState.RUNNING,
                    CoroState.SUSPENDED,
                    snap("a", CoroState.SUSPENDED, function = "await", fileName = "Repo.kt", lineNumber = 33, reason = "await"),
                ),
                s,
            )

        assertEquals(1, events.size)
        val suspended = events.single()
        assertTrue(suspended is CoroutineSuspended)
        assertEquals("await", suspended.reason)
        assertEquals("await", suspended.suspensionPoint?.function)
        assertEquals("Repo.kt", suspended.suspensionPoint?.fileName)
        assertEquals(33, suspended.suspensionPoint?.lineNumber)
        assertEquals("await", suspended.suspensionPoint?.reason)
    }

    @Test
    fun `StateChanged CREATED to SUSPENDED yields Started then Suspended (WR-01)`() {
        val s = session()
        val events =
            synthesizer.synthesize(
                CoroutineDelta.StateChanged(
                    CoroState.CREATED,
                    CoroState.SUSPENDED,
                    snap("a", CoroState.SUSPENDED, function = "fetch", fileName = "App.kt", lineNumber = 9, reason = "delay"),
                ),
                s,
            )

        // A coroutine that started+parked between polls must emit Started BEFORE
        // Suspended — never a suspend with no prior start (WR-01).
        assertEquals(2, events.size)
        assertTrue(events[0] is CoroutineStarted, "started must come first")
        val suspended = events[1]
        assertTrue(suspended is CoroutineSuspended, "suspended must follow started")
        assertEquals("delay", suspended.reason)
        assertEquals("fetch", suspended.suspensionPoint?.function)
        assertTrue(events[0].seq < events[1].seq, "Started seq precedes Suspended seq")
    }

    @Test
    fun `StateChanged SUSPENDED to RUNNING yields Resumed`() {
        val s = session()
        val events =
            synthesizer.synthesize(
                CoroutineDelta.StateChanged(CoroState.SUSPENDED, CoroState.RUNNING, snap("a", CoroState.RUNNING)),
                s,
            )

        assertEquals(1, events.size)
        assertTrue(events.single() is CoroutineResumed)
    }

    @Test
    fun `Vanished yields exactly one CoroutineCompleted`() {
        val s = session()
        val events =
            synthesizer.synthesize(CoroutineDelta.Vanished(snap("a", CoroState.RUNNING)), s)

        assertEquals(1, events.size)
        assertTrue(events.single() is CoroutineCompleted)
    }

    @Test
    fun `Appeared with a parentKey yields parentCoroutineId using the same dp- prefix as the parent id`() {
        val s = session()
        // child's parentKey points at the parent coroutine's own key ("p1").
        val events =
            synthesizer.synthesize(
                CoroutineDelta.Appeared(snap("c1", CoroState.CREATED, parentKey = CoroKey("p1"))),
                s,
            )

        val created = events.single()
        assertTrue(created is CoroutineCreated)
        // The child's parentCoroutineId MUST equal the parent's own derived id
        // ("dp-${parentKey.token}") so ProjectionService wires the tree edge.
        assertEquals("dp-p1", created.parentCoroutineId)
    }

    @Test
    fun `Appeared without a parentKey yields a null parentCoroutineId (tree root)`() {
        val s = session()
        val events =
            synthesizer.synthesize(CoroutineDelta.Appeared(snap("r1", CoroState.CREATED)), s)

        assertNull((events.single() as CoroutineCreated).parentCoroutineId)
    }

    @Test
    fun `scopeId is derived from dispatcherName when present (D-03 grouping)`() {
        val s = session()
        val events =
            synthesizer.synthesize(
                CoroutineDelta.Appeared(snap("a", CoroState.CREATED, dispatcherName = "Dispatchers.IO")),
                s,
            )

        // Since 15-08 an Appeared with a dispatcher also announces DispatcherSelected;
        // the created event (first) still carries the D-03 scopeId grouping.
        assertEquals("Dispatchers.IO", (events.first() as CoroutineCreated).scopeId)
    }

    @Test
    fun `scopeId falls back to the source id when dispatcherName is null`() {
        val s = session()
        val events =
            synthesizer.synthesize(CoroutineDelta.Appeared(snap("a", CoroState.CREATED)), s)

        assertEquals("debugprobes", (events.single() as CoroutineCreated).scopeId)
    }

    @Test
    fun `Appeared RUNNING with an observed thread emits ThreadAssigned with thread and dispatcher`() {
        val s = session()
        val events =
            synthesizer.synthesize(
                CoroutineDelta.Appeared(
                    snap("a", CoroState.RUNNING, dispatcherName = "Dispatchers.IO", threadId = 7L, threadName = "worker-1"),
                ),
                s,
            )

        val assigned = events.filterIsInstance<ThreadAssigned>().single()
        assertEquals(7L, assigned.threadId)
        assertEquals("worker-1", assigned.threadName)
        assertEquals("Dispatchers.IO", assigned.dispatcherName)
        // Ordering: thread assignment comes after created/started.
        assertTrue(
            events.indexOfFirst { it is CoroutineStarted } < events.indexOf(assigned),
            "ThreadAssigned must follow CoroutineStarted",
        )
    }

    @Test
    fun `Appeared with a dispatcher emits DispatcherSelected while scopeId routing stays`() {
        val s = session()
        val events =
            synthesizer.synthesize(
                CoroutineDelta.Appeared(snap("a", CoroState.RUNNING, dispatcherName = "Dispatchers.IO")),
                s,
            )

        val selected = events.filterIsInstance<DispatcherSelected>().single()
        assertEquals("Dispatchers.IO", selected.dispatcherName)
        assertEquals("Dispatchers.IO", selected.dispatcherId)
        // D-03 dispatcher grouping via scopeId is NOT moved — the dedicated event is additive.
        assertEquals("Dispatchers.IO", (events.first() as CoroutineCreated).scopeId)
    }

    @Test
    fun `StateChanged SUSPENDED to RUNNING with a thread emits Resumed then ThreadAssigned`() {
        val s = session()
        val events =
            synthesizer.synthesize(
                CoroutineDelta.StateChanged(
                    CoroState.SUSPENDED,
                    CoroState.RUNNING,
                    snap("a", CoroState.RUNNING, threadId = 12L, threadName = "main"),
                ),
                s,
            )

        assertEquals(2, events.size)
        assertTrue(events[0] is CoroutineResumed, "resumed must come first")
        val assigned = events[1]
        assertTrue(assigned is ThreadAssigned, "ThreadAssigned must follow resumed")
        assertEquals(12L, assigned.threadId)
        assertEquals("main", assigned.threadName)
    }

    @Test
    fun `snapshot without thread info emits no ThreadAssigned (no fabricated data)`() {
        val s = session()
        val appeared =
            synthesizer.synthesize(CoroutineDelta.Appeared(snap("a", CoroState.RUNNING)), s)
        val resumed =
            synthesizer.synthesize(
                CoroutineDelta.StateChanged(CoroState.SUSPENDED, CoroState.RUNNING, snap("a", CoroState.RUNNING)),
                s,
            )

        assertTrue(appeared.none { it is ThreadAssigned })
        assertTrue(resumed.none { it is ThreadAssigned })
    }

    @Test
    fun `DispatcherSelected is emitted on Appeared only, never on StateChanged`() {
        val s = session()
        val events =
            synthesizer.synthesize(
                CoroutineDelta.StateChanged(
                    CoroState.SUSPENDED,
                    CoroState.RUNNING,
                    snap("a", CoroState.RUNNING, dispatcherName = "Dispatchers.IO", threadId = 3L, threadName = "w"),
                ),
                s,
            )

        assertTrue(events.none { it is DispatcherSelected }, "dispatcher is announced once, at Appeared")
    }

    @Test
    fun `suspended carries the true suspension frame while created carries the launch site`() {
        val s = session()
        // Creation-derived fields say Bar.kt:7 (launch site); the last observed
        // stack's first user frame says Foo.kt:42 (true suspension point).
        val lastObserved =
            listOf(
                StackTraceElement("kotlinx.coroutines.DelayKt", "delay", "Delay.kt", 5),
                StackTraceElement("com.example.Foo", "fetch", "Foo.kt", 42),
            )
        val events =
            synthesizer.synthesize(
                CoroutineDelta.Appeared(
                    snap(
                        "a",
                        CoroState.SUSPENDED,
                        function = "startWork",
                        fileName = "Bar.kt",
                        lineNumber = 7,
                        reason = "delay",
                        lastObservedStackTrace = lastObserved,
                    ),
                ),
                s,
            )

        val created = events.first() as CoroutineCreated
        assertEquals("startWork", created.creationPoint?.function, "created carries the launch site")
        assertEquals("Bar.kt", created.creationPoint?.fileName)
        assertEquals(7, created.creationPoint?.lineNumber)

        val suspended = events.last() as CoroutineSuspended
        assertEquals("fetch", suspended.suspensionPoint?.function, "suspended carries the TRUE suspension frame")
        assertEquals("Foo.kt", suspended.suspensionPoint?.fileName)
        assertEquals(42, suspended.suspensionPoint?.lineNumber)
    }

    @Test
    fun `suspended falls back to the creation frame when the last observed stack has no user frame`() {
        val s = session()
        val allInfra =
            listOf(
                StackTraceElement("kotlinx.coroutines.DelayKt", "delay", "Delay.kt", 5),
                StackTraceElement("kotlin.coroutines.jvm.internal.BaseContinuationImpl", "resumeWith", "ContinuationImpl.kt", 33),
            )
        val events =
            synthesizer.synthesize(
                CoroutineDelta.StateChanged(
                    CoroState.RUNNING,
                    CoroState.SUSPENDED,
                    snap(
                        "a",
                        CoroState.SUSPENDED,
                        function = "startWork",
                        fileName = "Bar.kt",
                        lineNumber = 7,
                        reason = "delay",
                        lastObservedStackTrace = allInfra,
                    ),
                ),
                s,
            )

        val suspended = events.single() as CoroutineSuspended
        // Degrade, never null-out an available frame: creation-derived fallback.
        assertEquals("startWork", suspended.suspensionPoint?.function)
        assertEquals("Bar.kt", suspended.suspensionPoint?.fileName)
        assertEquals(7, suspended.suspensionPoint?.lineNumber)
    }

    @Test
    fun `created without creation-derived fields carries a null creationPoint (no regression)`() {
        val s = session()
        val events =
            synthesizer.synthesize(CoroutineDelta.Appeared(snap("a", CoroState.CREATED)), s)

        assertNull((events.single() as CoroutineCreated).creationPoint)
    }

    @Test
    fun `derived coroutineId and scopeId are stable and use the key token`() {
        val s = session()
        val e1 = synthesizer.synthesize(CoroutineDelta.Appeared(snap("k1", CoroState.CREATED)), s).single()
        val e2 = synthesizer.synthesize(CoroutineDelta.Vanished(snap("k1", CoroState.RUNNING)), s).single()

        // Same key token → same derived coroutineId across deltas (no double-identity).
        assertEquals((e1 as CoroutineCreated).coroutineId, (e2 as CoroutineCompleted).coroutineId)
        assertEquals("debugprobes", e1.scopeId)
        assertTrue(e1.coroutineId.startsWith("dp-"))
    }
}
