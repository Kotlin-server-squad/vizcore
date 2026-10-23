package com.jh.proj.coroutineviz.events

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SuspensionPointFrameSelectionTest {
    private fun frame(
        className: String,
        method: String = "m",
        line: Int = 10,
    ) = StackTraceElement(className, method, "F.kt", line)

    private val wrappers = "com.jh.proj.coroutineviz.wrappers"

    @Test
    fun `a user class that merely starts like a wrapper is the caller`() {
        val user = frame("$wrappers.VizScopeCancellationTest", "test")

        assertEquals(user, SuspensionPoint.selectCallerFrame(arrayOf(frame("$wrappers.VizScope\$vizLaunch\$1"), user)))
    }

    @Test
    fun `wrapper classes, their lambdas and file classes are skipped`() {
        val user = frame("com.example.Repo", "load")
        val frames =
            arrayOf(
                frame(SuspensionPoint::class.java.name + "\$Companion", "capture"),
                frame("$wrappers.VizScope", "vizDelay"),
                frame("$wrappers.VizScope\$vizLaunch\$1"),
                frame("$wrappers.VizScopeKt"),
                frame("$wrappers.InstrumentedDeferred", "await"),
                frame("kotlinx.coroutines.DelayKt"),
                user,
            )

        assertEquals(user, SuspensionPoint.selectCallerFrame(frames))
    }

    @Test
    fun `a frame with a line wins over an earlier one without`() {
        val lineless = frame("com.example.Bridge", "bridge", line = -1)
        val real = frame("com.example.Repo", "load", line = 42)

        assertEquals(real, SuspensionPoint.selectCallerFrame(arrayOf(lineless, real)))
    }

    @Test
    fun `without any line the first caller frame keeps the method name`() {
        val first = frame("com.example.Repo", "load", line = -1)
        val second = frame("com.example.Other", "other", line = -1)

        assertEquals(first, SuspensionPoint.selectCallerFrame(arrayOf(frame("kotlinx.coroutines.X", line = -1), first, second)))
    }

    @Test
    fun `no caller frame gives null`() {
        assertNull(SuspensionPoint.selectCallerFrame(arrayOf(frame("kotlinx.coroutines.X"))))
    }

    @Test
    @Suppress("DEPRECATION")
    fun `the deprecated overload still captures the caller`() {
        val point = SuspensionPoint.capture("delay", 2)

        assertEquals("SuspensionPointFrameSelectionTest.kt", point.fileName)
        assertEquals("delay", point.reason)
    }
}
