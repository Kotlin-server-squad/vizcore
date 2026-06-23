package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.coroutine.CoroutineBodyCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCancelled
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Regression for F8 (2026-06-22): when a coroutine launches children that outlive its own body
 * (fire-and-forget within the structured scope) and those children are cancelled during teardown
 * by a sibling, the recorded event stream must still respect structured-concurrency ordering:
 *
 *  - (A) every child's CoroutineCreated precedes the parent's CoroutineBodyCompleted — Created is
 *        emitted at launch time, not on the child's first dispatch; and
 *  - (B1) the parent's terminal event has a higher seq than every child's terminal event — the
 *         parent defers its terminal emission until all children have emitted theirs.
 *
 * This is the shape that the SharedFlow scenario exercises (publisher cancels two subscribers at
 * teardown); before the fix the parent's terminal could race ahead of a child's, tripping the
 * ParentNotCompletedBeforeChildren validator.
 */
class VizScopeFireForgetOrderingTest {
    @Test
    fun `parent terminal is ordered after fire-and-forget children cancelled at teardown`() =
        runBlocking {
            val session = VizSession("test-f8-fireforget")
            val viz = VizScope(session, context = CoroutineScope(SupervisorJob()).coroutineContext)

            viz.vizLaunch("parent") {
                val c1 = vizLaunch("child-1") { while (true) { delay(20) } }
                val c2 = vizLaunch("child-2") { while (true) { delay(20) } }
                // sibling that cancels the two long-running children, then completes (teardown)
                vizLaunch("canceller") {
                    delay(60)
                    c1.cancel()
                    c2.cancel()
                }
            }.join()

            // Terminal events may be emitted just after join() on the deferred (racy) path — poll
            // until the parent + 3 children have all reached a terminal event (or time out).
            var waited = 0
            while (waited < 3000) {
                val terminals = session.store.all().count { it is CoroutineCompleted || it is CoroutineCancelled }
                if (terminals >= 4) break
                delay(50)
                waited += 50
            }

            val events = session.store.all()
            fun createdSeq(label: String) = events.first { it is CoroutineCreated && it.label == label }.seq
            fun bodyDoneSeq(label: String) = events.first { it is CoroutineBodyCompleted && it.label == label }.seq
            fun terminalSeq(label: String) =
                events.first {
                    (it is CoroutineCompleted && it.label == label) || (it is CoroutineCancelled && it.label == label)
                }.seq

            val childLabels = listOf("child-1", "child-2", "canceller")

            // (A) every child is Created before the parent's body completes.
            val parentBodyDone = bodyDoneSeq("parent")
            val parentCreated = createdSeq("parent")
            for (c in childLabels) {
                assertTrue(parentCreated < createdSeq(c), "parent Created must precede child '$c' Created (A)")
                assertTrue(createdSeq(c) < parentBodyDone, "child '$c' Created must precede parent BodyCompleted (A)")
            }

            // (B1) the parent's terminal event comes AFTER every child's terminal event.
            val parentTerminal = terminalSeq("parent")
            for (c in childLabels) {
                assertTrue(
                    parentTerminal > terminalSeq(c),
                    "parent terminal (seq $parentTerminal) must come after child '$c' terminal (seq ${terminalSeq(c)}) (B1)",
                )
            }
        }
}
