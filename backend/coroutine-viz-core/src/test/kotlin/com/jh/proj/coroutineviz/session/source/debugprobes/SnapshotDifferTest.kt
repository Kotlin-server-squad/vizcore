package com.jh.proj.coroutineviz.session.source.debugprobes

import com.jh.proj.coroutineviz.session.source.debugprobes.CoroutineDelta.Appeared
import com.jh.proj.coroutineviz.session.source.debugprobes.CoroutineDelta.StateChanged
import com.jh.proj.coroutineviz.session.source.debugprobes.CoroutineDelta.Vanished
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-diff unit tests over hand-built `Map<CoroKey, CoroutineSnapshot>` — no real
 * coroutines, no DebugProbes, no timing. Covers the full <behavior> matrix from
 * Plan 06-02 Task 1, including the no-double-emit stable-set case and the new
 * `reason` / `lastObservedStackTrace` fields riding along on the snapshot.
 */
class SnapshotDifferTest {
    private fun key(id: String) = CoroKey(id)

    private fun snap(
        key: CoroKey,
        state: CoroState,
        reason: String? = null,
        lastObserved: List<StackTraceElement>? = null,
    ) = CoroutineSnapshot(
        key = key,
        state = state,
        label = null,
        dispatcherName = null,
        function = null,
        fileName = null,
        lineNumber = null,
        reason = reason,
        lastObservedStackTrace = lastObserved,
    )

    @Test
    fun `empty prev plus one coroutine in next yields one Appeared`() {
        val k = key("a")
        val next = mapOf(k to snap(k, CoroState.CREATED))

        val deltas = diff(emptyMap(), next)

        assertEquals(1, deltas.size)
        val appeared = deltas.single()
        assertTrue(appeared is Appeared)
        assertEquals(next.getValue(k), appeared.now)
    }

    @Test
    fun `same key with identical state yields no delta (no double-emit)`() {
        val k = key("a")
        val prev = mapOf(k to snap(k, CoroState.RUNNING))
        val next = mapOf(k to snap(k, CoroState.RUNNING))

        assertEquals(emptyList(), diff(prev, next))
    }

    @Test
    fun `CREATED to RUNNING yields one StateChanged`() {
        val k = key("a")
        val prev = mapOf(k to snap(k, CoroState.CREATED))
        val next = mapOf(k to snap(k, CoroState.RUNNING))

        assertEquals(
            listOf(StateChanged(CoroState.CREATED, CoroState.RUNNING, next.getValue(k))),
            diff(prev, next),
        )
    }

    @Test
    fun `RUNNING to SUSPENDED yields one StateChanged`() {
        val k = key("a")
        val prev = mapOf(k to snap(k, CoroState.RUNNING))
        val next = mapOf(k to snap(k, CoroState.SUSPENDED))

        assertEquals(
            listOf(StateChanged(CoroState.RUNNING, CoroState.SUSPENDED, next.getValue(k))),
            diff(prev, next),
        )
    }

    @Test
    fun `SUSPENDED to RUNNING yields one StateChanged`() {
        val k = key("a")
        val prev = mapOf(k to snap(k, CoroState.SUSPENDED))
        val next = mapOf(k to snap(k, CoroState.RUNNING))

        assertEquals(
            listOf(StateChanged(CoroState.SUSPENDED, CoroState.RUNNING, next.getValue(k))),
            diff(prev, next),
        )
    }

    @Test
    fun `key in prev but absent in next yields one Vanished`() {
        val k = key("a")
        val prev = mapOf(k to snap(k, CoroState.RUNNING))

        val deltas = diff(prev, emptyMap())

        assertEquals(listOf(Vanished(prev.getValue(k))), deltas)
    }

    @Test
    fun `mixed snapshot yields appeared, statechanged and vanished in deterministic order`() {
        val appearKey = key("appear")
        val changeKey = key("change")
        val vanishKey = key("vanish")

        val prev =
            mapOf(
                changeKey to snap(changeKey, CoroState.RUNNING),
                vanishKey to snap(vanishKey, CoroState.RUNNING),
            )
        val next =
            mapOf(
                changeKey to snap(changeKey, CoroState.SUSPENDED),
                appearKey to snap(appearKey, CoroState.CREATED),
            )

        val deltas = diff(prev, next)

        // Deterministic order: Appeared (next-order), then StateChanged (next-order), then Vanished (prev-order).
        assertEquals(3, deltas.size)
        assertEquals(Appeared(next.getValue(appearKey)), deltas[0])
        assertEquals(
            StateChanged(CoroState.RUNNING, CoroState.SUSPENDED, next.getValue(changeKey)),
            deltas[1],
        )
        assertEquals(Vanished(prev.getValue(vanishKey)), deltas[2])
    }

    @Test
    fun `stable synthetic-key coroutines do not double-emit across repeated identical snapshots`() {
        // job == null path simulated via a synthetic string key on the value type.
        val k = CoroKey("synthetic:hash#0")
        val s1 = mapOf(k to snap(k, CoroState.RUNNING))
        val s2 = mapOf(k to snap(k, CoroState.RUNNING))
        val s3 = mapOf(k to snap(k, CoroState.RUNNING))

        // First appearance only.
        assertEquals(1, diff(emptyMap(), s1).size)
        // No further deltas while stable.
        assertEquals(emptyList(), diff(s1, s2))
        assertEquals(emptyList(), diff(s2, s3))
    }

    @Test
    fun `snapshot carrying reason and lastObservedStackTrace round-trips through diff unchanged`() {
        val k = key("a")
        val frame = StackTraceElement("com.example.App", "doWork", "App.kt", 42)
        val prev = mapOf(k to snap(k, CoroState.RUNNING))
        val next =
            mapOf(
                k to snap(k, CoroState.SUSPENDED, reason = "delay", lastObserved = listOf(frame)),
            )

        val deltas = diff(prev, next)
        val changed = deltas.single() as StateChanged
        assertEquals("delay", changed.now.reason)
        assertEquals(listOf(frame), changed.now.lastObservedStackTrace)
    }
}
