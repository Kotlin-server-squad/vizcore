package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.flow.FlowCollectionCancelled
import com.jh.proj.coroutineviz.events.flow.FlowCollectionCompleted
import com.jh.proj.coroutineviz.events.flow.FlowCollectionStarted
import com.jh.proj.coroutineviz.events.flow.FlowCreated
import com.jh.proj.coroutineviz.events.flow.FlowOperatorApplied
import com.jh.proj.coroutineviz.events.flow.FlowValueEmitted
import com.jh.proj.coroutineviz.events.flow.FlowValueFiltered
import com.jh.proj.coroutineviz.events.flow.FlowValueTransformed
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InstrumentedFlowTest {
    private lateinit var session: VizSession

    @BeforeEach
    fun setup() {
        session = VizSession("test-flow-${System.currentTimeMillis()}")
    }

    // ========================================================================
    // CREATION TESTS
    // ========================================================================

    @Test
    fun `flow creation emits FlowCreated event`() {
        val flow = flowOf(1, 2, 3).instrumented(session, "flow-1", "Cold", "testFlow")

        val createdEvents = session.store.all().filterIsInstance<FlowCreated>()
        assertEquals(1, createdEvents.size)
        assertEquals("flow-1", createdEvents.first().flowId)
        assertEquals("Cold", createdEvents.first().flowType)
        assertEquals("testFlow", createdEvents.first().label)
    }

    // ========================================================================
    // COLLECTION LIFECYCLE TESTS
    // ========================================================================

    @Test
    fun `collecting flow emits collection lifecycle events`() =
        runTest {
            val flow = flowOf(1, 2, 3).instrumented(session, "flow-lifecycle", "Cold", "lifecycleFlow")

            val collected = mutableListOf<Int>()
            flow.collect { collected.add(it) }

            assertEquals(listOf(1, 2, 3), collected)

            val allEvents = session.store.all()

            // FlowCollectionStarted
            val startedEvents = allEvents.filterIsInstance<FlowCollectionStarted>()
            assertEquals(1, startedEvents.size)
            assertEquals("flow-lifecycle", startedEvents.first().flowId)

            // FlowValueEmitted - one per value
            val emittedEvents = allEvents.filterIsInstance<FlowValueEmitted>()
            assertEquals(3, emittedEvents.size)
            assertEquals(0, emittedEvents[0].sequenceNumber)
            assertEquals("1", emittedEvents[0].valuePreview)
            assertEquals(1, emittedEvents[1].sequenceNumber)
            assertEquals("2", emittedEvents[1].valuePreview)
            assertEquals(2, emittedEvents[2].sequenceNumber)
            assertEquals("3", emittedEvents[2].valuePreview)

            // FlowCollectionCompleted
            val completedEvents = allEvents.filterIsInstance<FlowCollectionCompleted>()
            assertEquals(1, completedEvents.size)
            assertEquals(3, completedEvents.first().totalEmissions)
        }

    // ========================================================================
    // OPERATOR TESTS
    // ========================================================================

    @Test
    fun `flow map operator emits FlowOperatorApplied and FlowValueTransformed`() =
        runTest {
            val flow = flowOf(1, 2, 3).instrumented(session, "flow-map-src", "Cold", "mapFlow")
            val mapped = flow.vizMap { it * 10 }

            val collected = mutableListOf<Int>()
            mapped.collect { collected.add(it) }

            assertEquals(listOf(10, 20, 30), collected)

            val allEvents = session.store.all()

            // FlowOperatorApplied for the map
            val operatorEvents = allEvents.filterIsInstance<FlowOperatorApplied>()
            assertEquals(1, operatorEvents.size)
            assertEquals("map", operatorEvents.first().operatorName)
            assertEquals("flow-map-src", operatorEvents.first().sourceFlowId)

            // FlowValueTransformed - one per value
            val transformedEvents = allEvents.filterIsInstance<FlowValueTransformed>()
            assertEquals(3, transformedEvents.size)
            assertEquals("1", transformedEvents[0].inputValuePreview)
            assertEquals("10", transformedEvents[0].outputValuePreview)
            assertEquals("2", transformedEvents[1].inputValuePreview)
            assertEquals("20", transformedEvents[1].outputValuePreview)
            assertEquals("3", transformedEvents[2].inputValuePreview)
            assertEquals("30", transformedEvents[2].outputValuePreview)
        }

    @Test
    fun `flow filter operator emits FlowValueFiltered with pass and drop`() =
        runTest {
            val flow = flowOf(1, 2, 3, 4, 5).instrumented(session, "flow-filter-src", "Cold", "filterFlow")
            val filtered = flow.vizFilter { it % 2 == 0 }

            val collected = mutableListOf<Int>()
            filtered.collect { collected.add(it) }

            // Only even numbers pass
            assertEquals(listOf(2, 4), collected)

            val allEvents = session.store.all()

            // FlowOperatorApplied for the filter
            val operatorEvents = allEvents.filterIsInstance<FlowOperatorApplied>()
            assertEquals(1, operatorEvents.size)
            assertEquals("filter", operatorEvents.first().operatorName)

            // FlowValueFiltered - one per input value (5 total)
            val filteredEvents = allEvents.filterIsInstance<FlowValueFiltered>()
            assertEquals(5, filteredEvents.size)

            // Check pass/drop status
            val passedValues = filteredEvents.filter { it.passed }.map { it.valuePreview }
            val droppedValues = filteredEvents.filter { !it.passed }.map { it.valuePreview }
            assertEquals(listOf("2", "4"), passedValues)
            assertEquals(listOf("1", "3", "5"), droppedValues)
        }

    // ========================================================================
    // CANCELLATION TESTS
    // ========================================================================

    @Test
    fun `flow collection cancellation emits FlowCollectionCancelled`() =
        runTest {
            val scope = VizScope(session, coroutineContext)

            // Create a flow that emits many values with delays
            val flow =
                scope.vizFlow<Int>(label = "cancelFlow") {
                    emit(1)
                    emit(2)
                    emit(3)
                    emit(4)
                    emit(5)
                }

            val collected = mutableListOf<Int>()
            // Collect in a child coroutine and cancel after receiving 2 values
            val job =
                launch {
                    flow.collect {
                        collected.add(it)
                        if (collected.size == 2) {
                            throw kotlinx.coroutines.CancellationException("collected enough")
                        }
                    }
                }

            job.join()

            assertEquals(listOf(1, 2), collected)

            val allEvents = session.store.all()

            val cancelledEvents = allEvents.filterIsInstance<FlowCollectionCancelled>()
            assertTrue(cancelledEvents.isNotEmpty(), "Expected at least one FlowCollectionCancelled event")
            assertEquals(2, cancelledEvents.first().emittedCount)
        }

    // ========================================================================
    // MULTIPLE COLLECTORS TESTS
    // ========================================================================

    @Test
    fun `multiple collectors each get their own events`() =
        runTest {
            val flow = flowOf(1, 2, 3).instrumented(session, "flow-multi", "Cold", "multiFlow")

            // First collection
            val collected1 = mutableListOf<Int>()
            flow.collect { collected1.add(it) }

            // Second collection
            val collected2 = mutableListOf<Int>()
            flow.collect { collected2.add(it) }

            assertEquals(listOf(1, 2, 3), collected1)
            assertEquals(listOf(1, 2, 3), collected2)

            val allEvents = session.store.all()

            // Two FlowCollectionStarted events (one per collect call)
            val startedEvents = allEvents.filterIsInstance<FlowCollectionStarted>()
            assertEquals(2, startedEvents.size)

            // Two FlowCollectionCompleted events
            val completedEvents = allEvents.filterIsInstance<FlowCollectionCompleted>()
            assertEquals(2, completedEvents.size)

            // 6 FlowValueEmitted events total (3 per collection)
            val emittedEvents = allEvents.filterIsInstance<FlowValueEmitted>()
            assertEquals(6, emittedEvents.size)

            // Each collection has its own collectorId
            val collectorIds = startedEvents.map { it.collectorId }.toSet()
            assertEquals(2, collectorIds.size, "Each collection should have a unique collectorId")
        }

    // ========================================================================
    // EMPTY FLOW TESTS
    // ========================================================================

    @Test
    fun `empty flow emits collection with zero emissions`() =
        runTest {
            val flow =
                kotlinx.coroutines.flow
                    .emptyFlow<Int>()
                    .instrumented(session, "flow-empty", "Cold", "emptyFlow")

            flow.collect { }

            val allEvents = session.store.all()

            // FlowCollectionStarted
            val startedEvents = allEvents.filterIsInstance<FlowCollectionStarted>()
            assertEquals(1, startedEvents.size)

            // No FlowValueEmitted
            val emittedEvents = allEvents.filterIsInstance<FlowValueEmitted>()
            assertEquals(0, emittedEvents.size)

            // FlowCollectionCompleted with 0 emissions
            val completedEvents = allEvents.filterIsInstance<FlowCollectionCompleted>()
            assertEquals(1, completedEvents.size)
            assertEquals(0, completedEvents.first().totalEmissions)
        }

    // ========================================================================
    // OPERATOR CHAIN TESTS
    // ========================================================================

    @Test
    fun `flow operator chain tracks operator index`() =
        runTest {
            val flow = flowOf(1, 2, 3, 4, 5).instrumented(session, "flow-chain-src", "Cold", "chainFlow")

            // Chain: filter -> map (two operators)
            val chained =
                flow
                    .vizFilter { it > 2 }
                    .vizMap { it * 100 }

            val collected = mutableListOf<Int>()
            chained.collect { collected.add(it) }

            assertEquals(listOf(300, 400, 500), collected)

            val allEvents = session.store.all()

            // Two FlowOperatorApplied events
            val operatorEvents = allEvents.filterIsInstance<FlowOperatorApplied>()
            assertEquals(2, operatorEvents.size)

            // First operator (filter) has operatorIndex 1
            val filterOp = operatorEvents.find { it.operatorName == "filter" }
            assertNotNull(filterOp)
            assertEquals(1, filterOp.operatorIndex)
            assertEquals("flow-chain-src", filterOp.sourceFlowId)

            // Second operator (map) has operatorIndex 2
            val mapOp = operatorEvents.find { it.operatorName == "map" }
            assertNotNull(mapOp)
            assertEquals(2, mapOp.operatorIndex)
            // map's source is the filter's output flow
            assertEquals(filterOp.flowId, mapOp.sourceFlowId)
        }
}
