package com.jh.proj.coroutineviz.observability

import com.jh.proj.coroutineviz.session.SessionManager
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.log

/**
 * Wire OpenTelemetry span export behind the OTEL-01 construction gate (D-02).
 *
 * The FIRST statement after reading `enabled` is `if (!enabled) return` — when OTel is
 * disabled NOTHING below the gate runs: no [OtelTracing] SDK, no `OtlpGrpcSpanExporter`, no
 * `BatchSpanProcessor` worker thread, and no per-session [CoroutineSpanExporter] subscriber is
 * ever constructed. This is the OTEL-01 "gated at construction, not use" guarantee made real:
 * the disabled path adds no listener to [SessionManager] and no thread to the JVM, so the
 * off-vs-on `send()` throughput delta stays within measurement noise (proven by
 * `OtelGatingTest` + `OtelOverheadTest`). A malformed `enabled` value parses to `false`
 * (`toBoolean()` semantics in [OtelConfig.from]) — i.e. the gate FAILS CLOSED (T-12-09).
 *
 * When enabled, exactly ONE process-wide [OtelTracing] SDK is built (RESEARCH Open Q3 — one
 * shared `SdkTracerProvider`), and a [CoroutineSpanExporter] is attached PER SESSION via
 * [SessionManager.addOnSessionCreated] — the COMPOSABLE registry, never the single-slot
 * `onSessionCreated` which would clobber `MetricsWiring`'s per-session callback (Pitfall 6 /
 * T-12-11). `tracing.shutdown()` is registered on [ApplicationStopping] to flush pending spans
 * before the process exits (the `DbRetentionPolicy.stop()` analog).
 *
 * Call from `Application.module()` on its own line, right after `configureStorage(maxEvents)`.
 */
fun Application.configureObservability() {
    val enabled =
        environment.config
            .propertyOrNull("observability.otel.enabled")
            ?.getString()
            ?.toBoolean() ?: false

    // ── OTEL-01 / D-02 construction gate ── This early return is the gate: nothing below runs
    // when OTel is off, so no SDK / exporter / worker thread / per-session subscriber exists.
    if (!enabled) {
        log.info(
            "OpenTelemetry disabled (observability.otel.enabled=false) — " +
                "SDK/exporter/subscriber NOT constructed (OTEL-01 zero-cost-when-off).",
        )
        return
    }

    // Past the gate: operator opted in. Build ONE shared SDK; attach one subscriber per session.
    val cfg = OtelConfig.from(environment.config)
    val tracing = OtelTracing.build(cfg)

    // Per-session span exporter via the COMPOSABLE registry (addOnSessionCreated, NOT the
    // single-slot onSessionCreated) so MetricsWiring's per-session callback is preserved
    // (compose-don't-clobber, Pitfall 6 / T-12-11).
    SessionManager.addOnSessionCreated { session ->
        CoroutineSpanExporter(session, tracing.tracer)
    }

    // Flush pending spans on shutdown (DbRetentionPolicy.stop() analog).
    monitor.subscribe(ApplicationStopping) {
        log.info("Application stopping — flushing OpenTelemetry span pipeline")
        tracing.shutdown()
    }

    log.info(
        "OpenTelemetry enabled — one shared SDK built, per-session CoroutineSpanExporter wired " +
            "(endpoint={}, service.name={}, samplingRatio={}).",
        cfg.endpoint,
        cfg.serviceName,
        cfg.samplingRatio,
    )
}
