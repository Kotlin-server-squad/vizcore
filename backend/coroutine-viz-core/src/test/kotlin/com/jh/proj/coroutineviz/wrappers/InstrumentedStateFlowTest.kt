package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class InstrumentedStateFlowTest {
    /**
     * `subscriptionCount` must follow the collectors as they come and go, as it does on a
     * MutableStateFlow. It used to return a snapshot taken when it was read, so waiting on it
     * for a new collector never ended.
     */
    @Test
    fun `subscriptionCount follows collectors as they subscribe and leave`() =
        runBlocking {
            val session = VizSession("state-flow-subscriptions")
            val scope = VizScope(session)
            val total = scope.vizStateFlow(0, label = "total")
            val count = total.subscriptionCount

            val collector = launch { total.collect { } }
            withTimeout(5_000) { count.first { it == 1 } }

            collector.cancel()
            withTimeout(5_000) { count.first { it == 0 } }
            assertEquals(0, total.subscriptionCount.value)
            session.close()
        }
}
