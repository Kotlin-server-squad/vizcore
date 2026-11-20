package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineStarted
import com.jh.proj.coroutineviz.models.CoroutineNode
import com.jh.proj.coroutineviz.models.CoroutineState
import com.jh.proj.coroutineviz.models.RuntimeSnapshot
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * [RuntimeSnapshot.nodes] lists coroutines in start order (the seq of their first creation event, then id), not in
 * the hash map's: the session page draws siblings in the order it is given, live as in replay.
 */
class RuntimeSnapshotStartOrderTest {
    private fun created(
        seq: Long,
        id: String,
        parent: String? = null,
        label: String? = id,
    ) = CoroutineCreated(
        sessionId = "s",
        seq = seq,
        tsNanos = seq,
        coroutineId = id,
        jobId = "job-$id",
        parentCoroutineId = parent,
        scopeId = "scope",
        label = label,
    )

    // Ids whose hash order is not their creation order: enough of them that a hash map would shuffle them.
    private val ids = listOf("root") + (1..40).map { "coroutine-${(it * 7919) % 1000}-$it" }

    @Test
    fun `nodes come in creation order, whatever the ids hash to`() {
        val snapshot = RuntimeSnapshot()
        val applier = EventApplier(snapshot)
        ids.forEachIndexed { i, id -> applier.apply(created(seq = 10L + i, id = id, parent = if (i == 0) null else "root")) }

        assertEquals(ids, snapshot.nodes().map { it.id })
    }

    @Test
    fun `the order does not change while the session runs`() {
        val snapshot = RuntimeSnapshot()
        val applier = EventApplier(snapshot)
        ids.forEachIndexed { i, id -> applier.apply(created(seq = 10L + i, id = id, parent = if (i == 0) null else "root")) }
        // State changes replace the map's entries; later coroutines are added.
        ids.reversed().forEachIndexed { i, id ->
            applier.apply(
                CoroutineStarted(
                    sessionId = "s",
                    seq = 100L + i,
                    tsNanos = 100L + i,
                    coroutineId = id,
                    jobId = "job-$id",
                    parentCoroutineId = null,
                    scopeId = "scope",
                    label = id,
                ),
            )
        }
        applier.apply(created(seq = 200, id = "a-late-one", parent = "root"))

        val nodes = snapshot.nodes()
        assertEquals(ids + "a-late-one", nodes.map { it.id })
        assertEquals(CoroutineState.ACTIVE, nodes.first().state)
    }

    @Test
    fun `a coroutine created again keeps its place and takes the new fields`() {
        val snapshot = RuntimeSnapshot()
        val applier = EventApplier(snapshot)
        applier.apply(created(seq = 1, id = "b"))
        applier.apply(created(seq = 2, id = "a"))
        applier.apply(created(seq = 3, id = "b", label = "renamed"))

        val nodes = snapshot.nodes()
        assertEquals(listOf("b", "a"), nodes.map { it.id })
        assertEquals("renamed", nodes.first().label)
    }

    @Test
    fun `events applied out of seq order still list by seq, and equal seqs by id`() {
        val snapshot = RuntimeSnapshot()
        val applier = EventApplier(snapshot)
        applier.apply(created(seq = 5, id = "late"))
        applier.apply(created(seq = 2, id = "y"))
        applier.apply(created(seq = 2, id = "x"))

        assertEquals(listOf("x", "y", "late"), snapshot.nodes().map { it.id })
    }

    @Test
    fun `a node put into the map directly comes after the created ones, by id`() {
        val snapshot = RuntimeSnapshot()
        EventApplier(snapshot).apply(created(seq = 9, id = "z-created"))
        for (id in listOf("m", "b")) {
            snapshot.coroutines[id] = CoroutineNode(id, "job-$id", null, "scope", id, CoroutineState.CREATED)
        }

        assertEquals(listOf("z-created", "b", "m"), snapshot.nodes().map { it.id })
    }
}
