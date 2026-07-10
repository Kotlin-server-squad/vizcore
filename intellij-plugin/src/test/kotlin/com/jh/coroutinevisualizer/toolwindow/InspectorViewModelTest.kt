package com.jh.coroutinevisualizer.toolwindow

import com.jh.coroutinevisualizer.api.HierarchyNodeDto
import com.jh.coroutinevisualizer.api.SuspensionPointDto
import com.jh.coroutinevisualizer.api.TimelineDto
import com.jh.coroutinevisualizer.api.TimelineEventDto
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class InspectorViewModelTest {
    @Test fun `formats nanos durations to approximate ms and s`() {
        assertEquals("~340ms", InspectorViewModel.formatApproxNanos(340_000_000))
        assertEquals("~1.2s", InspectorViewModel.formatApproxNanos(1_200_000_000))
        assertEquals("—", InspectorViewModel.formatApproxNanos(null))
    }

    @Test fun `extracts suspended-at from the latest coroutine-suspended event`() {
        val timeline =
            TimelineDto(
                coroutineId = "c",
                name = "db-query",
                state = "SUSPENDED",
                activeDuration = 80_000_000,
                suspendedDuration = 340_000_000,
                totalDuration = 420_000_000,
                events =
                    listOf(
                        TimelineEventDto(seq = 1, kind = "coroutine.created"),
                        TimelineEventDto(
                            seq = 2,
                            kind = "coroutine.suspended",
                            reason = "delay",
                            suspensionPoint =
                                SuspensionPointDto(
                                    function = "run",
                                    fileName = "OrderService.kt",
                                    lineNumber = 77,
                                    reason = "delay",
                                ),
                        ),
                    ),
            )
        val node =
            HierarchyNodeDto(
                id = "c",
                parentId = null,
                name = "db-query",
                scopeId = "req",
                state = "SUSPENDED",
                jobId = "j",
                dispatcherName = "IO",
            )
        val vm = InspectorViewModel.from(timeline, node)
        assertEquals("db-query", vm.name)
        assertEquals("delay", vm.suspendedAt?.reason)
        assertEquals("OrderService.kt", vm.suspendedAt?.fileName)
        assertEquals(77, vm.suspendedAt?.lineNumber)
        assertEquals("~340ms", vm.suspendedLabel)
        assertEquals("~80ms", vm.activeLabel)
    }

    @Test fun `null timeline yields a minimal vm from the node`() {
        val node = HierarchyNodeDto(id = "c", parentId = null, name = "x", scopeId = "s", state = "RUNNING", jobId = "j")
        val vm = InspectorViewModel.from(null, node)
        assertEquals("x", vm.name)
        assertEquals(null, vm.suspendedAt)
    }

    @Test fun `populates thread and dispatcher from the node`() {
        val node =
            HierarchyNodeDto(
                id = "c",
                parentId = null,
                name = "x",
                scopeId = "s",
                state = "RUNNING",
                jobId = "j",
                currentThreadName = "DefaultDispatcher-worker-1",
                dispatcherName = "Default",
            )
        val vm = InspectorViewModel.from(null, node)
        assertEquals("DefaultDispatcher-worker-1", vm.threadName)
        assertEquals("Default", vm.dispatcherName)
    }

    @Test fun `populates exception when the node has one`() {
        val node =
            HierarchyNodeDto(
                id = "c",
                parentId = null,
                name = "x",
                scopeId = "s",
                state = "FAILED",
                jobId = "j",
                exceptionType = "IllegalStateException",
                exceptionMessage = "boom",
            )
        val vm = InspectorViewModel.from(null, node)
        assertEquals("IllegalStateException", vm.exceptionType)
        assertEquals("boom", vm.exceptionMessage)
    }

    @Test fun `leaves exception null when the node has none`() {
        val node = HierarchyNodeDto(id = "c", parentId = null, name = "x", scopeId = "s", state = "RUNNING", jobId = "j")
        val vm = InspectorViewModel.from(null, node)
        assertEquals(null, vm.exceptionType)
        assertEquals(null, vm.exceptionMessage)
    }

    @Test fun `builds events with relative labels from the first event`() {
        val timeline =
            TimelineDto(
                coroutineId = "c",
                name = "db-query",
                state = "SUSPENDED",
                events =
                    listOf(
                        TimelineEventDto(seq = 1, tsNanos = 1_000_000_000, kind = "coroutine.created"),
                        TimelineEventDto(
                            seq = 2,
                            tsNanos = 1_340_000_000,
                            kind = "coroutine.suspended",
                            reason = "delay",
                        ),
                    ),
            )
        val vm = InspectorViewModel.from(timeline, null)
        assertEquals(2, vm.events.size)
        assertEquals("coroutine.created", vm.events[0].kind)
        assertEquals("~0ms", vm.events[0].relativeLabel)
        assertEquals("coroutine.suspended", vm.events[1].kind)
        assertEquals("delay", vm.events[1].reason)
        assertEquals("~340ms", vm.events[1].relativeLabel)
    }

    @Test fun `event reason falls back to suspension point reason`() {
        val timeline =
            TimelineDto(
                coroutineId = "c",
                name = "db-query",
                state = "SUSPENDED",
                events =
                    listOf(
                        TimelineEventDto(
                            seq = 1,
                            tsNanos = 5,
                            kind = "coroutine.suspended",
                            suspensionPoint =
                                SuspensionPointDto(function = "run", reason = "receive"),
                        ),
                    ),
            )
        val vm = InspectorViewModel.from(timeline, null)
        assertEquals("receive", vm.events.single().reason)
    }

    @Test fun `empty timeline yields no events`() {
        val vm = InspectorViewModel.from(null, null)
        assertEquals(emptyList(), vm.events)
    }

    @Test fun `populates identity fields and children counts from the node`() {
        val node =
            HierarchyNodeDto(
                id = "c",
                parentId = null,
                children = listOf("a", "b", "d"),
                name = "x",
                scopeId = "req-1",
                state = "RUNNING",
                jobId = "job-9",
                activeChildrenCount = 2,
            )
        val vm = InspectorViewModel.from(null, node)
        assertEquals("job-9", vm.jobId)
        assertEquals("req-1", vm.scopeId)
        assertEquals(2, vm.activeChildrenCount)
        assertEquals(3, vm.childrenCount)
    }

    @Test fun `blank job and scope map to null`() {
        val node = HierarchyNodeDto(id = "c", parentId = null, name = "x", scopeId = "", state = "RUNNING", jobId = "")
        val vm = InspectorViewModel.from(null, node)
        assertEquals(null, vm.jobId)
        assertEquals(null, vm.scopeId)
    }

    @Test fun `running flag reflects completedAtNanos`() {
        val running = HierarchyNodeDto(id = "c", parentId = null, name = "x", scopeId = "s", state = "RUNNING", jobId = "j")
        assertEquals(true, InspectorViewModel.from(null, running).running)
        val done =
            HierarchyNodeDto(
                id = "c",
                parentId = null,
                name = "x",
                scopeId = "s",
                state = "COMPLETED",
                jobId = "j",
                completedAtNanos = 123,
            )
        assertEquals(false, InspectorViewModel.from(null, done).running)
    }

    @Test fun `lifetime label mirrors total duration`() {
        val timeline = TimelineDto(coroutineId = "c", name = "x", state = "COMPLETED", totalDuration = 1_200_000_000)
        val vm = InspectorViewModel.from(timeline, null)
        assertEquals("~1.2s", vm.lifetimeLabel)
    }

    @Test fun `builds suspension history from coroutine-suspended events only`() {
        val timeline =
            TimelineDto(
                coroutineId = "c",
                name = "x",
                state = "SUSPENDED",
                events =
                    listOf(
                        TimelineEventDto(
                            seq = 1,
                            kind = "coroutine.created",
                            suspensionPoint =
                                SuspensionPointDto(function = "launch", fileName = "Launch.kt", lineNumber = 5, reason = "launch"),
                        ),
                        TimelineEventDto(
                            seq = 2,
                            kind = "coroutine.suspended",
                            reason = "delay",
                            suspensionPoint =
                                SuspensionPointDto(function = "run", fileName = "A.kt", lineNumber = 10, reason = "delay"),
                        ),
                        TimelineEventDto(
                            seq = 3,
                            kind = "coroutine.suspended",
                            suspensionPoint =
                                SuspensionPointDto(function = "recv", fileName = "B.kt", lineNumber = 20, reason = "receive"),
                        ),
                    ),
            )
        val vm = InspectorViewModel.from(timeline, null)
        assertEquals(2, vm.suspensionHistory.size)
        assertEquals("A.kt", vm.suspensionHistory[0].fileName)
        assertEquals(10, vm.suspensionHistory[0].lineNumber)
        assertEquals("run · delay", vm.suspensionHistory[0].reason)
        assertEquals("B.kt", vm.suspensionHistory[1].fileName)
        assertEquals("recv · receive", vm.suspensionHistory[1].reason)
    }

    @Test fun `suspension history is empty without suspension points`() {
        val vm = InspectorViewModel.from(null, null)
        assertEquals(emptyList(), vm.suspensionHistory)
    }

    @Test fun `never-suspended timeline falls back to the durable node suspension point`() {
        val timeline =
            TimelineDto(
                coroutineId = "c",
                name = "worker",
                state = "RUNNING",
                events =
                    listOf(
                        TimelineEventDto(
                            seq = 1,
                            kind = "coroutine.created",
                            suspensionPoint =
                                SuspensionPointDto(function = "launchWork", fileName = "Worker.kt", lineNumber = 42, reason = "launch"),
                        ),
                        TimelineEventDto(seq = 2, kind = "coroutine.started"),
                    ),
            )
        val node =
            HierarchyNodeDto(
                id = "c",
                parentId = null,
                name = "worker",
                scopeId = "s",
                state = "RUNNING",
                jobId = "j",
                lastSuspensionPoint =
                    SuspensionPointDto(function = "await", fileName = "Svc.kt", lineNumber = 88, reason = "await"),
            )
        val vm = InspectorViewModel.from(timeline, node)
        // The launch frame riding coroutine.created must NOT be captioned "Suspended at";
        // suspendedAt falls back to the durable node point (Svc.kt:88, not Worker.kt:42).
        assertEquals("Svc.kt", vm.suspendedAt?.fileName)
        assertEquals(88, vm.suspendedAt?.lineNumber)
        assertEquals(emptyList(), vm.suspensionHistory)
        // launchedRef stays unregressed — the launch frame still resolves as Launched at.
        assertEquals("Worker.kt", vm.launchedAt?.fileName)
        assertEquals(42, vm.launchedAt?.lineNumber)
    }

    @Test fun `never-suspended timeline without a durable point shows no suspended-at`() {
        val timeline =
            TimelineDto(
                coroutineId = "c",
                name = "worker",
                state = "RUNNING",
                events =
                    listOf(
                        TimelineEventDto(
                            seq = 1,
                            kind = "coroutine.created",
                            suspensionPoint =
                                SuspensionPointDto(function = "launchWork", fileName = "Worker.kt", lineNumber = 42, reason = "launch"),
                        ),
                        TimelineEventDto(seq = 2, kind = "coroutine.started"),
                    ),
            )
        val vm = InspectorViewModel.from(timeline, null)
        assertEquals(null, vm.suspendedAt)
        assertEquals(emptyList(), vm.suspensionHistory)
    }

    @Test fun `launchedAt matches the real coroutine-created wire kind`() {
        val timeline =
            TimelineDto(
                coroutineId = "c",
                name = "worker",
                state = "RUNNING",
                events =
                    listOf(
                        TimelineEventDto(
                            seq = 1,
                            kind = "coroutine.created",
                            suspensionPoint =
                                SuspensionPointDto(
                                    function = "launchWork",
                                    fileName = "Worker.kt",
                                    lineNumber = 42,
                                    reason = "launch",
                                ),
                        ),
                    ),
            )
        val vm = InspectorViewModel.from(timeline, null)
        assertEquals("Worker.kt", vm.launchedAt?.fileName)
        assertEquals(42, vm.launchedAt?.lineNumber)
    }

    @Test fun `falls back to node creation and last-suspension points when the timeline is evicted`() {
        val node =
            HierarchyNodeDto(
                id = "c",
                parentId = null,
                name = "x",
                scopeId = "s",
                state = "SUSPENDED",
                jobId = "j",
                creationPoint =
                    SuspensionPointDto(function = "main", fileName = "App.kt", lineNumber = 5, reason = "launch"),
                lastSuspensionPoint =
                    SuspensionPointDto(function = "await", fileName = "Svc.kt", lineNumber = 88, reason = "await"),
            )
        val vm = InspectorViewModel.from(null, node)
        assertEquals("App.kt", vm.launchedAt?.fileName)
        assertEquals(5, vm.launchedAt?.lineNumber)
        assertEquals("Svc.kt", vm.suspendedAt?.fileName)
        assertEquals(88, vm.suspendedAt?.lineNumber)
    }

    @Test fun `running coroutine shows a live lifetime computed from createdAtNanos`() {
        val node =
            HierarchyNodeDto(
                id = "c",
                parentId = null,
                name = "x",
                scopeId = "s",
                state = "RUNNING",
                jobId = "j",
                createdAtNanos = 1_000_000_000,
            )
        val vm = InspectorViewModel.from(null, node, nowNanos = 1_340_000_000)
        assertEquals("~340ms", vm.lifetimeLabel)
    }

    @Test fun `completed coroutine keeps its total-duration lifetime`() {
        val timeline = TimelineDto(coroutineId = "c", name = "x", state = "COMPLETED", totalDuration = 1_200_000_000)
        val node =
            HierarchyNodeDto(
                id = "c",
                parentId = null,
                name = "x",
                scopeId = "s",
                state = "COMPLETED",
                jobId = "j",
                completedAtNanos = 123,
                createdAtNanos = 1,
            )
        val vm = InspectorViewModel.from(timeline, node, nowNanos = 9_999_999_999)
        assertEquals("~1.2s", vm.lifetimeLabel)
    }

    @Test fun `timeline refs win over durable node fallbacks`() {
        val timeline =
            TimelineDto(
                coroutineId = "c",
                name = "x",
                state = "SUSPENDED",
                events =
                    listOf(
                        TimelineEventDto(
                            seq = 1,
                            kind = "coroutine.created",
                            suspensionPoint =
                                SuspensionPointDto(function = "f", fileName = "Fresh.kt", lineNumber = 1, reason = "launch"),
                        ),
                        TimelineEventDto(
                            seq = 2,
                            kind = "coroutine.suspended",
                            suspensionPoint =
                                SuspensionPointDto(function = "g", fileName = "FreshSusp.kt", lineNumber = 2, reason = "delay"),
                        ),
                    ),
            )
        val node =
            HierarchyNodeDto(
                id = "c",
                parentId = null,
                name = "x",
                scopeId = "s",
                state = "SUSPENDED",
                jobId = "j",
                creationPoint =
                    SuspensionPointDto(function = "old", fileName = "Stale.kt", lineNumber = 99, reason = "launch"),
                lastSuspensionPoint =
                    SuspensionPointDto(function = "old", fileName = "StaleSusp.kt", lineNumber = 98, reason = "await"),
            )
        val vm = InspectorViewModel.from(timeline, node)
        assertEquals("Fresh.kt", vm.launchedAt?.fileName)
        assertEquals("FreshSusp.kt", vm.suspendedAt?.fileName)
    }
}
