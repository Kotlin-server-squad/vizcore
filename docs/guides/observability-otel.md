# Observability: OpenTelemetry / OTLP tracing

The backend can export coroutine causality as OpenTelemetry spans over OTLP. The feature is
**default-off** and **zero-cost when disabled** (no SDK, no exporter, no worker thread) — see
[ADR-030](../adr/030-observability-opentelemetry.md) for the architecture.

One span is emitted per coroutine lifecycle, parented by `parentCoroutineId` so the span tree
mirrors structured concurrency; suspensions appear as span events; failed and cancelled
coroutines carry an `ERROR` status.

## Enabling OTel on the backend

Tracing is controlled by the `observability.otel` config block, overridable via environment
variables (all default-off / safe defaults):

| Env var               | Config key                          | Default                  |
| --------------------- | ----------------------------------- | ------------------------ |
| `OTEL_ENABLED`        | `observability.otel.enabled`        | `false`                  |
| `OTEL_ENDPOINT`       | `observability.otel.endpoint`       | `http://localhost:4317`  |
| `OTEL_SERVICE_NAME`   | `observability.otel.serviceName`    | `coroutine-visualizer`   |
| `OTEL_SAMPLING_RATIO` | `observability.otel.samplingRatio`  | `1.0`                    |

To run the backend with OTel ON, pointing at a locally-running collector (JDK 21 — see project
conventions):

```bash
cd backend
OTEL_ENABLED=true OTEL_ENDPOINT=http://localhost:4317 ./gradlew run
```

A down or unreachable collector never blocks the application — spans are exported out-of-band
through a bounded, drop-on-overflow `BatchSpanProcessor` (ADR-020 / ADR-030). With OTel off, none
of this is constructed.

## Starting the verification topology

An additive, opt-in compose file stands up a collector that fans OTLP out to **both** Jaeger and
Zipkin. It is separate from the main `docker-compose.yml` and does not modify it:

```bash
docker compose -f docker-compose.observability.yml up -d
```

This brings up three services:

| Service          | Image                                       | URL / port               |
| ---------------- | ------------------------------------------- | ------------------------ |
| `otel-collector` | `otel/opentelemetry-collector-contrib:latest` | OTLP gRPC `:4317`, HTTP `:4318` |
| `jaeger`         | `jaegertracing/all-in-one:latest`           | Jaeger UI `:16686`       |
| `zipkin`         | `openzipkin/zipkin:latest`                  | Zipkin UI `:9411`        |

The backend exports to the **collector** on `localhost:4317`; the collector relays to Jaeger and
Zipkin over the docker network (Jaeger's own OTLP port is intentionally not published to the host
to avoid a clash with the collector). See `otel-collector-config.yaml` for the single traces
pipeline that exports to both `otlp/jaeger` and `zipkin`.

Tear down when finished:

```bash
docker compose -f docker-compose.observability.yml down
```

## SC#3 dual-UI verification

The acceptance check for OTEL-02 (SC#3) is that the **same** coroutine run's correctly-parented
spans are visible in **both** Jaeger and Zipkin (not either/or):

1. Start the topology: `docker compose -f docker-compose.observability.yml up -d` and wait for
   all three containers to be healthy.
2. Run the backend with OTel ON (see above), from `backend/` with JDK 21.
3. Drive a coroutine scenario — e.g. POST a scenario via the existing scenarios route, or connect
   the demo app — so coroutines are created → suspended → completed / failed / cancelled.
4. Open the **Jaeger UI** at <http://localhost:16686>, select service `coroutine-visualizer`,
   and find the run's trace. Confirm:
   - spans are **nested by causality** (a child span's parent matches its `parentCoroutineId`,
     not one flat single-span trace);
   - a failed / cancelled coroutine's span shows **ERROR**;
   - a suspension shows as a span **event**, not a child span.
5. Open the **Zipkin UI** at <http://localhost:9411> and find the **same** run's trace. Confirm
   the same span tree and statuses are visible.
6. Confirm both UIs show the same run — SC#3 is dual, not either/or.

If a trace appears flat, has the wrong parent, is missing a status, or only shows in one UI, the
mapping or the collector fan-out is misconfigured — re-check the backend OTel wiring (Plans 01–03)
and `otel-collector-config.yaml`.

## Related

- [ADR-030: Observability — OpenTelemetry / OTLP](../adr/030-observability-opentelemetry.md)
- [ADR-020: Performance scaling](../adr/020-performance-scaling.md) — out-of-band egress philosophy.
