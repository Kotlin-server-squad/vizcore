---
phase: 01-foundation-production-readiness
plan: "05"
subsystem: backend-observability
tags: [logging, metrics, micrometer, prometheus, docker, prod-profile]
dependency_graph:
  requires: ["01-03"]
  provides: [prod-json-logging, full-adr020-metrics, /metrics-endpoint]
  affects: [backend/Dockerfile, docker-compose.prod.yml, MetricsWiring, VizSession, ScenarioRunnerRoutes]
tech_stack:
  added:
    - "net.logstash.logback:logstash-logback-encoder:8.1 (enables LogstashEncoder in logback-prod.xml)"
    - "Timer.start()/sample.stop() pattern for scenario.duration in ScenarioRunnerRoutes"
  patterns:
    - "Callback hooks on VizSession (onEventEmitted, onEventDropped, onEventProcessed) to avoid Micrometer dependency in coroutine-viz-core"
    - "SessionManager.onSessionCreated hook for per-session metric wiring"
    - "Per-session tagged Gauge for events.buffer.size"
key_files:
  created: []
  modified:
    - backend/build.gradle.kts
    - backend/Dockerfile
    - docker-compose.prod.yml
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/Monitoring.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/MetricsWiring.kt
    - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/VizSession.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/ScenarioRunnerRoutes.kt
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/MetricsWiringTest.kt
decisions:
  - "Used JAVA_TOOL_OPTIONS env var in docker-compose.prod.yml instead of overriding entrypoint — the Dockerfile already sets the flag; compose env provides an override point without duplicating the full ENTRYPOINT command"
  - "Implemented scenario.duration via Timer.Sample in runScenarioWithResponse helper so all scenario routes get timing without per-route boilerplate"
  - "SessionManager.onSessionCreated hook already existed in core — used it directly rather than adding a new wiring mechanism"
metrics:
  duration: ~15min
  completed: "2026-06-11"
  tasks_completed: 2
  files_modified: 8
---

# Phase 01 Plan 05: Production Logging + Full ADR-020 Metrics Summary

**One-liner:** Logstash JSON prod logging wired via JVM flag in Dockerfile, and all 7 ADR-020 Micrometer metrics registered at /metrics with callback-based counter/timer increments that keep coroutine-viz-core Micrometer-free.

## What Was Built

### Task 2 — PROD-02: Logstash dependency + prod logback profile (commit ba6e455)

Added `net.logstash.logback:logstash-logback-encoder:8.1` to `backend/build.gradle.kts` — the dependency that `logback-prod.xml` has always required (its `LogstashEncoder` class reference would have thrown `ClassNotFoundException` without it).

Updated `backend/Dockerfile` runtime stage:
- `COPY --from=build /app/src/main/resources/logback-prod.xml /app/logback-prod.xml` (copies the file to the filesystem path the JVM flag expects)
- `ENTRYPOINT ["java", "-Dlogback.configurationFile=/app/logback-prod.xml", "-jar", "app.jar"]` (D-06 JVM flag selection, not janino)

Updated `docker-compose.prod.yml`:
- Added `JAVA_TOOL_OPTIONS=-Dlogback.configurationFile=/app/logback-prod.xml` to the backend service environment (provides a compose-level override point; the Dockerfile is the canonical setting)

Dev runs are unaffected — no change to `logback.xml` or local run paths.

### Task 3 — PROD-05: 5 missing ADR-020 metrics + /metrics rename (commit 1bc1923)

**Monitoring.kt:** Renamed `/metrics-micrometer` route to `/metrics` (per ADR-020).

**VizSession.kt:** Added three zero-dependency callback hooks on `VizSession`:
- `onEventEmitted: (() -> Unit)?` — called after each successful `send()` completion
- `onEventDropped: (() -> Unit)?` — called each time the bounded EventStore evicts an event (augments the existing `EventStore.onEvict` with a session-level hook)
- `onEventProcessed: ((Long) -> Unit)?` — called with wall-clock nanos spent in `send()` (store + applier + bus)

These hooks intentionally have no Micrometer dependency — `coroutine-viz-core` stays free of the `io.micrometer` package.

**MetricsWiring.kt:** Registered all 5 missing ADR-020 metrics alongside the 2 existing gauges:
1. `events.emitted` (Counter) — incremented via `session.onEventEmitted`
2. `events.dropped` (Counter) — incremented via `session.store.onEvict`
3. `events.buffer.size` (Gauge, tagged by `sessionId`) — reads `session.store.count()`, registered per session via `SessionManager.onSessionCreated`
4. `scenario.duration` (Timer) — reference stored in `scenarioDurationTimerRef`; recorded in `ScenarioRunnerRoutes`
5. `event.processing.duration` (Timer) — records nanos from `session.onEventProcessed`

The `SessionManager.onSessionCreated` hook (already present in `coroutine-viz-core/SessionManager.kt`) is used to wire per-session callbacks and per-session gauges.

**ScenarioRunnerRoutes.kt:** Updated `runScenarioWithResponse` helper to record `scenario.duration` by wrapping the scenario block with `Timer.start()/sample.stop(scenarioDurationTimerRef)`.

**MetricsWiringTest.kt:** Extended with a third test that creates a session, runs the nested scenario, scrapes `/metrics`, and asserts all 7 ADR-020 metric names are present. All 3 tests pass; full backend suite is green.

## Verification Results

```
./gradlew test                 → BUILD SUCCESSFUL
./gradlew :test --tests "com.jh.proj.coroutineviz.MetricsWiringTest"  → BUILD SUCCESSFUL (3 tests)
grep -c 'logstash-logback-encoder' backend/build.gradle.kts           → 1
grep -c 'logback.configurationFile' backend/Dockerfile                → 1
grep -rc 'io.micrometer' coroutine-viz-core/src/main                  → 0 (all files)
```

## Deviations from Plan

### Auto-fixed Issues

None — plan executed exactly as written.

### Minor Implementation Notes

- `SessionManager.kt` was listed in `files_modified` in the plan frontmatter but required no changes — `onSessionCreated` was already declared there (Plan 03 added it). The wiring happens from `MetricsWiring.kt` by assigning to that hook.
- `docker-compose.prod.yml` uses `JAVA_TOOL_OPTIONS` env var rather than duplicating the full ENTRYPOINT command — the flag in Dockerfile is the canonical setting; compose simply ensures it applies even if the image is built without the flag (defense-in-depth for future image overrides).

## Known Stubs

None — all metrics are wired to live data sources (session count, store count, event callbacks). No hardcoded or placeholder values.

## Threat Flags

No new security-relevant surface beyond what was scoped in the plan's threat model:
- T-01-SC resolved: `logstash-logback-encoder:8.1` approved by human gate in Task 1.
- T-01-12 accepted: `/metrics` exposes session counts and buffer sizes tagged by sessionId; no secrets/PII; Phase 3 auth will gate it.
- T-01-13 accepted: LogstashEncoder structures developer-authored coroutine labels in prod JSON logs; no PII.

## Self-Check: PASSED

Files exist:
- FOUND: backend/build.gradle.kts (contains logstash-logback-encoder:8.1)
- FOUND: backend/Dockerfile (contains -Dlogback.configurationFile)
- FOUND: docker-compose.prod.yml
- FOUND: backend/src/main/kotlin/com/jh/proj/coroutineviz/Monitoring.kt (route is /metrics)
- FOUND: backend/src/main/kotlin/com/jh/proj/coroutineviz/MetricsWiring.kt (5 new metrics + callbacks)
- FOUND: backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/VizSession.kt (3 callback hooks)
- FOUND: backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/ScenarioRunnerRoutes.kt (Timer.Sample wiring)
- FOUND: backend/src/test/kotlin/com/jh/proj/coroutineviz/MetricsWiringTest.kt (3 tests including 7-metric assertion)

Commits exist:
- ba6e455: feat(01-05): add logstash dep + wire prod logback profile (PROD-02)
- 1bc1923: feat(01-05): wire 5 missing ADR-020 metrics + rename /metrics endpoint (PROD-05)
