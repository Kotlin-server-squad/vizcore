package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.SuspensionProvenance
import com.jh.proj.coroutineviz.events.capturedProvenance
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SuspensionProvenanceTest {
    @Test
    fun `vizDelay marks its suspension frame as real`(): Unit =
        runBlocking {
            val session = VizSession("provenance")
            val viz = VizScope(session)
            viz.vizLaunch("worker") { vizDelay(1) }.join()

            val suspended = session.store.all().filterIsInstance<CoroutineSuspended>()
            assertTrue(suspended.isNotEmpty(), "vizDelay must emit a suspension")
            assertTrue(
                suspended.all { it.provenance == SuspensionProvenance.REAL_FRAME },
                "wrapper frames come from SuspensionPoint.capture: $suspended",
            )
            viz.cancelAndJoin()
            session.close()
        }

    @Test
    fun `capture's unknown placeholder is not a real frame`() {
        assertEquals(null, SuspensionPoint(function = "unknown", reason = "delay").capturedProvenance())
        assertEquals(
            SuspensionProvenance.REAL_FRAME,
            SuspensionPoint(function = "load", fileName = "Repo.kt", lineNumber = 3, reason = "delay").capturedProvenance(),
        )
    }
}
