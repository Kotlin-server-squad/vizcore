package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.wrappers.VizScope

/**
 * Flow visualization scenarios demonstrating cold flows, operators,
 * SharedFlow, and StateFlow with instrumented event tracking.
 */
object FlowScenarios {
    /**
     * Simple cold flow: emit 5 values, collect them.
     * Shows FlowCreated, FlowCollectionStarted, FlowValueEmitted, FlowCollectionCompleted.
     */
    suspend fun simpleFlow(scope: VizScope) {
        scope
            .vizLaunch("flow-collector") {
                val flow = vizFlowOf(1, 2, 3, 4, 5, label = "numbers")

                flow.collect { value ->
                    vizDelay(50)
                }
            }.join()
    }

    /**
     * Flow with map and filter operators.
     * Shows FlowOperatorApplied, FlowValueTransformed, FlowValueFiltered.
     */
    suspend fun flowOperators(scope: VizScope) {
        scope
            .vizLaunch("operator-chain") {
                val flow = vizFlowOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, label = "source")

                flow
                    .vizMap { it * 2 }
                    .vizFilter { it % 4 == 0 }
                    .vizMap { "Value: $it" }
                    .collect { value ->
                        vizDelay(30)
                    }
            }.join()
    }

    /**
     * StateFlow: mutable state holder with multiple observers.
     * Shows StateFlowValueChanged events as state updates.
     */
    suspend fun stateFlowDemo(scope: VizScope) {
        scope
            .vizLaunch("stateflow-demo") {
                val counter = vizStateFlow(0, label = "counter")

                // Observer 1
                val observer1 =
                    vizLaunch("observer-1") {
                        var collected = 0
                        counter.collect { value ->
                            collected++
                            if (collected >= 6) return@collect
                        }
                    }

                // Observer 2
                val observer2 =
                    vizLaunch("observer-2") {
                        var collected = 0
                        counter.collect { value ->
                            collected++
                            if (collected >= 6) return@collect
                        }
                    }

                // Updater
                vizLaunch("updater") {
                    for (i in 1..5) {
                        vizDelay(100)
                        counter.value = i
                    }
                    vizDelay(200)
                    observer1.cancel()
                    observer2.cancel()
                }
            }.join()
    }

    /**
     * SharedFlow: broadcast to multiple subscribers.
     * Shows SharedFlowEmission, SharedFlowSubscription events.
     */
    suspend fun sharedFlowDemo(scope: VizScope) {
        scope
            .vizLaunch("sharedflow-demo") {
                val events = vizSharedFlow<String>(replay = 1, label = "event-bus")

                // Subscriber 1
                val sub1 =
                    vizLaunch("subscriber-1") {
                        var count = 0
                        events.collect { msg ->
                            count++
                            vizDelay(20)
                            if (count >= 5) return@collect
                        }
                    }

                // Subscriber 2
                val sub2 =
                    vizLaunch("subscriber-2") {
                        var count = 0
                        events.collect { msg ->
                            count++
                            vizDelay(20)
                            if (count >= 5) return@collect
                        }
                    }

                // Publisher
                vizLaunch("publisher") {
                    vizDelay(50) // Let subscribers attach
                    for (i in 1..5) {
                        events.emit("Event #$i")
                        vizDelay(80)
                    }
                    vizDelay(200)
                    sub1.cancel()
                    sub2.cancel()
                }
            }.join()
    }
}
