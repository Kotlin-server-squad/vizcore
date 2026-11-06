package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.models.CoroutineState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [VizSession.sendIf]: the condition is checked against the snapshot every earlier event left, under the send lock. */
class VizSessionSendIfTest {
    @Test
    fun `an event is sent only while the snapshot still records the state it expects`() {
        val session = VizSession("send-if")
        val ctx = EventContext(session, "c", "j", null, "s", null)
        session.send(ctx.coroutineCreated())
        session.send(ctx.coroutineStarted())
        val running = { s: com.jh.proj.coroutineviz.models.RuntimeSnapshot -> s.coroutines["c"]?.state == CoroutineState.ACTIVE }
        assertTrue(session.sendIf(ctx.coroutineSuspended(reason = "delay"), running))
        assertFalse(session.sendIf(ctx.coroutineSuspended(reason = "delay"), running), "already recorded suspended")
        session.send(ctx.coroutineCompleted())
        assertFalse(session.sendIf(ctx.coroutineResumed()) { it.coroutines["c"]?.state == CoroutineState.SUSPENDED }, "after its end")
        assertEquals(
            listOf("CoroutineCreated", "CoroutineStarted", "CoroutineSuspended", "CoroutineCompleted"),
            session.store.all().map { it.kind },
        )
        session.close()
    }
}
