package com.jh.proj.coroutineviz.wrappers

import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.SuspensionProvenance
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
            suspended.forEach {
                assertEquals("SuspensionProvenanceTest.kt", it.suspensionPoint?.fileName, "caller's file, not the wrapper's: $it")
                assertNotNull(it.suspensionPoint?.lineNumber, "caller's line: $it")
            }
            viz.cancelAndJoin()
            session.close()
        }

    @Test
    fun `await marks the caller's frame as real`(): Unit =
        runBlocking {
            val session = VizSession("provenance-await")
            val viz = VizScope(session)
            viz.vizLaunch("awaiter") {
                val child =
                    vizAsync("child") {
                        vizDelay(1)
                        1
                    }
                child.await()
            }.join()

            val point =
                session.store
                    .all()
                    .filterIsInstance<CoroutineSuspended>()
                    .single { it.reason == "await" }
            assertEquals(SuspensionProvenance.REAL_FRAME, point.provenance, "$point")
            assertEquals("SuspensionProvenanceTest.kt", point.suspensionPoint?.fileName, "$point")
            assertNotNull(point.suspensionPoint?.lineNumber, "$point")
            viz.cancelAndJoin()
            session.close()
        }

    @Test
    fun `capture's unknown placeholder is not a real frame`() {
        val (point, provenance) = SuspensionPoint.fromFrames(emptyArray(), "delay")

        assertEquals(SuspensionPoint.UNKNOWN_FUNCTION, point.function)
        assertNull(provenance)
    }

    @Test
    fun `a selected frame is a real frame even when it is a method called unknown without a file`() {
        val stripped = StackTraceElement("com.example.Repo", SuspensionPoint.UNKNOWN_FUNCTION, null, -1)
        val (point, provenance) = SuspensionPoint.fromFrames(arrayOf(stripped), "delay")

        assertEquals(SuspensionPoint.UNKNOWN_FUNCTION, point.function)
        assertNull(point.fileName)
        assertEquals(SuspensionProvenance.REAL_FRAME, provenance)
    }

    @Test
    fun `captureWithProvenance reads the live stack`() {
        val (point, provenance) = SuspensionPoint.captureWithProvenance("delay")

        assertEquals(SuspensionProvenance.REAL_FRAME, provenance)
        assertEquals("SuspensionProvenanceTest.kt", point.fileName)
    }
}
