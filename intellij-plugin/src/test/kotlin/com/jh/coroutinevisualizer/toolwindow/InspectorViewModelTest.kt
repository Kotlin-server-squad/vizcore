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

    @Test fun `extracts suspended-at from latest event with a suspension point`() {
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
                        TimelineEventDto(seq = 1, kind = "CREATED"),
                        TimelineEventDto(
                            seq = 2,
                            kind = "SUSPENDED",
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
                        TimelineEventDto(seq = 1, tsNanos = 1_000_000_000, kind = "CREATED"),
                        TimelineEventDto(
                            seq = 2,
                            tsNanos = 1_340_000_000,
                            kind = "SUSPENDED",
                            reason = "delay",
                        ),
                    ),
            )
        val vm = InspectorViewModel.from(timeline, null)
        assertEquals(2, vm.events.size)
        assertEquals("CREATED", vm.events[0].kind)
        assertEquals("~0ms", vm.events[0].relativeLabel)
        assertEquals("SUSPENDED", vm.events[1].kind)
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
                            kind = "SUSPENDED",
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
}
