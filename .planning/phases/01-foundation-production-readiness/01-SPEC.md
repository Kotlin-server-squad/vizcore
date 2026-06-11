# Phase 1: Foundation & Production Readiness — Specification

**Created:** 2026-06-11
**Ambiguity score:** 0.15 (gate: ≤ 0.20)
**Requirements:** 12 locked

## Goal

The running server uses only `coroutine-viz-core`'s session classes with a bounded (10k-default) event store, serves working SSE + raw-event + validation endpoints, renders coroutine failures as FAILED, and exposes `/api/health` (+`/live`, `/ready`), profile-selected logging, config-driven CORS, a validating OpenAPI spec, and the full ADR-020 metric set.

## Background

The 2026-06-11 audits (`.planning/VERIFICATION.md` runtime addendum, `.planning/SCENARIO-AUDIT.md`) established the current state with file-level precision:

- **Fork:** 10 duplicate `com.jh.proj.coroutineviz.session.*` classes in `backend/src/main` shadow the authoritative 16 in `coroutine-viz-core` (`SessionRoutes.kt:3` imports the fork). The fork's `EventStore` is an unbounded `CopyOnWriteArrayList`; `SessionManager.configure()` is never called.
- **RT-01:** `VizEvent` is a non-sealed interface with no `SerializersModule` polymorphic registration → `SerializationException: Serializer for subclass 'CoroutineCreated' is not found`. The SSE stream errors and ends on the first event; `GET /api/sessions/{id}/events` returns 500. Visualizations render only via the projected snapshot. Events tab is always empty; conditional Channels/Flow/Sync/Jobs tabs never mount.
- **RT-02:** `POST /api/validate/session/{id}` returns `{sessionId, results[], timing}` but `ValidationPanel.tsx:84-85` reads `data.errors.length`/`data.warnings.length` → full-page crash via error boundary.
- **SC-01:** `VizScope.kt:182` emits `CoroutineFailed` only when the exception message contains the coroutine's label; failed launch jobs report `isCancelled=true` → FAILED state unreachable; every failure renders CANCELLED (verified in 4 failure paths).
- **SCENARIO-AUDIT §3:** Cancellation scenario's targeted `child1.cancel()` is commented out (`ScenarioRunner.kt:117`); root `job.cancel()` kills the whole tree — `normal-child` never completes.
- **PROD:** `/health` exists (no `/live`, `/ready`, version, component checks; mounted at `/health`, not `/api/health`); `logback-prod.xml` exists but is never selected; CORS is config-driven (works — verified); OpenAPI spec is 2270 hand-maintained lines documenting `/health`; only 2 of 7 ADR-020 metrics are wired.

## Requirements

1. **FIX-01 — VizEvent polymorphic serialization**: All `VizEvent` subclasses (all event types, all packages — coroutine, job, flow, dispatcher, deferred, channel, sync) are registered in a kotlinx.serialization `SerializersModule` polymorphic scope installed on the server's `Json` configuration.
   - Current: No `polymorphic(`/`SerializersModule` registration anywhere in `src/main`; SSE stream dies on first event; `GET /api/sessions/{id}/events` → HTTP 500.
   - Target: SSE streams every emitted event end-to-end; `/events` returns 200 with the stored event list; Events tab and conditional Channels/Flow/Sync/Jobs tabs render.
   - Acceptance: Regression test runs a scenario over the real route and asserts `/events` returns 200 with `eventCount` items and zero `SerializationException` in logs; an SSE client receives ≥1 event without stream error.

2. **FIX-02 — Validation contract**: The frontend validation feature consumes the real backend response shape; the backend contract does not change.
   - Current: `ValidationPanel.tsx:84-85` reads `data.errors.length`/`data.warnings.length` (undefined) → `TypeError` caught by `CatchBoundaryImpl`, replacing the whole app.
   - Target: `ValidationResult` type and `ValidationPanel` consume `{sessionId, results[], timing}`, deriving error/warning groupings by filtering `results` on `type`; Run Validation renders 21-rule output for a Nested Coroutines session.
   - Acceptance: Component test feeds the real captured response JSON and asserts results render with no thrown error; clicking Run Validation in the UI never triggers the error boundary.

3. **FIX-03 — FAILED state reachable**: `VizScope`'s completion handler classifies terminal state by cause type, not message text.
   - Current: `VizScope.kt:182` requires `cause.message?.contains(label)`; all four audited failure paths render CANCELLED.
   - Target: `cause == null` → Completed; `cause is CancellationException` → Cancelled; any other Throwable → Failed. A throwing coroutine emits exactly one `CoroutineFailed`; victims still emit `CoroutineCancelled`.
   - Acceptance: Regression test runs the exception scenario and asserts `failing-child` final state == FAILED and `normal-child`/`parent` == CANCELLED in the session snapshot.

4. **FIX-04 — Cancellation scenario targeted cancel**: The Cancellation scenario cancels one child and lets its sibling complete.
   - Current: `ScenarioRunner.kt:117` (`child1.cancel()`) commented out; `:124` root `job.cancel()` at t=1s kills all three coroutines.
   - Target: `child1.cancel()` restored (after a visible delay), root cancel removed; `normal-child` runs to completion.
   - Acceptance: Regression test runs the cancellation scenario and asserts `child-to-be-cancelled` == CANCELLED and `normal-child` == COMPLETED.

5. **FND-01 — De-fork**: The backend compiles and runs against `coroutine-viz-core`'s session classes only.
   - Current: 10 duplicate session classes in `backend/src/main/.../session/` shadow core; the fork is active at runtime.
   - Target: Duplicate classes deleted; all imports resolve to `coroutine-viz-core`; behavior parity for session CRUD/scenarios.
   - Acceptance: `grep` finds zero session-class sources under `backend/src/main/.../session/` (excluding wiring/config); backend test suite passes; a scenario run via REST completes with the same snapshot shape as before.

6. **FND-02 — Bounded event store**: The running server uses the bounded EventStore with `maxEvents` default **10000**, configured via `application.yaml` (env-overridable).
   - Current: Unbounded `CopyOnWriteArrayList`; `SessionManager.configure()` never invoked.
   - Target: `SessionManager.configure(maxEvents=…)` called from `Application` startup with the value from config; events evict at the cap.
   - Acceptance: Config key present in `application.yaml`; startup log (or test) shows the bounded store in use.

7. **FND-03 — Bounded-store regression test**: A backend test proves the cap holds against the running server.
   - Current: Eviction tested only in core in isolation (`EventStoreTest.kt:50-62`).
   - Target: Test emits > maxEvents events into a session via the real wiring and asserts `store.all().size <= maxEvents`.
   - Acceptance: Named regression test exists and passes in `./gradlew test`.

8. **PROD-01 — Health endpoints**: `/api/health`, `/api/live`, `/api/ready` exist with component checks, uptime, and version; `/health` remains as an alias (nothing existing breaks).
   - Current: Only `/health`; no live/ready/version/component checks.
   - Target: `/api/health` returns `HealthStatus` (sessionManager + memory checks, uptime, version); `/api/live` and `/api/ready` return appropriate liveness/readiness; `/health` aliases `/api/health`.
   - Acceptance: `curl` each of the four paths returns 200 with documented JSON; `version` field non-empty.

9. **PROD-02 — Logging profiles**: Prod JSON logging profile is actually selectable and selected in prod deployment.
   - Current: `logback-prod.xml` exists, zero references repo-wide; dev profile ships everywhere.
   - Target: Environment-selectable profile (e.g. `-Dlogback.configurationFile` or env switch) wired into Dockerfile/compose-prod; no stray `println`.
   - Acceptance: Prod compose/container logs are JSON-formatted; dev run remains human-readable.

10. **PROD-03 — Externalized CORS**: Remains config-driven (already verified Works — keep covered by a test so it cannot regress).
    - Current: `HTTP.kt:22-65` reads `application.yaml`/env. Verified end-to-end.
    - Target: Unchanged behavior + a config-read test.
    - Acceptance: Test asserts allowed origins come from config, not literals.

11. **PROD-04 — OpenAPI accuracy**: The spec validates and matches served behavior for the endpoints this phase touches.
    - Current: 2270-line hand spec; documents `/health` only; validate endpoint schema doesn't match served JSON (RT-02 root cause).
    - Target: Spec updated for health alias + `/api/health|live|ready`, the validation response shape, and `/events`; spec passes an OpenAPI validator.
    - Acceptance: Validator exits 0; validate-endpoint schema matches a captured live response.

12. **PROD-05 — Full ADR-020 metrics**: All seven ADR-020 metrics are wired via Micrometer.
    - Current: 2 of 7 (`MetricsWiring.kt:14-24`).
    - Target: events emitted/dropped counters, scenario-duration + event-processing timers, buffer-size gauge, active-sessions and SSE-clients gauges all registered and updating.
    - Acceptance: Metrics test (or scrape) shows all seven metric names with non-default values after a scenario run.

## Boundaries

**In scope:**
- `SerializersModule` polymorphic registration for the full `VizEvent` hierarchy + ContentNegotiation/SSE wiring (FIX-01)
- Frontend `ValidationPanel`/`ValidationResult` adaptation to the real `{results, timing}` contract (FIX-02)
- `VizScope.invokeOnCompletion` cause-type classification (FIX-03)
- Cancellation scenario targeted-cancel restoration (FIX-04)
- Deletion of the `backend/src/main` session fork; core-only resolution (FND-01)
- Bounded store wiring: `application.yaml` key, default 10000, `SessionManager.configure()` at startup (FND-02) + regression test (FND-03)
- `/api/health`, `/api/live`, `/api/ready` + `/health` alias (PROD-01)
- Logback prod-profile selection wiring (PROD-02); CORS config test (PROD-03)
- OpenAPI updates for endpoints touched in this phase + validator pass (PROD-04)
- Remaining five ADR-020 metrics (PROD-05)
- Regression tests for each FIX using the headless REST checks from the audit

**Out of scope:**
- `supervisorScope` in User Registration, real `withTimeout` in Report Generation — scenario-semantics polish, deferred (audit decision: MED/LOW, nothing blocks)
- "Simulate failure" UI toggle for `?fail/?failEmail/?timeout` — frontend feature work, Phase 2 territory
- `VizScope` Job context + honest `success:false` on failed scenario runs — adjacent but behavior-changing; deferred with the scenario-polish batch
- Replay/Export/Comparison mounting — Phase 2
- Persistence (JDBC/Flyway), retention policy, auth, share tokens — Phase 3 (note: de-fork must not *block* later retention wiring)
- Sampling/batching/compression, OTEL, SDK publishing — Phase 4
- IntelliJ plugin, frontend coverage/E2E/Storybook — Phase 5
- Scenario card duration-text corrections and Deep Nesting "5 levels" off-by-one — cosmetic, backlog

## Constraints

- **Backend contract stability:** FIX-02 is resolved on the frontend; `POST /api/validate/session/{id}`'s response shape does not change.
- **maxEvents:** default **10000**, key in `application.yaml`, env-overridable. Largest audited scenario emitted 130 events; 10k never truncates a realistic teaching session.
- **Health paths:** `/api/health` (+`/live`, `/ready`) are the canonical paths; `/health` must keep working as an alias (Docker healthchecks and existing tooling depend on it).
- **No new runtime dependencies** beyond what kotlinx.serialization/Micrometer already provide.
- Kotlin conventions per CLAUDE.md: detekt/ktlint, structured concurrency, never `GlobalScope`.

## Acceptance Criteria

- [ ] `GET /api/sessions/{id}/events` returns 200 with events after a scenario run (was 500)
- [ ] SSE client receives events end-to-end with zero `SerializationException` in backend logs
- [ ] Events tab shows events; Channels tab mounts for a channel scenario session
- [ ] Run Validation renders rule results; error boundary never triggers
- [ ] Exception scenario: `failing-child` == FAILED, `normal-child` == CANCELLED (snapshot assertion)
- [ ] Cancellation scenario: `child-to-be-cancelled` == CANCELLED, `normal-child` == COMPLETED
- [ ] Zero duplicate session classes under `backend/src/main`; backend builds + tests green against core
- [ ] `application.yaml` has the maxEvents key (default 10000); regression test proves `store.all().size <= maxEvents`
- [ ] `/api/health`, `/api/live`, `/api/ready`, and `/health` all return 200 with component checks/uptime/version on the health responses
- [ ] Prod container logs JSON; dev logs human-readable
- [ ] OpenAPI spec passes validation; validate-endpoint schema matches live response
- [ ] All seven ADR-020 metric names present with non-default values after a scenario run

## Ambiguity Report

| Dimension          | Score | Min  | Status | Notes                                              |
|--------------------|-------|------|--------|----------------------------------------------------|
| Goal Clarity       | 0.88  | 0.75 | ✓      | Goal + 12 falsifiable REQ-IDs                      |
| Boundary Clarity   | 0.85  | 0.70 | ✓      | Deferred items explicitly out with reasons         |
| Constraint Clarity | 0.80  | 0.65 | ✓      | maxEvents, health alias, contract direction locked |
| Acceptance Criteria| 0.85  | 0.70 | ✓      | 12 pass/fail checkboxes, audit-derived             |
| **Ambiguity**      | 0.15  | ≤0.20| ✓      |                                                    |

## Interview Log

| Round | Perspective              | Question summary                                  | Decision locked                                                        |
|-------|--------------------------|---------------------------------------------------|------------------------------------------------------------------------|
| 0     | Researcher (scout)       | Current state                                     | Grounded in VERIFICATION.md addendum + SCENARIO-AUDIT.md (same session) |
| 1     | Researcher/Boundary      | FIX-02: which side of the contract moves?         | Frontend adapts; backend `{results, timing}` shape unchanged            |
| 1     | Boundary Keeper          | Scenario-audit deferred items in Phase 1?         | None — all deferred (supervisorScope, withTimeout, failure toggle, VizScope Job, duration text) |
| 1     | Constraint               | maxEvents default + config location?              | 10000 default, `application.yaml` key, env-overridable                  |
| 1     | Constraint/Boundary      | Health path: `/api/health` vs existing `/health`? | `/api/health` (+live/ready) canonical; `/health` kept as alias          |

Derived (not asked): FIX-01 registers **all** event types — any unregistered subclass re-breaks the SSE stream, so a subset cannot satisfy "SSE works end-to-end".

---

*Phase: 01-foundation-production-readiness*
*Spec created: 2026-06-11*
*Next step: /gsd-discuss-phase 1 — implementation decisions (how to build what's specified above)*
