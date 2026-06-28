package com.jh.proj.coroutineviz.harness

import com.jh.proj.coroutineviz.events.VizEvent
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCompleted
import com.jh.proj.coroutineviz.events.coroutine.CoroutineCreated
import com.jh.proj.coroutineviz.events.coroutine.CoroutineSuspended
import com.jh.proj.coroutineviz.events.flow.FlowValueEmitted
import com.jh.proj.coroutineviz.session.EventSampler
import com.jh.proj.coroutineviz.session.StructuralAwareBuffer
import com.jh.proj.coroutineviz.session.StructuralClassifier
import com.jh.proj.coroutineviz.session.VizSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * The reusable load-flood DRIVER for PERF-05 — the single source of truth for the synthetic
 * flood logic.
 *
 * ## Why this lives in `src/main` (the pre-decided smoke-test seam)
 * The `loadHarness` source set is NOT on the `:test` runtime classpath, so a smoke test in
 * `:test` cannot reference anything in `src/loadHarness/`. To keep ONE definition of the flood
 * logic that both the `loadHarness` `main()` entrypoint AND the `:test` smoke test can call, the
 * logic lives here in `main`. [LoadHarnessMain] (in `src/loadHarness/`) is a THIN wrapper over
 * [run]; [com.jh.proj.coroutineviz.harness.LoadHarnessSmokeTest] (in `:test`) calls [run]
 * directly. Being in `main` it MAY ride the production jar — that is fine: it is generic,
 * inert egress-flood plumbing that nothing in production wires up. Only the dev-only
 * ENTRYPOINT (`LoadHarnessMain`) is forbidden from the jar (D-09, asserted by
 * [JarExclusionTest]).
 *
 * ## What it does (D-10 / D-11)
 * It constructs a private [VizSession], attaches a collector running the SAME
 * `adaptive-sample → structural-shed` chain the SSE route uses (so the harness exercises the
 * real egress hardening, not a stub), then floods N synthetic [VizEvent]s — a mix of structural
 * (create/complete) and sheddable (suspend/flow-value) kinds — STRAIGHT at
 * [VizSession.eventBus].send. Injecting at the bus (NOT [VizSession.send]) deliberately
 * BYPASSES the store, which is the whole point: it isolates egress drops (bus / sampling) from
 * store drops (D-11). A parallel [VizSession.send] flood would be needed only if store-drop
 * numbers were also wanted.
 *
 * It reports [Counters] — THREE SEPARATE, non-conflated drop numbers (store / bus / sampling)
 * plus how many structural vs sheddable events survived the egress chain — so each layer's loss
 * is independently attributable.
 *
 * ## Structured concurrency (CLAUDE.md)
 * The flood and the egress collector both run on [VizSession.sessionScope] (a private
 * `SupervisorJob` scope) — NEVER `GlobalScope`. The collector is cancelled and the session is
 * closed on completion, so no coroutine leaks.
 */
object EgressLoadDriver {
    /**
     * Three independently-attributable drop counters (D-11) plus survival tallies the smoke
     * test asserts on.
     *
     * @property storeDrops events dropped by the bounded EventStore. ~0 here BY DESIGN: the
     *   harness injects at `eventBus.send`, bypassing the store entirely (the isolation point).
     * @property busDrops events dropped by the EventBus `MutableSharedFlow` overflow (its
     *   `onDrop` hook). Non-zero only if the egress collector cannot keep up with the flood.
     * @property samplingDrops non-structural events shed by the [StructuralAwareBuffer]'s
     *   sheddable lane under forced load (D-07/D-08).
     * @property structuralSurvived structural events that made it through the full chain.
     * @property sheddableSurvived sheddable events that made it through the full chain.
     * @property structuralSent total structural events injected at the bus.
     * @property sheddableSent total sheddable events injected at the bus.
     */
    data class Counters(
        val storeDrops: Long,
        val busDrops: Long,
        val samplingDrops: Long,
        val structuralSurvived: Long,
        val sheddableSurvived: Long,
        val structuralSent: Long,
        val sheddableSent: Long,
    )

    /**
     * Flood [n] synthetic events at [session].eventBus.send through a live egress chain and
     * return the three separate drop counters.
     *
     * @param session the session to flood; its `eventBus`, `onEventDropped` (store), and the
     *   bus `onDrop` are the three counter sources.
     * @param n number of synthetic events to inject.
     * @param shedCapacity the sheddable-lane bound; a small value here FORCES shedding so the
     *   sampling counter is exercised deterministically under load.
     * @param adaptiveSampling whether the adaptive sampler gate engages (default false → full
     *   fidelity, so survival is governed purely by the shed lane, keeping the smoke test
     *   deterministic).
     */
    suspend fun run(
        session: VizSession,
        n: Int,
        shedCapacity: Int = StructuralAwareBuffer.DEFAULT_SHED_CAPACITY,
        adaptiveSampling: Boolean = false,
    ): Counters {
        require(n >= 0) { "n must be >= 0, was $n" }

        // ── Three counter sources (D-11) ──────────────────────────────────────
        val storeDrops = AtomicLong(0)
        val busDrops = AtomicLong(0)
        session.onEventDropped = { storeDrops.incrementAndGet() } // store lane (expected ~0)
        session.eventBus.onDrop = { busDrops.incrementAndGet() } // bus lane

        // The egress chain: adaptive-sample → structural-shed (same primitives as the SSE route).
        val sampler = EventSampler(defaultRate = 1.0, adaptive = adaptiveSampling)
        val shedBuffer = StructuralAwareBuffer(shedCapacity = shedCapacity)
        val egress = wireEgressChain(session, sampler, shedBuffer)

        // Wait until the egress collector is subscribed before flooding — the bus is replay=0, so
        // an event sent before a collector subscribes is lost (the Phase-07 startup-race idiom).
        session.eventBus.subscriptionCount.first { it >= 1 }

        // ── Flood: inject N synthetic events STRAIGHT at the bus (D-10), bypassing the store ──
        val sent = flood(session, n)

        // Drain: close the upstream so the egress collector finishes, then the shed buffer, then
        // await the survival collector. Cancel any residual coroutines (structured cleanup).
        egress.egressJob.join()
        shedBuffer.close()
        egress.drainDone.await()
        egress.drainJob.cancel()

        return Counters(
            storeDrops = storeDrops.get(),
            busDrops = busDrops.get(),
            samplingDrops = shedBuffer.dropped,
            structuralSurvived = egress.structuralSurvived.get(),
            sheddableSurvived = egress.sheddableSurvived.get(),
            structuralSent = sent.first,
            sheddableSent = sent.second,
        )
    }

    /** Live egress-chain handles the driver awaits/joins after flooding. */
    private class Egress(
        val egressJob: kotlinx.coroutines.Job,
        val drainJob: kotlinx.coroutines.Job,
        val drainDone: CompletableDeferred<Unit>,
        val structuralSurvived: AtomicLong,
        val sheddableSurvived: AtomicLong,
    )

    /**
     * Launch the two egress collectors on [session]'s private scope (never GlobalScope): one
     * pumps the bus through [sampler] into [shedBuffer]; one drains the shed buffer
     * (lifecycle-first) and tallies survivors by structural class.
     */
    private fun wireEgressChain(
        session: VizSession,
        sampler: EventSampler,
        shedBuffer: StructuralAwareBuffer,
    ): Egress {
        val structuralSurvived = AtomicLong(0)
        val sheddableSurvived = AtomicLong(0)
        val drainDone = CompletableDeferred<Unit>()
        val drainJob =
            session.sessionScope.launch {
                shedBuffer.stream().collect { event ->
                    if (StructuralClassifier.isStructural(event.kind)) {
                        structuralSurvived.incrementAndGet()
                    } else {
                        sheddableSurvived.incrementAndGet()
                    }
                }
                drainDone.complete(Unit)
            }
        val egressJob =
            session.sessionScope.launch {
                session.eventBus.stream().collect { event ->
                    if (sampler.shouldKeep(event)) shedBuffer.offer(event)
                }
            }
        return Egress(egressJob, drainJob, drainDone, structuralSurvived, sheddableSurvived)
    }

    /**
     * Inject [n] synthetic events straight at [session].eventBus.send (D-10). Returns
     * (structuralSent, sheddableSent).
     */
    private fun flood(
        session: VizSession,
        n: Int,
    ): Pair<Long, Long> {
        var structuralSent = 0L
        var sheddableSent = 0L
        for (i in 0 until n) {
            val event = syntheticEvent(session.sessionId, i)
            if (StructuralClassifier.isStructural(event.kind)) structuralSent++ else sheddableSent++
            session.eventBus.send(event)
        }
        return structuralSent to sheddableSent
    }

    /**
     * Build a single synthetic [VizEvent]. Alternates structural (create/complete) and sheddable
     * (suspend/flow-value) kinds by index so a flood exercises BOTH the protected lifecycle lane
     * and the bounded sheddable lane.
     */
    private fun syntheticEvent(
        sessionId: String,
        i: Int,
    ): VizEvent {
        val coroutineId = "load-$i"
        val tsNanos = System.nanoTime()
        return when (i % STRIDE) {
            0 ->
                CoroutineCreated(
                    sessionId = sessionId,
                    seq = i.toLong(),
                    tsNanos = tsNanos,
                    coroutineId = coroutineId,
                    jobId = "job-$i",
                    parentCoroutineId = null,
                    scopeId = "load-harness",
                    label = "synthetic-$i",
                    createdAtEpochMs = System.currentTimeMillis(),
                )
            1 ->
                CoroutineCompleted(
                    sessionId = sessionId,
                    seq = i.toLong(),
                    tsNanos = tsNanos,
                    coroutineId = coroutineId,
                    jobId = "job-$i",
                    parentCoroutineId = null,
                    scopeId = "load-harness",
                    label = "synthetic-$i",
                )
            2 ->
                CoroutineSuspended(
                    sessionId = sessionId,
                    seq = i.toLong(),
                    tsNanos = tsNanos,
                    coroutineId = coroutineId,
                    jobId = "job-$i",
                    parentCoroutineId = null,
                    scopeId = "load-harness",
                    label = "synthetic-$i",
                    reason = "delay",
                )
            else ->
                FlowValueEmitted(
                    sessionId = sessionId,
                    seq = i.toLong(),
                    tsNanos = tsNanos,
                    coroutineId = coroutineId,
                    flowId = "flow-$i",
                    collectorId = "collector-$i",
                    sequenceNumber = i,
                    valuePreview = "v$i",
                    valueType = "kotlin.Int",
                )
        }
    }

    /** Synthetic-event kind cycle length (2 structural + 2 sheddable per stride). */
    private const val STRIDE = 4
}
