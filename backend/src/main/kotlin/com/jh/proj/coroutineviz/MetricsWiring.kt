package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.session.EventStore
import com.jh.proj.coroutineviz.session.SessionManager
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Timer
import io.micrometer.prometheus.PrometheusMeterRegistry
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private val logger = LoggerFactory.getLogger("MetricsWiring")

/** Tracks active SSE client connections. Increment on connect, decrement on disconnect. */
val sseClientsGauge = AtomicInteger(0)

/**
 * Monotonic count of events shed by the per-SSE-connection [StructuralAwareBuffer] egress
 * sheddable lane (PERF-04/D-08). The shed buffer is per-connection, so the route surfaces its
 * drop DELTA here (analog to [sseClientsGauge]); MetricsWiring exposes it as the
 * `events.dropped.sampling` counter (D-11). Core stays Micrometer-free — this AtomicLong is the
 * callback-to-Micrometer bridge (Pitfall P6).
 */
val sseSamplingDroppedGauge = AtomicLong(0)

fun wireMetrics(registry: PrometheusMeterRegistry) {
    // --- ADR-020 metric 1: viz.sessions.active (existing) ---
    Gauge
        .builder("viz.sessions.active") { SessionManager.listSessions().size.toDouble() }
        .description("Number of active visualization sessions")
        .register(registry)

    // --- ADR-020 metric 2: viz.sse.clients.active (existing) ---
    Gauge
        .builder("viz.sse.clients.active") { sseClientsGauge.toDouble() }
        .description("Number of active SSE client connections")
        .register(registry)

    // --- ADR-020 metric 3: events.emitted (Counter) ---
    val eventsEmittedCounter =
        Counter
            .builder("events.emitted")
            .description("Total events emitted across all sessions")
            .register(registry)

    // --- ADR-020 metric 4: events.dropped (Counter) — STORE drops ---
    val eventsDroppedCounter =
        Counter
            .builder("events.dropped")
            .description("Events dropped due to bounded EventStore capacity")
            .register(registry)

    // --- Phase-10 D-11 counters: events.dropped.bus + events.dropped.sampling ---
    val eventsDroppedBusCounter = registerPhase10DropCounters(registry)

    // --- ADR-020 metric 5: scenario.duration (Timer) ---
    val scenarioDurationTimer =
        Timer
            .builder("scenario.duration")
            .description("Time to complete scenario execution")
            .register(registry)
    // Expose for use in ScenarioRunnerRoutes
    scenarioDurationTimerRef = scenarioDurationTimer

    // --- ADR-020 metric 6: event.processing.duration (Timer) ---
    val eventProcessingTimer =
        Timer
            .builder("event.processing.duration")
            .description("Time to process and broadcast a single event")
            .register(registry)

    // Per-session buffer gauges: track Meter.Id by sessionId so the gauge can be
    // deregistered when the session closes. Without removal, the registry keeps a
    // strong reference to every closed session (EventStore included) and /metrics
    // accumulates one stale events_buffer_size series per session ever created (WR-03).
    val bufferGaugeIds = ConcurrentHashMap<String, Meter.Id>()

    // Wire callbacks into every new session via the composable registry rather
    // than assigning the single onSessionCreated slot — that slot is shared, and
    // overwriting it would clobber any other subsystem's per-session wiring
    // (e.g. an instrumentation-source installer). addOnSessionCreated composes,
    // so metrics + source wiring both fire on every createSession (RCO-01,
    // Research Pitfall 3, T-06-01).
    SessionManager.addOnSessionCreated { session ->
        // events.emitted: increment each time send() successfully completes
        session.onEventEmitted = { eventsEmittedCounter.increment() }

        // events.dropped: increment each time the bounded in-memory EventStore
        // evicts an event. Eviction is an in-memory-store concern only; the
        // DB-backed store has no capacity bound, so this is a no-op there.
        (session.store as? EventStore)?.onEvict = { eventsDroppedCounter.increment() }

        // events.dropped.bus: increment each time the EventBus broadcast buffer sheds an
        // event on tryEmit overflow (the onDrop hook already fires in EventBus.send; it was
        // previously unwired to any counter). Distinct lane from the store-drop counter (D-11).
        session.bus.onDrop = { eventsDroppedBusCounter.increment() }

        // events.buffer.size: per-session gauge tagged by sessionId
        val bufferGauge =
            Gauge
                .builder("events.buffer.size") { session.store.count().toDouble() }
                .description("Current number of events in session buffer")
                .tag("sessionId", session.sessionId)
                .register(registry)
        bufferGaugeIds[session.sessionId] = bufferGauge.id

        // event.processing.duration: record nanos from VizSession.send()
        session.onEventProcessed = { nanos ->
            eventProcessingTimer.record(nanos, TimeUnit.NANOSECONDS)
        }
    }

    // Deregister the per-session gauge when the session is closed, releasing the
    // session reference held by the gauge's value lambda. Use the composable
    // addOnSessionClosed registry (mirroring the created-side above) rather than
    // assigning the single-slot SessionManager.onSessionClosed — that slot is
    // shared, so another subsystem (or a second wireMetrics call) assigning it
    // would silently clobber this gauge-deregistration and re-introduce the
    // gauge/session leak (WR-05, RCO-01 "compose, don't clobber").
    SessionManager.addOnSessionClosed { sessionId ->
        bufferGaugeIds.remove(sessionId)?.let { meterId -> registry.remove(meterId) }
    }

    logger.info(
        "Metrics wiring complete (7 ADR-020 metrics + 2 Phase-10 drop counters: " +
            "events.dropped.bus, events.dropped.sampling)",
    )
}

/**
 * Register the two Phase-10 attributable drop counters (D-11) and return the bus-drop
 * [Counter] for per-session [EventBus.onDrop] wiring. Kept separate from [wireMetrics] to
 * respect the detekt LongMethod limit.
 *
 * - `events.dropped.bus` — events shed by the EventBus broadcast buffer on tryEmit overflow
 *   (the live-broadcast lane), distinct from the store-drop counter so a loss is attributable
 *   to the bus vs the store vs egress sampling.
 * - `events.dropped.sampling` — non-structural events shed by the PER-SSE-CONNECTION
 *   structural-aware egress buffer, surfaced through the process-wide [sseSamplingDroppedGauge]
 *   AtomicLong the route increments (callback-to-Micrometer; core stays Micrometer-free,
 *   Pitfall P6). A [FunctionCounter] mirrors that monotonic AtomicLong without owning the count.
 */
private fun registerPhase10DropCounters(registry: PrometheusMeterRegistry): Counter {
    val busCounter =
        Counter
            .builder("events.dropped.bus")
            .description("Events dropped by the EventBus broadcast buffer (tryEmit overflow)")
            .register(registry)

    FunctionCounter
        .builder("events.dropped.sampling", sseSamplingDroppedGauge) { it.get().toDouble() }
        .description("Non-structural events shed by the per-connection structural-aware egress buffer")
        .register(registry)

    return busCounter
}

/** Shared reference so ScenarioRunnerRoutes can record scenario.duration. */
var scenarioDurationTimerRef: Timer? = null
