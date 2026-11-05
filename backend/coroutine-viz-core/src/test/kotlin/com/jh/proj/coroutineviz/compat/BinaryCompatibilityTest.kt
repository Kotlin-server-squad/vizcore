package com.jh.proj.coroutineviz.compat

import com.jh.proj.coroutineviz.events.SemaphorePermitReleased
import com.jh.proj.coroutineviz.events.SemaphoreStateChanged
import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.events.SuspensionProvenance
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.session.EventContext
import com.jh.proj.coroutineviz.session.VizSession
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Pins the JVM signatures of the published 0.2.x API, which Java callers and prebuilt jars link against. */
class BinaryCompatibilityTest {
    @Test
    fun `VizSession keeps its 0_2_x constructors`() {
        val long = Long::class.javaPrimitiveType
        val int = Int::class.javaPrimitiveType
        VizSession::class.java.getDeclaredConstructor(
            String::class.java,
            int,
            Function0::class.java,
            long,
            Function1::class.java,
        )
        VizSession::class.java.getDeclaredConstructor(
            String::class.java,
            int,
            Function0::class.java,
            long,
            Function1::class.java,
            int,
            Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"),
        )
    }

    @Test
    fun `the old constructor builds a session without summaries`() {
        val ctor =
            VizSession::class.java.getDeclaredConstructor(
                String::class.java,
                Int::class.javaPrimitiveType,
                Function0::class.java,
                Long::class.javaPrimitiveType,
                Function1::class.java,
            )
        val session = ctor.newInstance("compat", 10, { 5L }, 7L, { _: String -> com.jh.proj.coroutineviz.session.EventStore(10) })
        try {
            assertEquals("compat", session.sessionId)
            assertEquals(7L, session.createdAtMs)
            assertFalse(runCatching { session.readSummaries { } }.isSuccess)
        } finally {
            session.close()
        }
    }

    @Test
    fun `SuspensionPoint keeps capture and capture dollar default`() {
        val companion = SuspensionPoint.Companion::class.java
        assertNotNull(companion.getDeclaredMethod("capture", String::class.java, Int::class.javaPrimitiveType))
        assertNotNull(
            companion.getDeclaredMethod(
                "capture\$default",
                companion,
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Any::class.java,
            ),
        )
    }

    private val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")

    @Test
    fun `CoroutineCreated keeps its 0_2_1 constructors`() {
        val long = Long::class.javaPrimitiveType
        val str = String::class.java
        val old = arrayOf<Class<*>?>(str, long, long, str, str, str, str, str, long, SuspensionPoint::class.java)
        val ctor = CoroutineCreated::class.java.getDeclaredConstructor(*old)
        CoroutineCreated::class.java.getDeclaredConstructor(
            *old,
            Int::class.javaPrimitiveType,
            marker,
        )
        val event = ctor.newInstance("s", 1L, 2L, "c", "j", null, "sc", "l", 9L, null)
        assertEquals("c", event.coroutineId)
        assertEquals(9L, event.createdAtEpochMs)
        assertNull(event.origin)
    }

    @Test
    fun `CoroutineSuspended keeps its 0_2_1 constructors`() {
        val long = Long::class.javaPrimitiveType
        val str = String::class.java
        val old =
            arrayOf<Class<*>?>(
                str, long, long, str, str, str, str, str, str, java.lang.Long::class.java, SuspensionPoint::class.java,
            )
        val ctor = CoroutineSuspended::class.java.getDeclaredConstructor(*old)
        CoroutineSuspended::class.java.getDeclaredConstructor(
            *old,
            Int::class.javaPrimitiveType,
            marker,
        )
        val event = ctor.newInstance("s", 1L, 2L, "c", "j", null, "sc", "l", "delay", 5L, null)
        assertEquals("delay", event.reason)
        assertEquals(5L, event.durationMillis)
        assertNull(event.provenance)
    }

    @Test
    fun `EventContext coroutineSuspended keeps its 0_2_x JVM methods`() {
        val helpers = Class.forName("com.jh.proj.coroutineviz.session.EventContextKt")
        val old =
            arrayOf(
                EventContext::class.java,
                String::class.java,
                java.lang.Long::class.java,
                SuspensionPoint::class.java,
            )
        assertNotNull(helpers.getDeclaredMethod("coroutineSuspended", *old))
        assertNotNull(
            helpers.getDeclaredMethod("coroutineSuspended\$default", *old, Int::class.javaPrimitiveType, Any::class.java),
        )
    }

    @Test
    fun `CoroutineSuspended keeps its 0_3_x constructors`() {
        val long = Long::class.javaPrimitiveType
        val str = String::class.java
        val v03 =
            arrayOf<Class<*>?>(
                str, long, long, str, str, str, str, str, str, java.lang.Long::class.java, SuspensionPoint::class.java,
                SuspensionProvenance::class.java,
            )
        val ctor = CoroutineSuspended::class.java.getDeclaredConstructor(*v03)
        CoroutineSuspended::class.java.getDeclaredConstructor(*v03, Int::class.javaPrimitiveType, marker)
        val event = ctor.newInstance("s", 1L, 2L, "c", "j", null, "sc", "l", "await", null, null, SuspensionProvenance.REAL_FRAME)
        assertEquals(SuspensionProvenance.REAL_FRAME, event.provenance)
        assertNull(event.waitKind)
        assertNull(event.resourceId)
    }

    @Test
    fun `EventContext coroutineSuspended keeps its 0_3_x JVM methods`() {
        val helpers = Class.forName("com.jh.proj.coroutineviz.session.EventContextKt")
        val v03 =
            arrayOf(
                EventContext::class.java,
                String::class.java,
                java.lang.Long::class.java,
                SuspensionPoint::class.java,
                SuspensionProvenance::class.java,
            )
        assertNotNull(helpers.getDeclaredMethod("coroutineSuspended", *v03))
        assertNotNull(
            helpers.getDeclaredMethod("coroutineSuspended\$default", *v03, Int::class.javaPrimitiveType, Any::class.java),
        )
    }

    @Test
    fun `semaphore events keep their 0_3_x constructors`() {
        val long = Long::class.javaPrimitiveType
        val int = Int::class.javaPrimitiveType
        val str = String::class.java
        val list = List::class.java
        SemaphorePermitReleased::class.java.getDeclaredConstructor(str, long, long, str, str, str, str, int, long)
        SemaphoreStateChanged::class.java.getDeclaredConstructor(str, long, long, str, str, int, int, list, list, list, list)
    }
}
