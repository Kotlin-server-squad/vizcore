package com.jh.proj.coroutineviz.harness

import com.jh.proj.coroutineviz.session.StructuralAwareBuffer
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.runBlocking

/**
 * Dev-only PERF-05 load-harness ENTRYPOINT — a THIN wrapper over [EgressLoadDriver.run].
 *
 * This is the ONLY class in the `loadHarness` source set. It is excluded from the production
 * jar/shadowJar (D-09, asserted by [JarExclusionTest]) precisely because it lives in its own
 * source set, not `main`. All flood logic lives in [EgressLoadDriver] (in `main`) so the `:test`
 * smoke test can exercise the identical code path without depending on this source set.
 *
 * Run it with `./gradlew loadHarness [-PloadN=200000] [-PloadShedCap=1000]` (or pass args:
 * `<n> <shedCapacity>`). It floods N synthetic events straight at `EventBus.send` (D-10),
 * bypassing the store to isolate egress drops, then prints the three separate drop counters
 * (store / bus / sampling, D-11).
 *
 * Structured concurrency: the flood + egress collectors run on the session's private scope
 * (never GlobalScope); the session is closed in a `finally` so no coroutines leak.
 */
object LoadHarnessMain {
    private const val DEFAULT_N = 200_000

    @JvmStatic
    fun main(args: Array<String>) {
        val n = args.getOrNull(0)?.toIntOrNull() ?: DEFAULT_N
        val shedCapacity =
            args.getOrNull(1)?.toIntOrNull() ?: StructuralAwareBuffer.DEFAULT_SHED_CAPACITY

        val session = VizSession(sessionId = "load-harness")
        val counters =
            try {
                runBlocking {
                    EgressLoadDriver.run(session, n = n, shedCapacity = shedCapacity)
                }
            } finally {
                session.close() // cancel the private session scope — no leaked coroutines
            }

        println("── PERF-05 load harness: $n synthetic events, shedCapacity=$shedCapacity ──")
        println("sent          structural=${counters.structuralSent} sheddable=${counters.sheddableSent}")
        println("survived      structural=${counters.structuralSurvived} sheddable=${counters.sheddableSurvived}")
        println("DROP COUNTERS (independent, D-11):")
        println("  store    = ${counters.storeDrops}   (≈0 by design — harness bypasses the store)")
        println("  bus      = ${counters.busDrops}")
        println("  sampling = ${counters.samplingDrops}   (non-structural events shed under load)")
    }
}
