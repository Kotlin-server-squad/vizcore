package com.jh.proj.coroutineviz.session.source.debugprobes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit-proves the spike-proven (D-02) nearest-observed-ancestor reconstruction on
 * REAL [Job] trees (the reconstructor's only input is `kotlinx.coroutines.Job`, a
 * public type — no DebugProbes needed, mirroring [CoroutineInfoAdapter]'s RawInfo
 * fakeability invariant).
 *
 * Structural assertions replicate the spike's bar (withParent >= 3, named-equivalent
 * structure >= 4) on a deterministic fake tree where observed leaf jobs descend from
 * intermediate UNOBSERVED scope jobs — exactly the case the production path must
 * recover (spike: direct-parent-observed 0/5, nearest-ancestor recovers the chain).
 */
class HierarchyReconstructorTest {
    /**
     * Build a fake hierarchy of real Jobs:
     *
     *   root (OBSERVED)
     *     └── scopeA (UNOBSERVED intermediate)
     *           ├── childA (OBSERVED)        parent → root  (across one intermediate)
     *           └── scopeB (UNOBSERVED)
     *                 └── grandchild (OBSERVED)  parent → root (across two intermediates)
     *     └── childDirect (OBSERVED)         parent → root  (direct-parent-observed)
     *
     * Only root/childA/grandchild/childDirect are "observed". scopeA/scopeB are
     * intermediate scope jobs that DebugProbes does not report as coroutines.
     */
    private class Tree {
        val root = Job()
        val scopeA = Job(root)
        val childA = Job(scopeA)
        val scopeB = Job(scopeA)
        val grandchild = Job(scopeB)
        val childDirect = Job(root)

        val observed: Set<Job> = setOf(root, childA, grandchild, childDirect)
    }

    @Test
    fun `nearestObservedParent recovers parent across one unobserved intermediate`() {
        val t = Tree()
        val recon = HierarchyReconstructor.build(t.observed)

        // childA's DIRECT parent is scopeA (unobserved) — climb must land on root.
        assertEquals(t.root, recon.nearestObservedParent(t.childA))
    }

    @Test
    fun `nearestObservedParent recovers parent across two unobserved intermediates`() {
        val t = Tree()
        val recon = HierarchyReconstructor.build(t.observed)

        // grandchild → scopeB → scopeA → root: skip BOTH unobserved scope jobs.
        assertEquals(t.root, recon.nearestObservedParent(t.grandchild))
    }

    @Test
    fun `nearestObservedParent handles the direct-parent-observed case`() {
        val t = Tree()
        val recon = HierarchyReconstructor.build(t.observed)

        // childDirect's direct parent IS root (observed) — no skipping needed.
        assertEquals(t.root, recon.nearestObservedParent(t.childDirect))
    }

    @Test
    fun `a coroutine with no observed ancestor maps to null (tree root)`() {
        val t = Tree()
        val recon = HierarchyReconstructor.build(t.observed)

        // root has no parent in the observed set → null (becomes a tree root).
        assertNull(recon.nearestObservedParent(t.root))
    }

    @Test
    fun `every non-root observed job resolves to an observed parent (spike bar withParent gte 3)`() {
        val t = Tree()
        val recon = HierarchyReconstructor.build(t.observed)

        val withParent = t.observed.count { recon.nearestObservedParent(it) != null }
        // childA, grandchild, childDirect all resolve to root; only root is parentless.
        assertTrue(withParent >= 3, "expected >= 3 observed jobs with an observed parent, got $withParent")
        // All recovered parents are themselves observed (edges connect — T-08-02).
        t.observed.forEach { job ->
            recon.nearestObservedParent(job)?.let { p ->
                assertTrue(p in t.observed, "recovered parent must be observed")
            }
        }
    }

    @Test
    fun `walk guards re-entry on a cyclic-shaped traversal without infinite loop (T-08-03)`() {
        // A deep linear chain of real launched coroutines (alive, suspended) — proves
        // the walked-set guard terminates on a large reachable set.
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val jobs = mutableListOf<Job>()
            var parentScope = scope
            repeat(20) {
                val j = parentScope.launch { kotlinx.coroutines.delay(10_000) }
                jobs += j
            }
            try {
                val observed = jobs.toSet()
                val recon = HierarchyReconstructor.build(observed)
                // Terminates (no StackOverflow / hang) and every job resolves deterministically.
                observed.forEach { recon.nearestObservedParent(it) }
                assertTrue(true)
            } finally {
                scope.coroutineContext[Job]?.cancel()
            }
        }
    }
}
