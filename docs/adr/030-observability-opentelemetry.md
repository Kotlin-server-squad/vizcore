# ADR 030: Observability — OpenTelemetry / OTLP

## Status

Accepted

Date: 2026-06-28

## Context

The Coroutine Visualizer reconstructs coroutine causality from an event-sourced stream
(Created → Started → Suspended → Resumed → Completed/Failed/Cancelled). That same causality
is exactly what distributed-tracing backends visualize as parent/child spans. Exporting it
over OpenTelemetry (OTLP) lets operators inspect a run in standard tooling (Jaeger, Zipkin)
without bespoke UI, and proves the event model is faithful to real coroutine structure.

Constraints carried in from earlier ADRs:

- **ADR-009 (ephemeral, default-off runtime):** new runtime behavior must be opt-in and cost
  nothing when disabled.
- **ADR-013 (core library extraction / backend-only deps):** `coroutine-viz-core` and
  `coroutine-viz-client` must stay dependency-light; tracing is a backend-only concern.
- **ADR-020 (out-of-band egress):** telemetry export must never block or back-pressure the
  hot event path or hold the session `sendLock`.

## Decision

### SDK choice

Use the **manual `io.opentelemetry` SDK** (`opentelemetry-api`, `-sdk`, `-sdk-trace`,
`-exporter-otlp`, pinned via the OTel BOM). We deliberately do **NOT** use:

- the **javaagent** (auto-instrumentation) — it instruments JVM/HTTP/DB layers, not our
  domain-level coroutine causality, and cannot be construction-gated per the OTEL-01 contract; and
- **autoconfigure** / `GlobalOpenTelemetry` as the primary wiring path — fine for app-wide
  auto-instrumentation, wrong for a construction-gated, per-instance SDK.

Tracing is **backend-only** (D-12): the OTel coordinates live on `:backend`, never on
`coroutine-viz-core` / `coroutine-viz-client` (ADR-013 preserved; `checkBytecode` enforces it).
The SDK is built once per process and **gated at construction** (OTEL-01): the first statement
after reading `observability.otel.enabled` is `if (!enabled) return`, so a disabled app builds
no SDK, no exporter, no `BatchSpanProcessor` worker thread, and no per-session subscriber.

### Causality → span mapping

- **One span per coroutine lifecycle** — opened on Created/Started, ended on
  Completed/Failed/Cancelled.
- **Parentage** is set explicitly from `parentCoroutineId` via
  `setParent(Context.root().with(Span.wrap(parentSpanContext)))`, so the span tree mirrors the
  structured-concurrency tree rather than collapsing into one flat trace. A missing parent
  (out-of-order Created) degrades gracefully to a root span.
- **Suspensions are span events** (not child spans) — a suspend/resume is an annotation on the
  coroutine's own span, keeping the tree shaped by parentage, not by suspension points.
- **Status:** `ERROR` for failed **and** cancelled coroutines, `OK` for completed.
- **Leak sweep on session close:** any still-open spans for a closing session are ended so a
  session teardown never leaks unfinished spans.

### Resilience

Export runs through a **`BatchSpanProcessor`** with a bounded queue that **drops on overflow**,
on its **own worker thread**, **out-of-band off the session `sendLock`** — mirroring the
ADR-020 egress philosophy. A stalled or unreachable collector therefore never blocks span
emission or the event hot path (verified in Plan 03's `OtelOverheadTest`, which floods the ON
path against an unreachable endpoint inside a bounded timeout). `provider.shutdown()` is
registered on `ApplicationStopping` to flush pending spans on graceful shutdown.

### Config contract

```
observability:
  otel:
    enabled:       ${OTEL_ENABLED:false}          # default-off (D-01)
    endpoint:      ${OTEL_ENDPOINT:http://localhost:4317}
    serviceName:   ${OTEL_SERVICE_NAME:coroutine-visualizer}
    samplingRatio: ${OTEL_SAMPLING_RATIO:1.0}
```

Default-off; telemetry is an explicit env opt-in only. A malformed `enabled` parses to `false`
(fails closed).

### Verification topology

```
backend (OTEL_ENABLED=true) --OTLP 4317--> otel-collector --> Jaeger (16686)
                                                          \--> Zipkin (9411)
```

ONE collector traces pipeline fans out to **both** Jaeger (`otlp/jaeger`) and Zipkin (D-13),
so a single coroutine run is verifiable in both UIs (SC#3 is dual, not either/or). The topology
ships as an additive, opt-in `docker-compose.observability.yml` + `otel-collector-config.yaml`
that does not touch the main `docker-compose.yml`. The collector uses the
`otel/opentelemetry-collector-contrib` image (the vanilla image omits the Zipkin exporter, A2),
and the removed `jaeger` collector exporter is replaced by `otlp/jaeger` against a native-OTLP
Jaeger (`COLLECTOR_OTLP_ENABLED=true`).

## Consequences

- Operators can inspect coroutine causality in standard tracing UIs with zero bespoke tooling,
  and the dual-UI check independently corroborates the span model.
- The feature is zero-cost when off (OTEL-01) and dependency-isolated to the backend (ADR-013).
- A down/slow collector degrades to dropped spans, never to a blocked or slowed application
  (ADR-020).
- **Deferred follow-ups:** the dev topology is unauthenticated and uses a local trusted
  collector (no PII in the bounded causality attribute set). Production deployments should add
  TLS + auth on the OTLP egress to a remote collector — explicitly out of scope here and tracked
  as a follow-up (threats T-12-12 / T-12-14).

## Related

- ADR-020 — out-of-band egress / drop-on-overflow philosophy mirrored by the span export path.
- ADR-013 — core library extraction / backend-only dependencies (tracing stays off the core libs).
- ADR-009 — ephemeral, default-off runtime (OTel is opt-in and zero-cost when disabled).
