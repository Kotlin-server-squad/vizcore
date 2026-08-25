package com.jh.proj.coroutineviz.session

import com.jh.proj.coroutineviz.events.VizEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicLong

/**
 * A two-lane bounded egress buffer that replaces today's BLIND `DROP_OLDEST` with
 * **structural-aware** shedding (PERF-04, D-07/D-08).
 *
 * The existing per-subscriber egress buffer (`SessionRoutes` `liveBuffer`) and the bus's
 * `MutableSharedFlow` overflow are both *predicate-free* — they cannot "keep lifecycle, drop
 * noise." `MutableSharedFlow`/`Channel` overflow has no API to make eviction selective. This
 * buffer fixes that by splitting the egress into two lanes (RESEARCH §3 Option 2):
 *
 * - **Lifecycle lane** — events where [StructuralClassifier.isStructural] is true. These define
 *   existence and topology (node birth/death, edges); dropping any corrupts the live tree/graph.
 *   The lane is effectively unbounded *by design*: lifecycle volume is inherently low (one
 *   create/complete per coroutine) and is transitively capped by the already-bounded EventStore
 *   live-coroutine count, so it cannot OOM in practice. It is **never** `DROP_OLDEST` (Pitfall P5).
 *
 * - **Sheddable lane** — everything else (high-frequency value/dispatch/state noise). Bounded at
 *   [shedCapacity] with `DROP_OLDEST`; every dropped element increments [droppedCounter] via
 *   `onUndeliveredElement` (D-08). The store keeps 100% of these upstream (D-03), so a shed event
 *   is recoverable via `/events` — nothing is truly lost.
 *
 * ## Protection is delegated, not reinvented
 * Routing asks the SHARED [StructuralClassifier] spine — the same allow-set PERF-01 sampling
 * uses — so "is this protected?" has exactly one definition (no drift between sampler and shedder).
 *
 * ## Draining (lifecycle-first, never starved)
 * [stream] drains the lifecycle lane FIRST on each tick, then a sheddable element, using a biased
 * [select]. A flood of noise can therefore never starve a structural event behind a backlog.
 *
 * Pure-Kotlin JVM-17, no new dependency, no Micrometer / io.ktor (Pitfall P6). The [dropped]
 * counter is a plain [AtomicLong] the route (Plan 03) surfaces to metrics and the `dropped N`
 * marker via the existing callback-to-Micrometer convention.
 *
 * @param shedCapacity bound on the sheddable lane; mirrors the existing 10_000 shape (D-07).
 */
class StructuralAwareBuffer(
    private val shedCapacity: Int = DEFAULT_SHED_CAPACITY,
) {
    init {
        require(shedCapacity >= 1) { "shedCapacity must be >= 1, was $shedCapacity" }
    }

    private val droppedCounter = AtomicLong(0)

    /**
     * Forced structural-drop counter. Per RESEARCH §3 the lifecycle lane is unbounded-by-design
     * and this should remain ~0 forever; a non-zero value surfaces a real bug (pathological
     * lifecycle volume). Exposed for diagnostics; the lane below never applies `DROP_OLDEST`.
     */
    private val forcedStructuralDropCounter = AtomicLong(0)

    /**
     * Lifecycle lane — [Channel.UNLIMITED] so a structural event is NEVER dropped on offer. Its
     * practical bound is the live-coroutine count the EventStore already caps (D-07, no OOM).
     */
    private val lifecycleLane =
        Channel<VizEvent>(capacity = Channel.UNLIMITED)

    /**
     * Sheddable lane — bounded with `DROP_OLDEST`; each evicted element increments [droppedCounter]
     * through [onUndeliveredElement] so EVERY shed is observable (D-08).
     */
    private val sheddableLane =
        Channel<VizEvent>(
            capacity = shedCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { droppedCounter.incrementAndGet() },
        )

    /**
     * Route [event] by structural classification: structural → lifecycle lane (never dropped),
     * non-structural → bounded sheddable lane (drop-oldest on overflow). Non-suspending: both
     * lanes accept without blocking the caller — the sheddable lane sheds rather than backpressure
     * (D-07 rejected backpressure), the lifecycle lane is unbounded.
     */
    fun offer(event: VizEvent) {
        if (StructuralClassifier.isStructural(event.kind)) {
            val result = lifecycleLane.trySend(event)
            if (result.isFailure && !result.isClosed) {
                // Unreachable for an UNLIMITED channel, but counted defensively per RESEARCH §3.
                forcedStructuralDropCounter.incrementAndGet()
            }
        } else {
            // DROP_OLDEST means trySend always accepts the newest; the evicted oldest flows
            // through onUndeliveredElement, incrementing droppedCounter.
            sheddableLane.trySend(event)
        }
    }

    /** Signal end-of-input; closes both lanes so [stream] can complete after draining. */
    fun close() {
        lifecycleLane.close()
        sheddableLane.close()
    }

    /**
     * Drain both lanes as a single [Flow], **lifecycle-lane-first each tick** so structural
     * events are never starved behind a sheddable backlog. Completes once both lanes are closed
     * and emptied.
     */
    fun stream(): Flow<VizEvent> =
        flow {
            var lifecycleOpen = true
            var sheddableOpen = true

            while (lifecycleOpen || sheddableOpen) {
                // Fast path: greedily emit every IMMEDIATELY-available lifecycle element before
                // considering a sheddable one. This is what guarantees "lifecycle drains first
                // each tick" — a ready structural event is always taken before any noise.
                if (lifecycleOpen) {
                    var ready = lifecycleLane.tryReceive()
                    while (ready.isSuccess) {
                        emit(ready.getOrThrow())
                        ready = lifecycleLane.tryReceive()
                    }
                    if (ready.isClosed) lifecycleOpen = false
                }
                if (!lifecycleOpen && !sheddableOpen) break

                // Biased suspend point: lifecycle arm is listed first so it wins ties when both
                // lanes have an element ready at the same instant.
                val received: VizEvent? =
                    select {
                        if (lifecycleOpen) {
                            lifecycleLane.onReceiveCatching { result ->
                                if (result.isClosed) {
                                    lifecycleOpen = false
                                }
                                result.getOrNull()
                            }
                        }
                        if (sheddableOpen) {
                            sheddableLane.onReceiveCatching { result ->
                                if (result.isClosed) {
                                    sheddableOpen = false
                                }
                                result.getOrNull()
                            }
                        }
                    }
                if (received != null) emit(received)
            }
        }

    /** Observable count of non-structural events shed by the sheddable lane (D-08). */
    val dropped: Long get() = droppedCounter.get()

    /** Forced structural drops — expected to remain 0 (a non-zero value indicates a bug). */
    val forcedStructuralDrops: Long get() = forcedStructuralDropCounter.get()

    companion object {
        /** Default sheddable-lane bound, mirroring the existing `EventBus` 10_000 shape (D-07). */
        const val DEFAULT_SHED_CAPACITY: Int = 10_000
    }
}
