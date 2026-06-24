package com.jh.proj.coroutineviz.session.source.debugprobes

import kotlinx.coroutines.CoroutineName
import org.junit.jupiter.api.Test
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * RCO-03 source-attribution extraction tests. Pure over hand-built
 * `List<StackTraceElement>` and `CoroutineContext` — no DebugProbes, no real
 * coroutines.
 */
class SourceAttributionTest {
    private fun frame(
        className: String,
        method: String,
        file: String?,
        line: Int,
    ) = StackTraceElement(className, method, file, line)

    @Test
    fun `picks first non-infrastructure frame`() {
        val stack =
            listOf(
                frame("kotlinx.coroutines.DelayKt", "delay", "Delay.kt", 10),
                frame("kotlin.coroutines.jvm.internal.BaseContinuationImpl", "resumeWith", "Continuation.kt", 20),
                frame("com.example.MyApp", "doWork", "MyApp.kt", 42),
                frame("com.example.Other", "ignored", "Other.kt", 99),
            )

        val attr = SourceAttribution.fromCreationStack(stack)

        assertEquals("doWork", attr.function)
        assertEquals("MyApp.kt", attr.fileName)
        assertEquals(42, attr.lineNumber)
    }

    @Test
    fun `empty stack yields null attribution`() {
        val attr = SourceAttribution.fromCreationStack(emptyList())

        assertNull(attr.function)
        assertNull(attr.fileName)
        assertNull(attr.lineNumber)
    }

    @Test
    fun `all-infrastructure stack yields null attribution`() {
        val stack =
            listOf(
                frame("kotlinx.coroutines.BuildersKt", "launch", "Builders.kt", 1),
                frame("kotlin.coroutines.ContinuationKt", "resume", "Continuation.kt", 2),
            )

        val attr = SourceAttribution.fromCreationStack(stack)

        assertNull(attr.function)
        assertNull(attr.fileName)
        assertNull(attr.lineNumber)
    }

    @Test
    fun `negative line number is dropped to null`() {
        val stack = listOf(frame("com.example.MyApp", "doWork", "MyApp.kt", -1))

        val attr = SourceAttribution.fromCreationStack(stack)

        assertEquals("doWork", attr.function)
        assertNull(attr.lineNumber)
    }

    @Test
    fun `reason returns first non-infra method of last observed stack`() {
        val stack =
            listOf(
                frame("kotlinx.coroutines.DelayKt", "delay", "Delay.kt", 10),
                frame("com.example.MyApp", "fetchUser", "MyApp.kt", 55),
            )

        assertEquals("fetchUser", SourceAttribution.reason(stack))
    }

    @Test
    fun `reason is null for empty or all-infra stack`() {
        assertNull(SourceAttribution.reason(emptyList()))
        assertNull(
            SourceAttribution.reason(
                listOf(frame("kotlinx.coroutines.DelayKt", "delay", "Delay.kt", 10)),
            ),
        )
    }

    @Test
    fun `label read from CoroutineName`() {
        val ctx = CoroutineName("fetcher")
        assertEquals("fetcher", SourceAttribution.label(ctx))
    }

    @Test
    fun `label is null when no CoroutineName present`() {
        assertNull(SourceAttribution.label(EmptyCoroutineContext))
    }

    @Test
    fun `dispatcherName read from context interceptor`() {
        val ctx = kotlinx.coroutines.Dispatchers.Default
        val name = SourceAttribution.dispatcherName(ctx)
        // Dispatchers.Default has a stable, non-null toString.
        assertEquals(kotlinx.coroutines.Dispatchers.Default.toString(), name)
    }

    @Test
    fun `dispatcherName is null when no interceptor present`() {
        assertNull(SourceAttribution.dispatcherName(EmptyCoroutineContext))
    }
}
