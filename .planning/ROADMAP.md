# Roadmap: vizcore — Visualizer for Coroutines

## Overview

vizcore is a brownfield product (~92% built): the event-sourced backend, 48 event types, instrumentation wrappers, validation engine, and a rich React visualization frontend already work. This roadmap closes out the remaining feature work and — critically — repairs the verified structural gaps that block production. The journey starts by removing the duplicate session-package fork (root cause of the unbounded-store and unwired-perf gaps) and hardening the running server, then delivers the highest-visible user value (replay, export, comparison), then makes the product safe to deploy multi-user (persistence, auth, sharing), then scales and packages it (perf, observability, SDK), and finally completes the advisory IntelliJ plugin and the frontend test/quality bar.

## Phases

**Phase Numbering:**

- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

- [ ] **Phase 1: Foundation & Production Readiness** - Remove the session-package fork, wire the bounded store + full metrics, add health/logging/CORS/OpenAPI (5 plans executed; code-verification gap-closure 01-06..01-08 done; UAT then found 7 runtime gaps — gap-closure plans 01-09..01-12 added, pending execution)
- [ ] **Phase 2: User-Value Visualization** - Replay/time-travel, PNG/SVG/WebM export, side-by-side session comparison
- [ ] **Phase 3: Persistence, Auth & Sharing** - Optional JDBC store + retention, route-level auth + tenant isolation, shareable read-only sessions
- [ ] **Phase 4: Scale, Observability & SDK** - Sampling/batching/compression + load harness, OpenTelemetry export, published SDK + CI/CD CLI
- [ ] **Phase 5: IntelliJ Plugin & Frontend Quality** - Complete the plugin run-action + tests, fill test gaps, E2E, Storybook/visual regression

## Phase Details

### Phase 1: Foundation & Production Readiness

**Goal**: The running server is structurally sound and production-observable — it uses the authoritative core session classes with a bounded event store, exposes health, logging, CORS, and full metrics, and the four high-severity runtime defects from the 2026-06-11 audits are fixed (broken event serialization, validation crash, unreachable FAILED state, broken cancellation demo).
**Depends on**: Nothing (first phase)
**Requirements**: FIX-01, FIX-02, FIX-03, FIX-04, FND-01, FND-02, FND-03, PROD-01, PROD-02, PROD-03, PROD-04, PROD-05
**Success Criteria** (what must be TRUE):

  0. SSE delivers events end-to-end and `GET /api/sessions/{id}/events` returns 200 with the stored events; Run Validation renders results without crashing; a throwing coroutine renders FAILED while cancelled victims render CANCELLED; the Cancellation scenario leaves `normal-child` COMPLETED — each backed by a regression test. (FIX-01..04 — sequence these as the first plan/wave, before the FND-01 de-fork.)
  1. The backend runs against `coroutine-viz-core`'s session classes only — the duplicate `backend/src/main/.../session/` fork is gone and the build resolves cleanly.
  2. A high-volume session never grows the event store past the configured `maxEvents` ceiling, and a regression test proves the bounded store is in use.
  3. `GET /api/health` (and `/live`, `/ready`) returns component checks, uptime, and version; logging uses dev/prod profiles; CORS reads from config.
  4. Micrometer exposes the full ADR-020 metric set (events emitted/dropped, scenario + event-processing durations, active sessions, SSE clients) and the OpenAPI spec is fully described and validates.

**Plans**: 12 plans (5 executed + 3 code-verification gap-closure executed + 4 UAT gap-closure pending)
Plans:
**Wave 1**

- [x] 01-01-PLAN.md — FIX wave: VizEvent polymorphic serialization (FIX-01) + FAILED-state classification (FIX-03) + Cancellation scenario fix (FIX-04)
- [x] 01-02-PLAN.md — FIX-02: frontend ValidationPanel/TimingReport adaptation to the real {results, timing} contract

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 01-03-PLAN.md — De-fork (FND-01) + bounded EventStore wiring (FND-02) + bounded-store/fork-deletion regression tests (FND-03)

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 01-04-PLAN.md — Health endpoints /api/health|live|ready + /health alias (PROD-01), CORS config test (PROD-03), OpenAPI accuracy (PROD-04)
- [x] 01-05-PLAN.md — Prod logback profile selection (PROD-02) + full ADR-020 7-metric set (PROD-05)

**Wave 4** *(gap closure — blocks Phase 1 pass; 01-06 and 01-07 run in parallel)*

- [x] 01-06-PLAN.md — Gap 1 (CR-02 / PROD-05): wire viz.sse.clients.active gauge into SSE stream lifecycle + value-asserting test
- [x] 01-07-PLAN.md — Gap 2 (CR-01 / FND-01,FND-03): delete the wrappers/ fork (11 files) + extend ForkDeletionTest to guard wrappers/

**Wave 5** *(gap closure — blocked on Wave 4; 01-07 must complete first)*

- [x] 01-08-PLAN.md — Gap 3 (CR-03 / FND-01): add a Job to core VizScope.coroutineContext so cancel()/cancelAndJoin() work + cancellation regression test

**Wave 6** *(UAT gap closure — 01-09, 01-10, 01-11 run in parallel; no file overlap)*

- [x] 01-09-PLAN.md — UAT gap: VizScope emits JobStateChanged before terminal event so the validator's NoEventsAfterTerminal rule passes on a clean run + terminal-ordering regression test
- [x] 01-10-PLAN.md — UAT gap: backend TimingAnalyzer converts durations ns→ms to match the documented frontend ms contract (fixes ~109,172s display) + magnitude-sanity test
- [x] 01-11-PLAN.md — UAT gap: consistent event discriminator (type→kind) across REST and SSE paths so the Jobs tab shows a non-zero count + raw-payload discriminator test

**Wave 7** *(UAT gap closure — blocked on Wave 6; 01-11 must complete first, shared SessionDetails.tsx/use-event-stream.ts)*

- [ ] 01-12-PLAN.md — UAT gaps: debounce SSE polling storm, completion-aware scenario button, Connected badge on first onopen, StructuredConcurrencyInfo parent-FAILED copy + button-state/copy tests

### Phase 2: User-Value Visualization

**Goal**: A developer can replay a captured session step-by-step, export visualizations to share, and compare two sessions side-by-side — the highest-visibility "see and understand" value, all client-side.
**Depends on**: Phase 1
**Requirements**: RPLY-01, RPLY-02, RPLY-03, EXPT-01, EXPT-02, CMPR-01, CMPR-02
**Success Criteria** (what must be TRUE):

  1. A developer can play/pause/stop/step a session, scrub to any position, and choose 0.5x-5x speed, with every panel reflecting the current event and animations respecting replay speed.
  2. A developer can export any visualization as PNG, a graph view as standalone SVG, and a replay as WebM video, with downloads triggered in-browser.
  3. A developer can run `GET /api/sessions/compare?a=&b=` and view two sessions side-by-side with event-count, duration, and thread-utilization delta highlights.

**Plans**: TBD
**UI hint**: yes

### Phase 3: Persistence, Auth & Sharing

**Goal**: vizcore is safe to deploy for multiple users — sessions can persist across restarts, every non-public route enforces authentication with tenant isolation, and sessions can be shared as read-only links.
**Depends on**: Phase 1
**Requirements**: PERS-01, PERS-02, PERS-03, AUTH-01, AUTH-02, AUTH-03, AUTH-04, AUTH-05, SHAR-01, SHAR-02
**Success Criteria** (what must be TRUE):

  1. With `storage.type=database`, sessions and events survive a backend restart (Exposed + HikariCP, Flyway-migrated H2/PostgreSQL, JSONB events) and a retention policy trims old data in the background.
  2. With `API_KEY` set, requests without a valid (SHA-256-compared) `X-API-Key` are rejected while `/health`, `/openapi.json`, and the token endpoint stay open; JWT issues role-bearing principals; sessions are tenant-isolated — all covered by end-to-end tests.
  3. A developer can mint a revocable, expiring share token and anyone with that token can open a rate-limited, read-only shared view.

**Plans**: TBD
**UI hint**: yes

### Phase 4: Scale, Observability & SDK

**Goal**: vizcore stays responsive under load, integrates with standard trace backends, and ships as a consumable SDK + CI tool.
**Depends on**: Phase 1
**Requirements**: PERF-01, PERF-02, PERF-03, PERF-04, OTEL-01, OTEL-02, SDK-01, SDK-02
**Success Criteria** (what must be TRUE):

  1. Per-event-type sampling, event batching, and SSE gzip compression (with `X-Accel-Buffering: no`) are active, and a dev-only load harness reports latency/memory while rate limiting protects session/scenario creation.
  2. An OpenTelemetry/OTLP exporter (zero overhead when disabled) produces coroutine spans verifiable in Jaeger/Zipkin with parent-child relationships preserved.
  3. `coroutine-viz-core` is published to GitHub Packages (MIT POM, semver) with a sample app, and a CLI fat JAR + `coroutineVizCheck` Gradle task run scenarios/validation in CI.

**Plans**: TBD

### Phase 5: IntelliJ Plugin & Frontend Quality

**Goal**: The advisory IntelliJ plugin actually instruments user code, and the frontend meets its test/quality bar.
**Depends on**: Phase 4 (SDK artifact available for the plugin to consume)
**Requirements**: IDE-01, IDE-02, IDE-03, FETEST-01, FETEST-02, FETEST-03
**Success Criteria** (what must be TRUE):

  1. Clicking "Run with Visualizer" launches the current run configuration with the instrumentation agent so user code emits events; a JCEF tool window loads the app and auto-detects the backend on :8080.
  2. The plugin has automated tests covering the receiver, session manager, and run action (it currently has zero).
  3. Actor/select/anti-pattern tests exist, frontend coverage is ≥80% enforced in CI, Playwright E2E covers the critical flows, and Storybook + Chromatic visual regression are in place.

**Plans**: TBD
**UI hint**: yes

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4 → 5

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Foundation & Production Readiness | 11/12 | In Progress|  |
| 2. User-Value Visualization | 0/TBD | Not started | - |
| 3. Persistence, Auth & Sharing | 0/TBD | Not started | - |
| 4. Scale, Observability & SDK | 0/TBD | Not started | - |
| 5. IntelliJ Plugin & Frontend Quality | 0/TBD | Not started | - |
