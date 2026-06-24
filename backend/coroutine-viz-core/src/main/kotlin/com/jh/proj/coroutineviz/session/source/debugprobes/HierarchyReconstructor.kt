package com.jh.proj.coroutineviz.session.source.debugprobes

import kotlinx.coroutines.Job

/**
 * Pure, DebugProbes-free reconstruction of the coroutine parent/child tree from a
 * DebugProbes dump (Phase 8, D-01/D-02; spike-proven in
 * `examples/spring-vizcore-demo/.../spike/HierarchySpike.kt`).
 *
 * DebugProbes reports each observed coroutine's [Job], but the *direct* parent of an
 * observed leaf is frequently an INTERMEDIATE scope/async job (`coroutineScope { }`,
 * `supervisorScope { }`, an awaited `async`) that is NOT itself reported as a
 * coroutine. A naive direct-parent link therefore yields a flat, parentless wall
 * (spike: direct-parent-observed 0/5). This reconstructor inverts [Job.children]
 * across the WHOLE reachable set and climbs to the NEAREST OBSERVED ancestor,
 * collapsing those intermediate jobs.
 *
 * Purity contract (mirrors [CoroutineInfoAdapter]'s `RawInfo` fakeability): the only
 * input is `kotlinx.coroutines.Job`, a public type, so this is unit-testable on fake
 * Job trees WITHOUT real DebugProbes ([HierarchyReconstructorTest]). It assigns NO
 * coroutine key — it returns [Job] references; keying is the adapter's job
 * ([CoroutineInfoAdapter.toSnapshots]) so parent and child keys come from the same
 * `jobKeys` cache and edges connect (Open Q4 resolution; threat T-08-02).
 *
 * Termination (threat T-08-03): the [Job.children] walk is guarded by a `walked`
 * HashSet so a huge or cyclic-shaped reachable graph cannot recurse unbounded.
 */
class HierarchyReconstructor private constructor(
    private val observedJobs: Set<Job>,
    private val childToParent: Map<Job, Job>,
) {
    /**
     * Climb [job]'s parent chain (via the inverted [Job.children] map) skipping every
     * non-observed intermediate scope/async job, returning the nearest OBSERVED
     * ancestor — or `null` when the chain reaches a root with no observed ancestor
     * (the coroutine becomes a tree root).
     */
    fun nearestObservedParent(job: Job): Job? {
        var p = childToParent[job]
        while (p != null && p !in observedJobs) p = childToParent[p]
        return p
    }

    companion object {
        /**
         * Build the reconstructor for one dump from its set of OBSERVED jobs.
         * Inverts [Job.children] across every reachable job (observed + intermediate),
         * guarding re-entry with a `walked` set (T-08-03).
         */
        fun build(observed: Set<Job>): HierarchyReconstructor {
            val childToParent = HashMap<Job, Job>()
            val walked = HashSet<Job>()
            fun walk(j: Job) {
                if (!walked.add(j)) return
                for (c in j.children) {
                    childToParent[c] = j
                    walk(c)
                }
            }
            observed.forEach(::walk)
            return HierarchyReconstructor(observed, childToParent)
        }
    }
}
