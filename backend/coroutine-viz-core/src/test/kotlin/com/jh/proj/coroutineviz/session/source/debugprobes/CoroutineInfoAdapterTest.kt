package com.jh.proj.coroutineviz.session.source.debugprobes

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.jupiter.api.Test
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Focused unit test of the real `CoroutineInfo → CoroutineSnapshot` adapter's
 * field extraction (06-REVIEWS.md MEDIUM real-adapter coverage). Because
 * `CoroutineInfo`'s constructor takes the internal `DebugCoroutineInfo`, the
 * adapter is factored around a small fakeable [CoroutineInfoAdapter.RawInfo]
 * input so extraction is testable WITHOUT real DebugProbes. The thin
 * `CoroutineInfo` overload only forwards into this same core (smoke-tested in
 * [DebugProbesSmokeIT]).
 */
class CoroutineInfoAdapterTest {
    private fun frame(
        className: String,
        method: String,
        file: String?,
        line: Int,
    ) = StackTraceElement(className, method, file, line)

    private val creationStack =
        listOf(
            frame("kotlinx.coroutines.BuildersKt", "launch", "Builders.kt", 1),
            frame("com.example.App", "startWork", "App.kt", 12),
        )
    private val lastObserved =
        listOf(
            frame("kotlinx.coroutines.DelayKt", "delay", "Delay.kt", 5),
            frame("com.example.App", "fetch", "App.kt", 30),
        )

    @Test
    fun `extracts function, file, line, dispatcher, label, reason and last observed stack`() {
        val adapter = CoroutineInfoAdapter()
        val raw =
            CoroutineInfoAdapter.RawInfo(
                state = CoroState.SUSPENDED,
                job = Job(),
                context = CoroutineName("fetcher") + Dispatchers.Default,
                creationStackTrace = creationStack,
                lastObservedStackTrace = lastObserved,
            )

        val snap = adapter.toSnapshot(raw)

        assertEquals(CoroState.SUSPENDED, snap.state)
        assertEquals("startWork", snap.function)
        assertEquals("App.kt", snap.fileName)
        assertEquals(12, snap.lineNumber)
        assertEquals("fetcher", snap.label)
        assertEquals("Dispatchers.Default", snap.dispatcherName)
        assertEquals("fetch", snap.reason)
        assertEquals(lastObserved, snap.lastObservedStackTrace)
    }

    @Test
    fun `same job identity maps to the same stable key (no double-emit)`() {
        val adapter = CoroutineInfoAdapter()
        val job = Job()
        val raw1 =
            CoroutineInfoAdapter.RawInfo(CoroState.RUNNING, job, EmptyCoroutineContext, creationStack, emptyList())
        val raw2 =
            CoroutineInfoAdapter.RawInfo(CoroState.SUSPENDED, job, EmptyCoroutineContext, creationStack, lastObserved)

        assertEquals(adapter.toSnapshot(raw1).key, adapter.toSnapshot(raw2).key)
    }

    @Test
    fun `distinct jobs map to distinct keys`() {
        val adapter = CoroutineInfoAdapter()
        val a = CoroutineInfoAdapter.RawInfo(CoroState.RUNNING, Job(), EmptyCoroutineContext, creationStack, emptyList())
        val b = CoroutineInfoAdapter.RawInfo(CoroState.RUNNING, Job(), EmptyCoroutineContext, creationStack, emptyList())

        assertTrue(adapter.toSnapshot(a).key != adapter.toSnapshot(b).key)
    }

    @Test
    fun `null job falls back to a synthetic stable key`() {
        val adapter = CoroutineInfoAdapter()
        val raw =
            CoroutineInfoAdapter.RawInfo(
                state = CoroState.RUNNING,
                job = null,
                context = EmptyCoroutineContext,
                creationStackTrace = creationStack,
                lastObservedStackTrace = emptyList(),
            )

        val key = adapter.toSnapshot(raw).key
        assertNotNull(key.token)
        assertTrue(key.token.startsWith("synthetic:"))
    }

    @Test
    fun `same null-job input maps to the same synthetic key twice (stable re-key)`() {
        val adapter = CoroutineInfoAdapter()
        val raw =
            CoroutineInfoAdapter.RawInfo(CoroState.RUNNING, null, EmptyCoroutineContext, creationStack, emptyList())

        // First observation assigns a synthetic ordinal; the same identity (same
        // creation-stack signature) must re-resolve to the same key on re-dump.
        val k1 = adapter.toSnapshot(raw).key
        val k2 = adapter.toSnapshot(raw).key
        assertEquals(k1, k2)
    }

    @Test
    fun `toSnapshots links child parentKey to the parent's own key via the same jobKeys cache`() {
        // root (OBSERVED) ── scopeA (UNOBSERVED intermediate) ── child (OBSERVED)
        // child's nearest-observed-ancestor is root; its parentKey must equal root's own key.
        val adapter = CoroutineInfoAdapter()
        val root = Job()
        val scopeA = Job(root) // intermediate, NOT in the observed infos
        val child = Job(scopeA)

        val rootRaw = CoroutineInfoAdapter.RawInfo(CoroState.RUNNING, root, CoroutineName("root"), creationStack, emptyList())
        val childRaw = CoroutineInfoAdapter.RawInfo(CoroState.SUSPENDED, child, CoroutineName("child"), creationStack, lastObserved)

        val snaps = adapter.toSnapshots(listOf(rootRaw, childRaw))
        val rootSnap = snaps.single { it.label == "root" }
        val childSnap = snaps.single { it.label == "child" }

        // Edge connects: child.parentKey == the parent coroutine's OWN key (T-08-02).
        assertEquals(rootSnap.key, childSnap.parentKey)
        // root is a tree root — no observed ancestor.
        assertNull(rootSnap.parentKey)
    }

    @Test
    fun `toSnapshots leaves parentKey null for a null-job (synthetic) coroutine`() {
        val adapter = CoroutineInfoAdapter()
        val raw = CoroutineInfoAdapter.RawInfo(CoroState.RUNNING, null, EmptyCoroutineContext, creationStack, emptyList())

        val snap = adapter.toSnapshots(listOf(raw)).single()
        assertNull(snap.parentKey)
    }

    @Test
    fun `toSnapshots preserves single-info field extraction`() {
        val adapter = CoroutineInfoAdapter()
        val raw =
            CoroutineInfoAdapter.RawInfo(
                state = CoroState.SUSPENDED,
                job = Job(),
                context = CoroutineName("fetcher") + Dispatchers.Default,
                creationStackTrace = creationStack,
                lastObservedStackTrace = lastObserved,
            )

        val snap = adapter.toSnapshots(listOf(raw)).single()
        assertEquals("startWork", snap.function)
        assertEquals("Dispatchers.Default", snap.dispatcherName)
        assertEquals("fetch", snap.reason)
    }
}
