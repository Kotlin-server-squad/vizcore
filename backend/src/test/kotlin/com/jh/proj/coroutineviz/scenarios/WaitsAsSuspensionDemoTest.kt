package com.jh.proj.coroutineviz.scenarios

import com.jh.proj.coroutineviz.events.CoroutineEvent
import com.jh.proj.coroutineviz.events.SemaphoreCreated
import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.WaitKind
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineResumed
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.wrappers.VizScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * In the Combined demo (E-commerce orders) the orders share a payment gateway with two permits. Each order that
 * waits for it is shown as a suspension naming the gateway, and goes on afterwards.
 *
 * The demo runs on one virtual-time thread: every order reaches the gateway before any payment (a `delay`) ends,
 * so orders 1 and 2 take both permits and the other orders that reach payment must wait. Contention is certain,
 * not a matter of timing.
 */
class WaitsAsSuspensionDemoTest {
    private fun VizEvent.endsCoroutine(id: String) =
        (this is CoroutineCompleted || this is CoroutineCancelled || this is CoroutineFailed) &&
            (this as CoroutineEvent).coroutineId == id

    @Test
    fun `an order waiting for the payment gateway is suspended, then resumed before it ends`() =
        runTest {
            val session = VizSession("combined-waits")
            try {
                SyncScenarios.ecommerceOrderProcessing(VizScope(session, context = StandardTestDispatcher(testScheduler)))

                val events = session.store.all()
                val gateway = events.filterIsInstance<SemaphoreCreated>().single { it.semaphoreLabel == "payment-gateway" }.semaphoreId
                val waits = events.filterIsInstance<CoroutineSuspended>().filter { it.waitKind == WaitKind.SEMAPHORE }
                assertTrue(waits.size >= 2, "only ${waits.size} orders waited for the payment gateway")
                for (wait in waits) {
                    assertTrue(wait.resourceId == gateway, "${wait.label} waited for ${wait.resourceId}")
                    val end = assertNotNull(events.firstOrNull { it.endsCoroutine(wait.coroutineId) }, "${wait.label} never ended")
                    val resumedBeforeEnd =
                        events.any {
                            it is CoroutineResumed && it.coroutineId == wait.coroutineId && it.seq > wait.seq && it.seq < end.seq
                        }
                    assertTrue(resumedBeforeEnd, "${wait.label} stays suspended until it ends")
                }
            } finally {
                session.close()
            }
        }
}
