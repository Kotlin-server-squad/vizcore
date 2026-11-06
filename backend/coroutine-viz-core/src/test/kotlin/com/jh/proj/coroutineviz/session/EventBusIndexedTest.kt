package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** The bus positions a subscriber can see (triage PR 3, P-6/P-8): gap-free indices in send order, a jump being evicted entries. */
@OptIn(ExperimentalCoroutinesApi::class)
class EventBusIndexedTest {
    private fun event(n: Int) = CoroutineCreated("s", n.toLong(), n.toLong(), "c$n", "j$n", null, "sc", null)

    @Test
    fun `sendIndexed returns the index the subscriber sees, starting after the subscription point`() =
        runTest(UnconfinedTestDispatcher()) {
            val bus = EventBus(capacity = 8)
            bus.send(event(0)) // before the subscription: index 1
            var start = -1L
            val seen = mutableListOf<Long>()
            val job = launch { bus.indexedStream { start = it }.collect { seen += it.index } }
            val second = bus.sendIndexed(event(1))
            val third = bus.sendIndexed(event(2))
            assertEquals(1L, start)
            assertEquals(listOf(2L, 3L), listOf(second, third))
            assertEquals(listOf(2L, 3L), seen)
            job.cancel()
        }

    @Test
    fun `a lagging subscriber sees the jump where entries were evicted for it, a fast one sees none`() =
        runTest(UnconfinedTestDispatcher()) {
            val bus = EventBus(capacity = 4)
            val gate = CompletableDeferred<Unit>()
            val slow = mutableListOf<Long>()
            val fast = mutableListOf<Long>()
            val slowJob =
                launch {
                    bus.indexedStream().collect {
                        gate.await()
                        slow += it.index
                    }
                }
            val fastJob = launch { bus.indexedStream().collect { fast += it.index } }
            repeat(10) { bus.send(event(it)) }
            gate.complete(Unit)
            assertEquals((1L..10L).toList(), fast)
            assertEquals(1L, slow.first(), "the entry it was holding when it fell behind")
            assertEquals((7L..10L).toList(), slow.drop(1), "entries 2..6 were evicted for it; the bus kept the newest 4")
            slowJob.cancel()
            fastJob.cancel()
        }
}
