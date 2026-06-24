package com.jh.vizcore.demo.spike

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.debug.DebugProbes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

/**
 * THROWAWAY SPIKE — answers one question: can the DebugProbes real-app path reconstruct the
 * parent/child tree the visualizer needs, from data that's actually available at runtime?
 *
 * It installs DebugProbes, starts a NAMED structured-concurrency workload that suspends (so the
 * coroutines are alive at dump time), then:
 *   1. dumps all coroutines (DebugProbes.dumpCoroutinesInfo)
 *   2. inverts Job.children across ALL reachable jobs (observed + intermediate scope jobs)
 *   3. for each observed coroutine, climbs child->parent to the NEAREST OBSERVED ancestor
 *      (collapsing the intermediate coroutineScope/supervisor jobs that aren't themselves
 *      reported as coroutines)
 *
 * If names + nearest-observed-parent come out sensible, the Phase-8 fix is real and buildable.
 */
fun main() {
    DebugProbes.install()

    val root = CoroutineScope(Dispatchers.Default + CoroutineName("root-scope"))
    root.launch(CoroutineName("parent")) {
        coroutineScope {
            launch(CoroutineName("child-a")) { delay(10_000) }
            launch(CoroutineName("child-b")) {
                coroutineScope {
                    launch(CoroutineName("grandchild")) { delay(10_000) }
                }
            }
            val computed = async(CoroutineName("compute")) { delay(10_000); 42 }
            computed.await()
        }
    }

    Thread.sleep(1_000) // let the workload suspend on delay so it's alive in the dump

    val infos = DebugProbes.dumpCoroutinesInfo()

    data class Obs(val job: Job, val name: String?, val state: String)
    val observed =
        infos.mapNotNull { info ->
            val job = info.job ?: return@mapNotNull null
            Obs(job = job, name = info.context[CoroutineName]?.name, state = info.state.name)
        }
    val observedJobs = observed.map { it.job }.toHashSet()
    val nameOf = observed.associate { it.job to (it.name ?: "job@${System.identityHashCode(it.job)}") }

    // Invert Job.children over every reachable job (observed coroutines + intermediate scope jobs).
    val childToParent = HashMap<Job, Job>()
    val walked = HashSet<Job>()
    fun walk(j: Job) {
        if (!walked.add(j)) return
        for (c in j.children) {
            childToParent[c] = j
            walk(c)
        }
    }
    observed.forEach { walk(it.job) }

    fun nearestObservedParent(j: Job): Job? {
        var p = childToParent[j]
        while (p != null && p !in observedJobs) p = childToParent[p]
        return p
    }

    println("=== SPIKE: observed coroutines = ${observed.size} ===")
    observed.sortedBy { nameOf[it.job] }.forEach { o ->
        val parent = nearestObservedParent(o.job)
        val pName = parent?.let { nameOf[it] } ?: "<no observed parent / root>"
        val directParentObserved = childToParent[o.job]?.let { it in observedJobs } ?: false
        println("  ${(o.name ?: "(unnamed)").padEnd(12)} [${o.state.padEnd(9)}] parent=$pName  (directParentObserved=$directParentObserved)")
    }

    val withParent = observed.count { nearestObservedParent(it.job) != null }
    val named = observed.count { it.name != null }
    val directObserved = observed.count { (childToParent[it.job]?.let { p -> p in observedJobs }) ?: false }
    println("=== resolved-to-observed-parent: $withParent/${observed.size} | named: $named/${observed.size} | direct-parent-observed: $directObserved/${observed.size} ===")
    println("=== VERDICT: ${if (withParent >= 3 && named >= 4) "TREE IS RECOVERABLE (nearest-observed-ancestor needed for intermediate scope jobs)" else "SPARSE — needs deeper handling"} ===")

    DebugProbes.uninstall()
    exitProcess(0)
}
