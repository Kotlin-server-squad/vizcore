package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.coroutine.CoroutineFailed
import com.jh.proj.coroutineviz.session.MAX_STACK_TRACE_FRAMES
import com.jh.proj.coroutineviz.session.VizSession
import com.jh.proj.coroutineviz.session.stackTraceLines
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The common emitter used to send every failure with an empty stack trace (spec 2026-11-05, Part 2). */
@Timeout(30)
class FailedStackTraceTest {
    @Test
    fun `a failed coroutine sends the stack trace of its exception`() =
        runBlocking {
            val session = VizSession("failed-stack")
            try {
                // The failure is expected: handled here, it does not reach the global handler (and later runTest tests).
                val swallow = CoroutineExceptionHandler { _, _ -> }
                withTimeout(5.seconds) { VizScope(session, context = SupervisorJob() + swallow).vizLaunch("thrower") { failHere() }.join() }
                val failed = session.awaitEvent { it is CoroutineFailed && it.label == "thrower" } as CoroutineFailed

                assertTrue(failed.stackTrace.any { "failHere" in it }, failed.stackTrace.joinToString("\n"))
                assertTrue(failed.stackTrace.size <= MAX_STACK_TRACE_FRAMES)
            } finally {
                session.close()
            }
        }

    @Test
    fun `a deep stack trace is cut to the frame limit, innermost frame first`() {
        val deep = assertNotNull(runCatching { recurse(200) }.exceptionOrNull())

        assertEquals(MAX_STACK_TRACE_FRAMES, deep.stackTraceLines().size)
        assertTrue("recurse" in deep.stackTraceLines().first())
    }

    private fun failHere(): Nothing = throw IllegalStateException("boom")

    private fun recurse(depth: Int): Int = if (depth == 0) throw IllegalStateException("deep") else recurse(depth - 1) + 1
}
