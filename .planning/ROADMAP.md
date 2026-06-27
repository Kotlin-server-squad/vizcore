# Roadmap: vizcore — Visualizer for Coroutines

## Overview

vizcore is a brownfield product (~92% built): the event-sourced backend, 48 event types, instrumentation wrappers, validation engine, and a rich React visualization frontend already work. This roadmap closes out the remaining feature work and — critically — repairs the verified structural gaps that block production. The journey starts by removing the duplicate session-package fork (root cause of the unbounded-store and unwired-perf gaps) and hardening the running server, then delivers the highest-visible user value (replay, export, comparison), then makes the product safe to deploy multi-user (persistence, auth, sharing), then scales and packages it (perf, observability, SDK), and finally completes the advisory IntelliJ plugin and the frontend test/quality bar.

## Phases

**Phase Numbering:**

- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

- [x] **Phase 1: Foundation & Production Readiness** - Remove the session-package fork, wire the bounded store + full metrics, add health/logging/CORS/OpenAPI (5 plans executed; code-verification gap-closure 01-06..01-08 done; UAT gap-closure 01-09..01-12 done; re-verification found 2 live-stream freshness blockers — gap-closure plan 01-13 added) (completed 2026-06-12)
- [x] **Phase 2: User-Value Visualization** - Replay/time-travel, PNG/SVG/WebM export, side-by-side session comparison (completed 2026-06-20)
- [x] **Phase 3: Persistence, Auth & Sharing** - Optional JDBC store + retention, route-level auth + tenant isolation, shareable read-only sessions (executed 2026-06-21; verification GAPS FOUND — AUTH-04 tenant isolation not enforced, pending gap-closure) (completed 2026-06-21)
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

**Plans**: 15 plans (5 executed + 3 code-verification gap-closure + 4 UAT gap-closure + 1 re-verification gap-closure + 2 UAT round-2 gap-closure)
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

- [x] 01-12-PLAN.md — UAT gaps: debounce SSE polling storm, completion-aware scenario button, Connected badge on first onopen, StructuredConcurrencyInfo parent-FAILED copy + button-state/copy tests

**Wave 8** *(re-verification gap closure — blocked on Wave 7; closes CR-01/CR-02 introduced by 01-12, shared use-event-stream.ts/use-thread-activity.ts/SessionDetails.tsx)*

- [x] 01-13-PLAN.md — Re-verification gaps (CR-01/CR-02): invalidate the thread-activity query key in the SSE debounce + 5s live fallback poll so the Threads tab stops freezing; add max-wait caps to both trailing-edge debounces so a sustained event stream can't starve the session-snapshot refresh + regression tests

**Wave 9** *(UAT round-2 gap closure — 01-14 and 01-15 run in parallel; no file overlap)*

- [x] 01-14-PLAN.md — UAT gap 1 (REVIEW CR-02): align frontend thread-activity types/hooks to the real Map<threadId, ThreadEvent[]> wire shape via a buildThreadLanes adapter, remove the SessionDetails double cast, fix the MSW mock + value-asserting integration test on the live shape
- [x] 01-15-PLAN.md — UAT gap 2: backend SSE sends an immediate `: connected` comment so headers flush on 0-event sessions; frontend useEventStream gains bounded exponential-backoff retry for fatal EventSource errors + seq-based replay dedup + zero-event flush and retry tests

### Phase 2: User-Value Visualization

**Goal**: A developer can replay a captured session step-by-step, export visualizations to share, and compare two sessions side-by-side — the highest-visibility "see and understand" value, all client-side.
**Depends on**: Phase 1
**Requirements**: RPLY-01, RPLY-02, RPLY-03, EXPT-01, EXPT-02, CMPR-01, CMPR-02
**Success Criteria** (what must be TRUE):

  1. A developer can play/pause/stop/step a session, scrub to any position, and choose 0.5x-5x speed, with every panel reflecting the current event and animations respecting replay speed.
  2. A developer can export any visualization as PNG, a graph view as standalone SVG, and a replay as WebM video, with downloads triggered in-browser.
  3. A developer can run `GET /api/sessions/compare?a=&b=` and view two sessions side-by-side with event-count, duration, and thread-utilization delta highlights.

**Plans**: 8 plans (4 waves)
Plans:

**Wave 1** *(backend + frontend foundation, parallel — zero file overlap)*

- [x] 02-01-PLAN.md — Compare-route rename to `/api/sessions/compare?a=&b=` + thread-utilization delta (CMPR-01/OQ-3) + OpenAPI doc + shared/api-types regen + frontend client/type alignment
- [x] 02-02-PLAN.md — FQCN fork reconciliation (events/ + checksystem/ into core, ns→ms fix landed in core) + ForkDeletionTest extension + strict-404 read-path audit (D-12, folded todos)
- [x] 02-03-PLAN.md — Client-side event→snapshot projection layer (projectCoroutines/projectThreadActivity, OQ-1) tested against the server snapshot oracle + useReplay 50–2000ms clamp + Wave-0 use-replay test
- [x] 02-04-PLAN.md — HeroUI 2.7 toast upgrade + ToastProvider at root + toast helper + full-suite React Aria regression gate (human smoke checkpoint)

**Wave 2** *(frontend features, parallel)*

- [x] 02-05-PLAN.md — Export menu: PNG info header (EXPT-01/D-08) + standalone style-inlined SVG with `<svg>`-root auto-detect (EXPT-02/D-21) + JSON event export (D-22) + ExportMenu dropdown with toasts *(depends on 02-04)*
- [x] 02-06-PLAN.md — `/compare` route with shareable `?a=&b=` + ComparisonView controlled selection + SyncedTreePair (two synced trees, delta badges, selection sync) + Compare nav + session-not-found state (CMPR-02, D-10/11/19/20) *(depends on 02-01)*

**Wave 3** *(replay integration)*

- [x] 02-07-PLAN.md — Mount replay in SessionDetails: ReplayController Stop/FastForward/keyboard + sticky bar + REPLAY chip/new-events badge + projected event-derived panels + gated SSE buffering + dim/scrub/animate + ExportMenu mount (RPLY-01/02/03, D-01..18) *(depends on 02-03/02-04/02-05)*

**Wave 4** *(WebM recording)*

- [x] 02-08-PLAN.md — WebM recording: pure pipeline (codec cascade, 2x mirror-canvas capture, duration estimate) + useRecordReplay scripted flow + RecordConfirmModal + abort-on-hidden + SessionDetails wiring (EXPT-02 video, D-05..08/23..27) *(depends on 02-05/02-07)*

**UI hint**: yes

### Phase 3: Persistence, Auth & Sharing

**Goal**: vizcore is safe to deploy for multiple users — sessions can persist across restarts, every non-public route enforces authentication with tenant isolation, and sessions can be shared as read-only links.
**Depends on**: Phase 1
**Requirements**: PERS-01, PERS-02, PERS-03, AUTH-01, AUTH-02, AUTH-03, AUTH-04, AUTH-05, SHAR-01, SHAR-02
**Success Criteria** (what must be TRUE):

  1. With `storage.type=database`, sessions and events survive a backend restart (Exposed + HikariCP, Flyway-migrated H2/PostgreSQL, JSONB events) and a retention policy trims old data in the background.
  2. With `API_KEY` set, requests without a valid (SHA-256-compared) `X-API-Key` are rejected while `/health`, `/openapi.json`, and the token endpoint stay open; JWT issues role-bearing principals; sessions are tenant-isolated — all covered by end-to-end tests.
  3. A developer can mint a revocable, expiring share token and anyone with that token can open a rate-limited, read-only shared view.

**Plans**: 7 plans (6 waves)
Plans:

**Wave 1** *(persistence seam + migrations — foundation for everything else)*

- [x] 03-01-PLAN.md — Optional Exposed/HikariCP JDBC store behind the existing seam + Flyway V1 schema (sessions/events/shares) + restart-survival (PERS-01, PERS-02)

**Wave 2** *(auth backend — depends on 03-01: shares build.gradle/yaml/Application.kt)*

- [x] 03-02-PLAN.md — Dual-provider auth: SHA-256 multi-key + config-seeded-user JWT + token endpoint, fail-open when unconfigured, routes wrapped, SSE query-param token, E2E enforcement (AUTH-01, AUTH-02, AUTH-03, AUTH-05)

**Wave 3** *(tenancy/retention + sharing backend — parallel, no file overlap; both depend on 03-01 + 03-02)*

- [x] 03-03-PLAN.md — Tenant isolation (forUser/tenantId filter, D-03) + DB-aware retention with active-share guard (AUTH-04, PERS-03)
- [x] 03-04-PLAN.md — DB-backed ShareService + 4 share endpoints + per-IP RateLimit 429 (SHAR-01, SHAR-02)

**Wave 4** *(frontend auth — depends on 03-02)*

- [x] 03-05-PLAN.md — auth-store + api-client Bearer/401 interception + token-aware SSE + /login route (AUTH-03; D-05/06/07/08)

**Wave 5** *(frontend sharing UI — depends on 03-04 + 03-05 api-client)*

- [x] 03-06-PLAN.md — readOnly SessionDetails + /shared/:token shell + Share dialog + Manage-shares list (SHAR-01, SHAR-02; D-09/10/11/13)

**Wave 6** *(gap closure — tenant-isolation fixes from 03-VERIFICATION; depends on the executed 03-01..03-06)*

- [x] 03-07-PLAN.md — Enforce tenant scope on session sub-resources + SSE stream (CR-01), share-owner ownership on mint/list/revoke (CR-02), and e2e tests guarding the isolation invariant (AUTH-04, AUTH-05)

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

## Milestone v1.1 — Real-Code Coroutine Observability (added 2026-06-24, reprioritized)

Point vizcore at the developer's **own running Kotlin app** (dev-first) and visualize it
live in the existing FE. Design record: `.planning/notes/real-code-observability-architecture.md`.
Reprioritized **ahead of the remaining Phase 4/5 work** (which becomes supporting / future).

### Phase 6: Pluggable Instrumentation Source + DebugProbesSource

**Goal**: A pluggable source layer feeds the existing pipeline; DebugProbes captures any app's coroutines with source attribution.
**Depends on**: Phase 1
**Requirements**: RCO-01, RCO-02, RCO-03
**Success Criteria** (what must be TRUE):

  1. An `InstrumentationSource` interface fronts the `EventBus`; existing wrappers are a `WrapperSource`; sources run concurrently + toggle independently; downstream is unchanged.
  2. A `DebugProbesSource` polls `dumpCoroutinesInfo()` (~150 ms), diffs by coroutine identity, and synthesizes existing `VizEvent`s — a sample app animates in the FE with no manual wrapping.
  3. Each coroutine shows function + `file:line` (creation stack), dispatcher, and `CoroutineName`.

**Plans**: 2 plans (2 waves)
Plans:

**Wave 1**

- [ ] 06-01-PLAN.md — InstrumentationSource interface + WrapperSource (zero wrapper behavior change) + composable SessionManager.onSessionCreated registry + MetricsWiring migration (RCO-01)

**Wave 2** *(depends on 06-01: needs the InstrumentationSource interface)*

- [ ] 06-02-PLAN.md — DebugProbesSource poller: kotlinx-coroutines-debug dep, pure SnapshotDiffer, attribution extractor + DebugProbesEventSynthesizer, ref-counted install poll loop (injected dump + virtual clock) + @Tag-gated smoke IT (RCO-02, RCO-03)

### Phase 7: Real-App Transport (client lib + ingest)

**Goal**: A real app in a separate JVM streams its coroutine events to vizcore.
**Depends on**: Phase 6
**Requirements**: RCO-04, RCO-05
**Success Criteria** (what must be TRUE):

  1. An embeddable client lib installs `DebugProbesSource` and streams events to the backend over HTTP/WebSocket; zero overhead when disabled.
  2. A backend ingest endpoint accepts remote event streams into an auth-scoped session's `EventBus`, reusing the existing SSE/FE path.

**Plans**: 4 plans in 4 waves
Plans:
**Wave 1**

- [x] 07-01-PLAN.md — Prerequisite refactor: move vizEventSerializersModule/appJson (+test) into coroutine-viz-core so backend and the new client share one serializer (RCO-04 enabler)

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 07-02-PLAN.md — Backend ingest: install WebSockets plugin + auth-scoped, tenant-filtered webSocket(/api/sessions/{id}/ingest) → deserialize → VizSession.send; testApplication WS + AUTH-04/credential tests (RCO-05)

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 07-03-PLAN.md — New coroutine-viz-client module: VizcoreClient (creates session via JWT, local VizSession w/ server id, drives DebugProbesSource, forwards over Ktor client WS, reconnect/backoff, stop); in-process round-trip + zero-overhead + reconnect tests (RCO-04)

**Wave 4** *(blocked on Wave 3 completion)*

- [x] 07-04-PLAN.md — Gap closure (CR-01 / RCO-04): lifetime-scoped OutboundBuffer (single bus collector + per-socket drain) bridges the startup + reconnect-backoff windows so no event is lost; surfaced dropped counter; remove delay(200) and assert zero-loss completeness before-connect + across a forced reconnect

### Phase 8: Live Real-App View + Metrics

**Goal**: The developer watches their real app live, with aggregate health metrics.
**Depends on**: Phase 7
**Requirements**: RCO-06, RCO-07
**Success Criteria** (what must be TRUE):

  1. The existing FE animations render a real app's coroutines in real-time via SSE ("what's running now").
  2. A metrics panel shows active/peak counts, throughput, dispatcher/thread-pool utilization, and leak detection.

**Plans**: 3 plans (3 waves)
Plans:

**Wave 1** *(backend core — no deps)*

- [x] 08-01-PLAN.md — Hierarchy reconstruction (HierarchyReconstructor + batch toSnapshots) + parentCoroutineId/scopeId propagation through the synthesizer so the existing ProjectionService/CoroutineTree populate (RCO-06; D-01/D-02/D-03) ✅ 2026-06-24

**Wave 2** *(backend metrics — depends on 08-01: dispatcherName/scope propagation)*

- [x] 08-02-PLAN.md — Per-session MetricsProjection (active/peak/throughput/dispatcher-util/leaks, replay-consistent) + tenant-scoped GET /api/sessions/{id}/metrics + MetricsResponse/LeakDto DTOs (RCO-07; D-04/D-05/D-06/D-07) ✅ 2026-06-24

**Wave 3** *(frontend — depends on 08-01 event fields + 08-02 API shape)*

- [x] 08-03-PLAN.md — Active-only "What's running now" view (+ "Show completed (N)" / "N more" cap) + Session metrics panel + leak list (warning) reusing DispatcherOverview/StateIndicator/EmptyState; useSessionMetrics poll-while-live (RCO-06/RCO-07 FE; D-08) ✅ 2026-06-24 (code complete; tests/lint/build green) — live-demo human-verify (Task 4 / SC#1+SC#2) DEFERRED to /gsd-verify-work

**UI hint**: yes

### Phase 08.1: Align live real-app view to sketch winners — IDE-docked metric tiles (Delta L1 delivered; Delta S1/L2 source-attribution surfacing → Phase 08.2) (INSERTED)

**Goal:** Move the shipped Phase-8 live real-app view to match the locked sketch winners. **Delivered: Delta L1** — IDE-docked metric tiles in a static panel below the canvas, metrics out of the Threads tab, LIVE pill (live-UAT verified 2026-06-25). **Re-scoped out:** Delta S1 jump-to-code shipped in code (frame classifier + copy+toast) but its target components (CoroutineNodeEnhanced/CoroutineTimelineView) are unmounted, so it is not user-reachable; surfacing S1 + the deferred L2 chip moved to **Phase 08.2**.
**Requirements**: RCO-07 (metrics surfacing, delivered); RCO-06 source-attribution surfacing → 08.2
**Depends on:** Phase 8
**Plans:** 2/2 plans complete

Plans:

- [x] 08.1-01-PLAN.md — Wave 1: frame classifier + LivePill + jump-to-code (S1) code on the two file:line sites (NOT user-reachable — components unmounted; surfacing → 08.2; SourceAttribution chip/stack = Delta L2 DEFERRED) ✅ 2026-06-25 (FE gates green: tests/lint/build)
- [x] 08.1-02-PLAN.md — Wave 2: reflow SessionMetrics to a horizontal strip (2-weight numerals) + static docked panel with LivePill below the canvas; remove metrics from the Threads tab (L1) ✅ 2026-06-25 (FE gates green: 463 tests/lint/build; live-UAT verified)

### Phase 08.2: Surface source attribution + jump-to-code on a MOUNTED per-coroutine view (mount CoroutineTimelineView/detail via lazy GET /coroutines/{id}/timeline) — makes Delta S1 user-reachable and folds in the deferred live-card source chip (Delta L2) (INSERTED)

**Goal:** Make per-coroutine source attribution + jump-to-code USER-REACHABLE in the live view. Mount a per-coroutine source-detail drawer that renders the creation/suspension `file:line` frames (sourced lazily via the existing `GET /api/sessions/{id}/coroutines/{coroutineId}/timeline`), wiring the already-built jump-to-code (copy+toast) and frame classifier from 08.1 so they actually appear. FE-only — no backend change, no new dependency, literal Tailwind only (IN-12).
**Requirements**: RCO-06
**Depends on:** Phase 08.1 (reuses its LivePill, frame classifier, and jump-to-code components)
**Plans:** 2/2 plans complete

Plans:

**Wave 1** *(NEW drawer surface — no deps; unit-testable in isolation)*

- [x] 08.2-01-PLAN.md — `CoroutineSourceDrawer` (HeroUI Drawer, right/lg) + extracted headerless `CoroutineSourceStack` body (Created at / Suspended at file:line frames) reusing the LOCKED jump-to-code + lazy useCoroutineTimeline data path verbatim; colocated tests for loading/empty/error/jump-to-code/inert-library (RCO-06; D-01/D-03/D-04/D-07/D-08/D-09) ✅ 2026-06-25 (FE gates green: 472 tests / lint 0 errors / build)

**Wave 2** *(mount path — depends on 08.2-01: imports CoroutineSourceDrawer)*

- [x] 08.2-02-PLAN.md — Optional/additive `onSelect`/`selectedNodeId` on CoroutineTree + CoroutineTreeGraph (selected ring, IN-12 literal classes) + lift `selectedCoroutineId` in SessionDetails + mount the drawer in the live view; integration test proving click→drawer→file:line reachability; replay/shared views stay presentational (RCO-06; D-02/D-05/D-06) ✅ 2026-06-25 (FE gates green: 478 tests / lint 0 errors / build; tree node uses HeroUI isPressable+onPress — Rule 3; reachability proof in new SessionDetails.reachability.test.tsx — Rule 3; D-10 guards empty. Live-app UAT deferred to /gsd-verify-work)

> ⚠️ **08.2 live-UAT (2026-06-26):** Test 1 (drawer reachability) PASS; Test 2 (real jump-to-code) FAIL — drawer is reachable but shows "No source attribution yet" for every coroutine against the real backend. Root cause is a backend gap (timeline endpoint is a stub) → **Phase 08.3**. RCO-06 not yet delivered end-to-end. See `08.2-UAT.md`.

### Phase 08.3: Populate per-coroutine timeline source frames end-to-end so RCO-06 jump-to-code works against the real backend (backend: aggregate events + suspensionPoint in the timeline endpoint, regen api-types, reconcile FE, add the missing projection + integration tests) (INSERTED)

**Goal:** Make per-coroutine source attribution ACTUALLY APPEAR against the real backend, closing the RCO-06 end-to-end gap found in 08.2 live UAT (the source drawer is reachable but always empty). `ProjectionService.getCoroutineTimeline` is a stub that returns `events: []`, and `TimelineEventSummary` has no `suspensionPoint` field — so the frames the event store already holds (`CoroutineSuspended.suspensionPoint`, e.g. `SpringVizcoreDemoApplication.kt:75`) never reach the FE drawer. Implement event aggregation in the timeline projection (map `CoroutineStarted`→`coroutine.started`, `CoroutineSuspended`→`coroutine.suspended`, carry `suspensionPoint`), add the source fields to the DTO, update OpenAPI + regenerate `shared/api-types`, reconcile the hand-written FE `api.ts`, and add the missing backend projection test plus a non-mocked integration test asserting a real `file:line` frame is returned. Backend-led; NOT a sketch-alignment pass (that surface work is a separate future FE phase).
**Requirements**: RCO-06 (completes end-to-end delivery — 08.1 built the code, 08.2 mounted the drawer, 08.3 supplies the data)
**Depends on:** Phase 08.2 (the FE drawer + lazy timeline data path already consume this endpoint)
**Plans:** 3/3 plans complete

Plans:

**Wave 1** *(backend projection + DTO + non-mocked test — no deps)*

- [x] 08.3-01-PLAN.md — Add nested `suspensionPoint` to `TimelineEventSummary` + implement the `ProjectionService.getCoroutineTimeline` aggregation (CoroutineSuspended→`coroutine.suspended` carrying `suspensionPoint`, CoroutineStarted→`coroutine.started`) + non-mocked projection test asserting a real `file:line` (RCO-06; D-01 backend half/D-02/D-03/D-04/D-05a) ✅ commits 5dd9834, be6bb16, 0f4fcc1

**Wave 2** *(contract pipeline — depends on 08.3-01: needs the new DTO field)*

- [x] 08.3-02-PLAN.md — Extend OpenAPI (`SuspensionPoint` component + `suspensionPoint` field + kebab-case `kind` doc) → sync `openapi.json` → `pnpm generate` regen `generated.ts` → reconcile FE `api.ts`/`use-timeline.ts` to the generated shape (kill the divergence; `tsNanos` not `timestamp`) (RCO-06; D-01) ✅ commits adc32ed, d508301

**Wave 3** *(live UAT — depends on 08.3-01 + 08.3-02; non-autonomous human-verify)*

- [x] 08.3-03-PLAN.md — Stand up the 3-process demo harness on free alt-ports + re-run 08.2 Test 2 in live Chrome: confirm the source drawer renders a real `SpringVizcoreDemoApplication.kt:<line>` frame (not "No source attribution yet") and clipboard-copies it; record `08.3-UAT.md` (RCO-06; D-05b)

### Phase 08.4: Eliminate the duplicate-FQN model shadowing hazard (CR-01 hardening) (✅ COMPLETE)

**Goal:** Remove the latent classpath-shadowing trap surfaced by the 08.3 code review (CR-01, BLOCKER). The `com.jh.proj.coroutineviz.models.*` model classes exist TWICE under the SAME fully-qualified names — once in the `:coroutine-viz-core` SDK module and once in the `:backend` app module — and both land on the runtime classpath. The `:backend` copies shadow the SDK copies, which already caused a hard HTTP 500 in 08.3 (`NoSuchMethodError: TimelineEventSummary.<init>(... SuspensionPoint ...)` after 08.3-01 edited only the SDK copy), and `RuntimeSnapshot.kt` has ALREADY drifted between the two copies (verified via `diff`). Unit tests miss this because they compile each module in isolation; only the running app loads the shadowing class. Deliver: (1) determine authoritatively whether the `:backend` model copies are dead (no `:backend` source binds to them) or load-bearing; (2) eliminate the duplication — delete the dead `:backend` copies (preferred) or consolidate to a single module — so each model FQN resolves to exactly one class; (3) add a build-time guard (Gradle task / detekt rule / CI check) that FAILS the build on any same-FQN class duplicated across `:backend` and `:coroutine-viz-core`, so the trap cannot re-arm; (4) prove the timeline endpoint (and the broader app) still return real frames after the change. Backend-only hardening; no FE or product-behavior change.
**Requirements**: hardening / tech-debt (CR-01 from `08.3-REVIEW.md`; see also WR-01/02/03 as optional adjacent cleanups)
**Depends on:** Phase 08.3 (the bug was discovered and partially patched there — `CoroutineTimeline.kt` re-synced in commit `dc33a70`; this phase makes the fix structural and permanent)
**Plans:** 1/1 plans complete

Plans:

- [x] 08.4-01-PLAN.md — Delete 8 :backend duplicate model/sync files, add the verifyNoDuplicateSourceFqns Gradle guard wired into check, and prove the fix with an assembled-app Ktor Test Host timeline test (CR-01) — DONE: 8 deletions (50f805b), guard wired into check (f6c6107), assembled-app TimelineRouteAssembledTest (a51bed1); guard negative-check confirmed (reintroduce→FAIL→remove→PASS); ./gradlew clean check GREEN under JDK 21

### Phase 08.5: Align the frontend to the validated sketch winners (FE-alignment) (INSERTED)

**Goal:** Close the three sketch-vs-shipped divergences recorded in the sketch-findings-vizcore skill so the FE matches the validated design winners. (001 — Live view) Move the live real-app view to the winning **IDE-docked layout**: a docked bottom panel with a **header strip of metric tiles** (Active / Peak / Throughput/s / Dispatcher util) — un-bury metrics + the leak list from the current "Threads" tab; keep the existing tree/graph/timeline canvas on top; LIVE pill + ~150ms indicator stays. (002 — Source attribution) Reconcile the current right-side Drawer to the winning **inline compact-chips → expand-to-full-stack** model inside the dock's source panel: compact "Created at / Suspended at" chips by default, expand on click to the full creation+suspension stack with **user frames bold / library frames dimmed** and per-user-frame jump-to-code (jump-to-code itself already ships — preserve it). (003 — Connect/onboarding, GREENFIELD) Build the **badged sessions sidebar-as-home** (LIVE vs DEMO pill on every row, live apps + demo scenarios together, `+ Connect` action, empty state folded inline) plus the **3-step connect wizard** (add dependency → enable → run) with a copy-obvious client-lib snippet and a "waiting for events" spinner. Frontend-only (consumes existing backend/transport from Phases 7–8.4); literal Tailwind + HeroUI per IN-12; no new backend. Design contract = `Skill("sketch-findings-vizcore")` (winning markup in sources/00{1,2,3}-*/index.html). Run `/gsd-ui-phase 08.5` to produce the UI-SPEC, then `/gsd-plan-phase 08.5`.
**Requirements**: FE-ALIGN (sketch divergence closure — see vizcore-sketch-findings-fe-divergence) + ONB-01 (greenfield connect/onboarding, sketch 003)
**Depends on:** Phase 08.3 (the live view + source drawer this realigns) and Phase 07 (the client transport the connect wizard onboards)
**Plans:** 3 plans (2 waves)

Plans:

**Wave 1** *(independent surfaces — no file overlap; run in parallel)*

- [x] 08.5-01-PLAN.md — Surface 001: extract LiveDockPanel (IDE-dock shell — metric-tile header strip + live list + source slot + inline amber leaks), mount live-only in SessionDetails, retire the partial L1 strip; replay/shared keep the tabs (FE-ALIGN) — DONE: NEW LiveDockPanel.tsx + test, additive SessionMetrics showLeaks guard, live-only mount via isLiveView, partial L1 strip + duplicate SessionMetrics removed, source-selection gated to live; 59 files/492 tests + tsc 0 + lint 0 errors + build clean, no new dep (commits 70e3d8c, bf011c3)
- [ ] 08.5-03-PLAN.md — Surface 003 (ONB-01): client-side LIVE/DEMO derivation + badged SessionsSidebar-as-home (grouped rows, + Connect, inline empty state) + 3-step ConnectWizard (Snippet steps + waiting Spinner → live view); repoint routes/sessions/index.tsx

**Wave 2** *(depends on 08.5-01: mounts into the LiveDockPanel source slot, shares SessionDetails.tsx)*

- [ ] 08.5-02-PLAN.md — Surface 002: refactor CoroutineSourceStack to compact-chips → expand-to-full-stack (text-sm headers, jump-to-code preserved verbatim), mount inline in the dock right column, retire the live-view Drawer (FE-ALIGN)


## Progress

**Execution Order:**
Phases 1 → 2 → 3 complete. **Reprioritized next: 6 → 7 → 8 (v1.1 real-code observability).**
Remaining 4 (Scale/SDK/OTel) and 5 (Plugin/FE-quality) follow; the IntelliJ "Run with
Visualizer" parts of Phase 5 (IDE-01..03) are the delivery vehicle for v1.1 and may pull forward.

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Foundation & Production Readiness | 15/15 | Complete    | 2026-06-12 |
| 2. User-Value Visualization | 8/8 | Complete    | 2026-06-20 |
| 3. Persistence, Auth & Sharing | 7/7 | Complete    | 2026-06-21 |
| 6. Instrumentation Source + DebugProbesSource (v1.1) | 2/2 | Complete    | 2026-06-24 |
| 7. Real-App Transport (v1.1) | 4/4 | Complete    | 2026-06-24 |
| 8. Live Real-App View + Metrics (v1.1) | 4/4 | Complete (verified + secured + live UAT passed) | 2026-06-25 |
| 8.1 Align live view to sketch winners (v1.1) | 2/2 | Complete    | 2026-06-25 |
| 8.2 Surface source attribution + jump-to-code (mounted) (v1.1) | 0/2 | Planned | - |
| 8.3 Populate per-coroutine timeline source frames (backend, RCO-06 e2e) (v1.1) | 3/3 | Complete    | 2026-06-27 |
| 8.4 Eliminate duplicate-FQN model shadowing hazard (CR-01 hardening) (v1.1) | 1/1 | Complete    | 2026-06-27 |
| 8.5 Align frontend to sketch winners — IDE-dock + inline source stack + connect wizard (v1.1) | 1/3 | Executing | - |
| 4. Scale, Observability & SDK | 0/TBD | Deferred (post-v1.1) | - |
| 5. IntelliJ Plugin & Frontend Quality | 0/TBD | Deferred (IDE parts feed v1.1) | - |
