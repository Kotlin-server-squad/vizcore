package com.jh.proj.coroutineviz.compat

import com.jh.proj.coroutineviz.events.SuspensionPoint
import com.jh.proj.coroutineviz.session.VizSession
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

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
}
