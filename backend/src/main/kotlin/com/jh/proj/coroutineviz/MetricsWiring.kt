package com.jh.proj.coroutineviz

import com.jh.proj.coroutineviz.session.SessionManager
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Timer
import io.micrometer.prometheus.PrometheusMeterRegistry
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val logger = LoggerFactory.getLogger("MetricsWiring")

/** Tracks active SSE client connections. Increment on connect, decrement on disconnect. */
val sseClientsGauge = AtomicInteger(0)

fun wireMetrics(registry: PrometheusMeterRegistry) {
    // --- ADR-020 metric 1: viz.sessions.active (existing) ---
    Gauge.builder("viz.sessions.active") { SessionManager.listSessions().size.toDouble() }
        .description("Number of active visualization sessions")
        .register(registry)

    // --- ADR-020 metric 2: viz.sse.clients.active (existing) ---
    Gauge.builder("viz.sse.clients.active") { sseClientsGauge.toDouble() }
        .description("Number of active SSE client connections")
        .register(registry)

    // --- ADR-020 metric 3: events.emitted (Counter) ---
    val eventsEmittedCounter = Counter.builder("events.emitted")
        .description("Total events emitted across all sessions")
        .register(registry)

    // --- ADR-020 metric 4: events.dropped (Counter) ---
    val eventsDroppedCounter = Counter.builder("events.dropped")
        .description("Events dropped due to bounded EventStore capacity")
        .register(registry)

    // --- ADR-020 metric 5: scenario.duration (Timer) ---
    val scenarioDurationTimer = Timer.builder("scenario.duration")
        .description("Time to complete scenario execution")
        .register(registry)
    // Expose for use in ScenarioRunnerRoutes
    scenarioDurationTimerRef = scenarioDurationTimer

    // --- ADR-020 metric 6: event.processing.duration (Timer) ---
    val eventProcessingTimer = Timer.builder("event.processing.duration")
        .description("Time to process and broadcast a single event")
        .register(registry)

    // Wire callbacks into every new session via SessionManager.onSessionCreated
    SessionManager.onSessionCreated = { session ->
        // events.emitted: increment each time send() successfully completes
        session.onEventEmitted = { eventsEmittedCounter.increment() }

        // events.dropped: increment each time EventStore evicts an event
        session.store.onEvict = { eventsDroppedCounter.increment() }

        // events.buffer.size: per-session gauge tagged by sessionId
        Gauge.builder("events.buffer.size") { session.store.count().toDouble() }
            .description("Current number of events in session buffer")
            .tag("sessionId", session.sessionId)
            .register(registry)

        // event.processing.duration: record nanos from VizSession.send()
        session.onEventProcessed = { nanos ->
            eventProcessingTimer.record(nanos, TimeUnit.NANOSECONDS)
        }
    }

    logger.info("Metrics wiring complete (7 ADR-020 metrics registered)")
}

/** Shared reference so ScenarioRunnerRoutes can record scenario.duration. */
var scenarioDurationTimerRef: Timer? = null
