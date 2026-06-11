---
phase: 01-foundation-production-readiness
verified: 2026-06-11T20:30:00Z
status: gaps_found
score: 9/11 must-haves verified
overrides_applied: 0
gaps:
  - truth: "The backend runs against coroutine-viz-core's session classes only — the duplicate backend/src/main/.../session/ fork is gone and the build resolves cleanly"
    status: verified
    reason: "Session fork IS gone; see below — but wrappers/ fork still exists (flagged separately)"
  - truth: "All seven ADR-020 metrics are wired via Micrometer and present with non-default values after a scenario run, scraped at /metrics"
    status: failed
    reason: "viz.sse.clients.active gauge is permanently 0 — sseClientsGauge (AtomicInteger) is declared in MetricsWiring.kt and registered as a gauge but is never incremented or decremented anywhere in the codebase. After a scenario run the SSE clients gauge reads its default (0). MetricsWiringTest only asserts the metric name is present, not its value. This is CR-02 from the code review."
    artifacts:
      - path: "backend/src/main/kotlin/com/jh/proj/coroutineviz/MetricsWiring.kt"
        issue: "sseClientsGauge declared at line 15 but no call to incrementAndGet()/decrementAndGet() exists anywhere in the codebase"
      - path: "backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt"
        issue: "SSE stream handler never touches sseClientsGauge — no increment on connect, no decrement on disconnect"
      - path: "backend/src/test/kotlin/com/jh/proj/coroutineviz/MetricsWiringTest.kt"
        issue: "Test only asserts metric name presence (body.contains), not that value > 0 after any SSE activity — the dead gauge is invisible to CI"
    missing:
      - "Add sseClientsGauge.incrementAndGet() at SSE stream entry in SessionRoutes.kt SSE handler, with decrementAndGet() in a finally block on disconnect"
      - "Extend MetricsWiringTest to assert viz_sse_clients_active value changes while a stream is open (or drops to 0 after disconnect)"
  - truth: "The wrappers/ package fork is eliminated so classloader ordering cannot decide which VizScope executes"
    status: failed
    reason: "CR-01 from the code review: backend/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/ contains 11 Kotlin files with the same package (com.jh.proj.coroutineviz.wrappers) as coroutine-viz-core/src/main/.../wrappers/. These are duplicate FQCNs compiled into the same fat jar. At runtime classloader ordering decides which class loads. InstrumentedFlow.kt has diverged by 55 lines between the two locations. ForkDeletionTest only guards session/ (not wrappers/). This is the exact failure mode FND-01 was stated to eliminate — the phase narrowly interpreted FND-01 as 'session/ fork' and left the wrappers/ fork in place."
    artifacts:
      - path: "backend/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/"
        issue: "11 files with same FQCNs as coroutine-viz-core wrappers — classloader race condition"
      - path: "backend/src/test/kotlin/com/jh/proj/coroutineviz/ForkDeletionTest.kt"
        issue: "Guards session/ only; wrappers/ fork can regress undetected"
    missing:
      - "Delete backend/src/main/.../wrappers/* files that duplicate coroutine-viz-core (reconcile divergent content into core first)"
      - "Extend ForkDeletionTest to also assert no wrapper class files duplicate those in coroutine-viz-core"
human_verification:
  - test: "Run Validation end-to-end: create a session, run a scenario, click Run Validation in the browser"
    expected: "Validation panel renders with Failures/Passes/Timing sections without an error boundary crash"
    why_human: "Requires a live browser session and real backend; automated tests prove component renders with mock data but not with the live backend response through the full stack"
  - test: "SSE stream visual verification: open a session, run a scenario while watching the Events tab"
    expected: "Events appear in real-time in the Events tab as they are emitted during scenario execution"
    why_human: "SessionEventsIntegrationTest proves GET /events returns 200 + events; SseStreamTest proves SSE serialization path; but live browser rendering of the event stream is not covered by automated tests"
---

# Phase 01: Foundation & Production Readiness — Verification Report

**Phase Goal:** The running server is structurally sound and production-observable — it uses the authoritative core session classes with a bounded event store, exposes health, logging, CORS, and full metrics, and the four high-severity runtime defects from the 2026-06-11 audits are fixed (broken event serialization, validation crash, unreachable FAILED state, broken cancellation demo).

**Verified:** 2026-06-11T20:30:00Z
**Status:** gaps_found
**Re-verification:** No — initial verification
**Code Review Cross-Reference:** 01-REVIEW.md (3 critical findings; CR-01 and CR-02 remain open)

---

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | GET /api/sessions/{id}/events returns 200 with events; SSE delivers ≥1 event end-to-end; zero SerializationException (FIX-01) | VERIFIED | VizEventSerializersModule.kt registers all 66 VizEvent subclasses via polymorphic(); appJson wired into both ContentNegotiation (Serialization.kt:17) and SSE handler (SessionRoutes.kt:201,217). SessionEventsIntegrationTest (4 tests, 0 failures) asserts 200 + non-empty polymorphic JSON array. SseStreamTest passes with PolymorphicSerializer replacing stale CoroutineCreated cast. |
| 2 | Run Validation renders results without crashing the page (FIX-02) | VERIFIED | ValidationPanel.tsx reads data.results.filter(r => r.type === 'Fail'/'Pass') — all data.valid/data.errors/data.warnings references gone. api.ts exports ValidationResponse/ValidationRuleResult/BackendTimingReport matching backend contract. TimingReportView renders timing.totalDuration/coroutineDurations/suspensionDurations. ValidationPanel.test.tsx feeds real backend shape; full frontend suite passed (225 tests). |
| 3 | A throwing coroutine renders FAILED (emits CoroutineFailed) while cancelled victims render CANCELLED (FIX-03) | VERIFIED | VizScope.kt completion handler fixed: `cause !is CancellationException` branch (line 182) replaced the broken message-contains-label branch. isCancelled=false set for FAILED path (line 192). Applied to both backend/src/main/wrappers/VizScope.kt and coroutine-viz-core VizScope.kt. VizScopeCompletionHandlerTest (2 tests, 0 failures) asserts CoroutineFailed emitted for throwing child; no CoroutineCancelled for that child. |
| 4 | Cancellation scenario leaves child-to-be-cancelled CANCELLED and normal-child COMPLETED (FIX-04) | VERIFIED | ScenarioRunner.kt: child1.cancel() uncommented at line 120 with vizDelay(500) before it; external job.cancel() removed; child2.join() added. CancellationScenarioRegressionTest (2 tests, 0 failures) asserts cancelledEvents contains 'child-to-be-cancelled' and completedEvents contains 'normal-child'. Parent completes normally. |
| 5 | Session fork deleted — backend resolves session classes from coroutine-viz-core only (FND-01/SC1) | VERIFIED | `find backend/src/main/.../session -name '*.kt' | wc -l` returns 0. All 10 fork files removed in commit 46221e6. ForkDeletionTest CI guard passes. Backend compiles cleanly. Note: wrappers/ fork remains (see Gap 3 / CR-01 — a separate BLOCKER). |
| 6 | A high-volume session never grows event store past maxEvents; regression test proves bounded store is in use (FND-02/FND-03/SC2) | VERIFIED | application.yaml has `maxEvents: ${SESSION_MAX_EVENTS:10000}`. Application.kt calls SessionManager.configure(maxEventsPerSession=maxEvents) before configureRouting(). BoundedStoreRegressionTest uses small cap (20), sends >20 events via VizSession.send(), asserts store.all().size <= 20. BoundedStoreWiringTest boots module and asserts bounded store. Both tests pass. |
| 7 | GET /api/health, /api/live, /api/ready all return 200 with component checks, uptime, version; logging uses dev/prod profiles; CORS reads from config (PROD-01/02/03/SC3) | VERIFIED | HealthRoutes.kt: respondHealth() shared helper; /api/health, /api/live, /api/ready registered in route("/api"); /health alias kept. HealthStatus has version="0.0.1" and components map. HealthRoutesTest (7 tests). CorsConfigTest asserts configured origin returns ACAO header; non-configured origin does not. logstash-logback-encoder:8.1 in build.gradle.kts. Dockerfile ENTRYPOINT has -Dlogback.configurationFile=/app/logback-prod.xml. logback-prod.xml file exists. |
| 8 | OpenAPI spec documents health endpoints, validation shape, and /events; spec passes redocly lint (PROD-04/SC4 partial) | VERIFIED | documentation.yaml has /api/health, /api/live, /api/ready paths. ValidationResponse schema with results[] (Pass/Fail discriminator) and TimingReport (coroutineDurations/suspensionDurations/totalDuration). /api/sessions/{id}/events at line 332. security: [] global. redocly lint exits 0 per 01-04-SUMMARY (0 errors, 48 pre-existing operation-4xx-response warnings on scenario endpoints). |
| 9 | Micrometer registers all 7 ADR-020 metrics; 6 of 7 have non-default values after scenario run (PROD-05/SC4 partial) | PARTIAL | MetricsWiring.kt registers all 7 names: viz.sessions.active, viz.sse.clients.active, events.emitted, events.dropped, events.buffer.size, scenario.duration, event.processing.duration. /metrics endpoint confirmed (Monitoring.kt:20). Counter/Timer callbacks wired: onEventEmitted, onEventDropped, onEventProcessed on VizSession. scenario.duration wired via Timer.Sample in ScenarioRunnerRoutes. coroutine-viz-core has 0 Micrometer imports. MetricsWiringTest (3 tests) passes name-presence assertion. FAIL: viz.sse.clients.active is permanently 0 — see Gap 2. |
| 10 | The wrappers/ package fork is eliminated (CR-01 from code review — not in ROADMAP SC but flagged as BLOCKER by reviewer) | FAILED | backend/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/ contains 11 files with same FQCNs as coroutine-viz-core wrappers. InstrumentedFlow.kt has diverged by 55 lines. ForkDeletionTest guards session/ only. Classloader ordering decides which wrappers class loads at runtime — identical failure mode to what FND-01 was designed to prevent. |
| 11 | VizScope has a Job in its context so cancel()/cancelAndJoin() are functional (CR-03 from code review) | FAILED | coroutine-viz-core VizScope line 59: `override val coroutineContext: CoroutineContext = context + CoroutineName(...)` — no Job. sseClientsGauge.cancelAndJoin() at line 398 is a silent no-op. Scenario coroutines are unparented (GlobalScope-equivalent). Session close cannot stop running scenarios. This is CR-03 — not in the ROADMAP SC but identified as critical by review. |

**Score:** 8/11 truths verified (Truths 1-8 VERIFIED, Truth 9 PARTIAL, Truths 10-11 FAILED)

**Scoring adjustment:** Truths 10 and 11 are from the code review, not the ROADMAP success criteria. Against ROADMAP SC specifically: SC0 (FIX-01..04) = VERIFIED, SC1 (session fork) = VERIFIED, SC2 (bounded store) = VERIFIED, SC3 (health/logging/CORS) = VERIFIED, SC4 (metrics+spec) = PARTIAL (SSE clients gauge dead). ROADMAP SC score: 4.5/5.

---

## Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `backend/src/main/kotlin/com/jh/proj/coroutineviz/VizEventSerializersModule.kt` | 66-subclass polymorphic SerializersModule + appJson | VERIFIED | 66 subclass() registrations confirmed |
| `backend/src/test/kotlin/com/jh/proj/coroutineviz/events/VizEventSerializersModuleTest.kt` | Registration completeness guard (D-04) | VERIFIED | Exists, 0 failures |
| `backend/src/test/kotlin/com/jh/proj/coroutineviz/wrappers/VizScopeCompletionHandlerTest.kt` | FIX-03 regression (plan called ExceptionScenarioRegressionTest — created as unit test instead of route test) | VERIFIED (deviation) | 2 tests, 0 failures; asserts CoroutineFailed for throwing child. Route-level integration test was not created per plan artifact list — unit test covers the truth. |
| `backend/src/test/kotlin/com/jh/proj/coroutineviz/scenarios/CancellationScenarioRegressionTest.kt` | FIX-04 regression | VERIFIED | 2 tests, 0 failures; asserts child-to-be-cancelled=CANCELLED, normal-child=COMPLETED |
| `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/SessionEventsIntegrationTest.kt` | GET /events 200 + non-empty list (FIX-01 acceptance) | VERIFIED | 4 tests, 0 failures |
| `backend/src/test/kotlin/com/jh/proj/coroutineviz/ForkDeletionTest.kt` | Static guard for session fork re-introduction (FND-01) | VERIFIED | Exists, guards session/ only — wrappers/ unguarded (see gaps) |
| `backend/src/test/kotlin/com/jh/proj/coroutineviz/BoundedStoreWiringTest.kt` | Bounded store wiring smoke test | VERIFIED | Exists, passes |
| `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/BoundedStoreRegressionTest.kt` | Eviction regression test (FND-03) | VERIFIED | Exists, asserts store.all().size <= maxEvents through real wiring |
| `backend/src/main/resources/application.yaml` | session.maxEvents config key | VERIFIED | Line 16: `maxEvents: ${SESSION_MAX_EVENTS:10000}` |
| `backend/src/main/kotlin/com/jh/proj/coroutineviz/Application.kt` | SessionManager.configure at startup | VERIFIED | Line 20: SessionManager.configure(maxEventsPerSession=maxEvents) before configureRouting() |
| `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/HealthRoutes.kt` | /api/health, /live, /ready + /health alias; version + components | VERIFIED | All 4 routes present; HealthStatus has version and components |
| `backend/src/test/kotlin/com/jh/proj/coroutineviz/CorsConfigTest.kt` | CORS config-read regression test | VERIFIED | 2 tests asserting configured origin allowed and non-configured rejected |
| `backend/src/main/resources/openapi/documentation.yaml` | Updated spec passing redocly lint | VERIFIED | /api/health, /live, /ready paths added; ValidationResponse schema correct; security: [] |
| `backend/build.gradle.kts` | logstash-logback-encoder:8.1 dep | VERIFIED | Line 44 confirmed |
| `backend/Dockerfile` | Prod logback profile via JVM flag | VERIFIED | ENTRYPOINT with -Dlogback.configurationFile=/app/logback-prod.xml |
| `backend/src/main/kotlin/com/jh/proj/coroutineviz/MetricsWiring.kt` | All 7 ADR-020 metrics wired | PARTIAL | 6/7 metrics functional; viz.sse.clients.active gauge permanently 0 |
| `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/VizSession.kt` | onEventEmitted/onEventDropped/onEventProcessed callback hooks | VERIFIED | Lines 64, 71, 77; no Micrometer import |

---

## Key Link Verification

| From | To | Via | Status | Details |
|------|-----|-----|--------|---------|
| Serialization.kt | vizEventSerializersModule | json(appJson) | VERIFIED | Line 17: `json(appJson)` |
| SessionRoutes.kt | appJson | appJson.encodeToString(PolymorphicSerializer(VizEvent::class), event) | VERIFIED | Lines 201, 217 |
| Application.kt | SessionManager.configure | startup reads session.maxEvents | VERIFIED | Line 20 before configureRouting() |
| Application.kt | session.maxEvents | environment.config.propertyOrNull | VERIFIED | Pattern confirmed |
| HealthRoutes.kt | SessionManager.listSessions | component check | VERIFIED | Lines 46, 85 |
| Routing.kt | registerHealthRoutes | health routes mounted | VERIFIED | Line 23 |
| Dockerfile | logback-prod.xml | -Dlogback.configurationFile JVM flag in ENTRYPOINT | VERIFIED | Line 15 |
| MetricsWiring.kt | VizSession callbacks | onSessionCreated hook assigns onEventEmitted/onEventDropped | VERIFIED | Lines 51-68 |
| MetricsWiring.kt | viz.sse.clients.active | sseClientsGauge increment/decrement in SSE handler | FAILED | sseClientsGauge is never mutated; gauge always reads 0 |
| ScenarioRunnerRoutes.kt | scenario.duration timer | Timer.start()/sample.stop() in runScenarioWithResponse | VERIFIED | confirmed per summary and code review |

---

## Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|-------------------|--------|
| ValidationPanel.tsx | data.results, data.timing | useValidation hook → apiClient.validateSession() → POST /api/validate/session/{id} | Yes — backend ValidationResponse with real rule results and timing | FLOWING |
| MetricsWiring.kt | viz.sse.clients.active | sseClientsGauge.toDouble() | No — AtomicInteger never mutated | DISCONNECTED |
| MetricsWiring.kt | events.emitted | session.onEventEmitted callback → Counter.increment() | Yes — called in VizSession.send() after event stored | FLOWING |
| HealthRoutes.kt | sessions, memory, uptime | SessionManager.listSessions(), Runtime.getRuntime(), System.currentTimeMillis() | Yes — real runtime values | FLOWING |

---

## Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| VizEventSerializersModule registers 66 subclasses | `grep -c 'subclass(' VizEventSerializersModule.kt` | 66 | PASS |
| Session fork deleted | `find backend/src/main/.../session -name '*.kt' \| wc -l` | 0 | PASS |
| appJson wired in ContentNegotiation | `grep 'json(appJson)' Serialization.kt` | 1 match | PASS |
| SSE encoder uses module-aware Json | `grep 'appJson.encodeToString' SessionRoutes.kt` | 2 matches at lines 201, 217 | PASS |
| FIX-03 cause-type branch present | `grep 'cause !is CancellationException' VizScope.kt` | lines 182, 332 (vizLaunch + vizAsync) | PASS |
| isCancelled=false for FAILED path | `grep 'isCancelled = false' VizScope.kt` | line 192 | PASS |
| FIX-04 targeted cancel active | `grep 'child1.cancel()' ScenarioRunner.kt` | line 120 (uncommented with vizDelay) | PASS |
| SessionManager.configure at startup | `grep 'SessionManager.configure' Application.kt` | line 20 | PASS |
| /metrics endpoint (renamed) | `grep "get.*metrics" Monitoring.kt` | line 20: `get("/metrics")` | PASS |
| sseClientsGauge ever mutated | `grep -rn 'sseClientsGauge\.' backend/src/main/kotlin/` | Only 1 match: `.toDouble()` in MetricsWiring.kt | FAIL — never incremented/decremented |
| Wrappers fork still exists | `ls backend/src/main/.../wrappers/` | 11 files with same FQCNs as core | FAIL — duplicate FQCNs in fat jar |
| VizScope coroutineContext has Job | `grep 'override val coroutineContext' VizScope.kt` | `context + CoroutineName(...)` — no Job | FAIL — cancel() is a no-op |

---

## Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| FIX-01 | Plan 01 | Polymorphic SerializersModule for all 66 VizEvent subclasses | SATISFIED | VizEventSerializersModule.kt (66 subclasses), appJson wired, SessionEventsIntegrationTest passes |
| FIX-02 | Plan 02 | Validation frontend reads real backend response shape | SATISFIED | ValidationPanel/ValidationResponse/TimingReportView adapted; 225 frontend tests green |
| FIX-03 | Plan 01 | VizScope classifies terminal state by cause type | SATISFIED | completion handler fix at lines 182/332; VizScopeCompletionHandlerTest asserts CoroutineFailed |
| FIX-04 | Plan 01 | Cancellation scenario targeted child cancel | SATISFIED | ScenarioRunner.kt child1.cancel() active; CancellationScenarioRegressionTest passes |
| FND-01 | Plan 03 | Session fork deleted; build resolves against core | SATISFIED | 0 kt files in session/; ForkDeletionTest passes |
| FND-02 | Plan 03 | Bounded EventStore wired via maxEvents config | SATISFIED | application.yaml maxEvents key; SessionManager.configure at startup |
| FND-03 | Plan 03 | Regression test for bounded store | SATISFIED | BoundedStoreRegressionTest asserts store.all().size <= maxEvents through real wiring |
| PROD-01 | Plan 04 | /api/health, /live, /ready with component checks + version + uptime | SATISFIED | HealthRoutes.kt confirmed; HealthRoutesTest (7 tests) |
| PROD-02 | Plan 05 | Dev/prod logging profiles; logstash dep present | SATISFIED | logstash-logback-encoder:8.1 in build.gradle.kts; Dockerfile selects logback-prod.xml via JVM flag |
| PROD-03 | Plan 04 | CORS reads from config | SATISFIED | CorsConfigTest: configured origin allowed, non-configured rejected |
| PROD-04 | Plan 04 | OpenAPI spec validated | SATISFIED | documentation.yaml: health paths added, ValidationResponse schema correct, redocly lint exits 0 |
| PROD-05 | Plan 05 | Full ADR-020 metric set at /metrics | BLOCKED | 6/7 metrics functional; viz.sse.clients.active is registered but permanently 0 — gauge counter never mutated |

**REQUIREMENTS.md inconsistency:** FIX-01, FIX-03, FIX-04 still show as `[ ]` (unchecked) in REQUIREMENTS.md despite implementation being verified. FIX-02 is correctly marked `[x]`. The traceability document was not updated after Plan 01 completed.

---

## Anti-Patterns Found

| File | Pattern | Severity | Impact |
|------|---------|----------|--------|
| `backend/src/main/kotlin/com/jh/proj/coroutineviz/MetricsWiring.kt:15` | `val sseClientsGauge = AtomicInteger(0)` declared but never mutated via incrementAndGet/decrementAndGet | BLOCKER | viz.sse.clients.active metric permanently reports 0; the ADR-020 "SSE clients" metric is misleading |
| `backend/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/*.kt` (11 files) | Duplicate FQCNs — same package and class names as coroutine-viz-core wrappers; InstrumentedFlow.kt has diverged by 55 lines | BLOCKER | Classloader ordering decides which wrappers implementation runs at runtime; divergent files mean behavior is non-deterministic |
| `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:59` | `coroutineContext = context + CoroutineName(...)` — no Job in context | BLOCKER | cancel()/cancelAndJoin() are silent no-ops; scenario coroutines are unparented (GlobalScope-equivalent); session close cannot stop running scenarios |
| `.planning/REQUIREMENTS.md` | FIX-01, FIX-03, FIX-04 still marked `[ ]` despite completion | WARNING | Traceability document inconsistency; no functional impact but audit trail is misleading |

---

## Human Verification Required

### 1. Run Validation end-to-end browser test

**Test:** Create a session, run a coroutine scenario, then click "Run Validation" in the browser UI
**Expected:** The validation panel renders with separate Failures and Passes sections, timing bars appear, and no error boundary page is shown
**Why human:** Automated tests (ValidationPanel.test.tsx) use mock data. The live path goes through useValidation → apiClient.validateSession() → POST /api/validate/session/{id} → backend ValidationResponse → component render. A TypeError in any hop could crash the panel but not be caught by the component test with mocked hooks.

### 2. SSE stream live rendering in browser

**Test:** Open the React app, create a session, run a scenario, observe the Events tab (or equivalent panel)
**Expected:** Events appear in the UI in real-time as the scenario executes
**Why human:** SessionEventsIntegrationTest proves GET /events returns 200 + polymorphic JSON. SseStreamTest proves SSE serialization. But the browser SSE EventSource path, event parsing in the frontend (use-event-stream.ts), and rendering in the visualization panels are not covered by any automated test in this phase.

---

## Gaps Summary

**2 BLOCKER gaps require closure before phase can be marked passed:**

**Gap 1 — viz.sse.clients.active metric dead (CR-02):** The `sseClientsGauge` AtomicInteger in MetricsWiring.kt is registered as the `viz.sse.clients.active` gauge but no code path ever calls `incrementAndGet()` or `decrementAndGet()` on it. The SSE handler in SessionRoutes.kt connects and disconnects clients without touching the gauge. This means the ADR-020 metric for SSE client count always reads 0 — it satisfies the metric _name_ requirement but not the _value_ requirement (`non-default values after a scenario run` per Plan 05 must_have). Fix: add `sseClientsGauge.incrementAndGet()` at SSE stream entry and `decrementAndGet()` in a finally block.

**Gap 2 — wrappers/ fork unresolved (CR-01):** The phase deleted the session/ fork correctly but left the wrappers/ package forked: `backend/src/main/.../wrappers/` contains 11 files with identical FQCNs to `coroutine-viz-core/wrappers/`. InstrumentedFlow.kt has already diverged by 55 lines between the two locations. In the fat jar, both class files exist; classloader ordering is non-deterministic. This is the same structural problem FND-01 was designed to fix — the phase narrowly interpreted FND-01 as "session/ only" and ForkDeletionTest guards session/ only, leaving the wrappers/ fork able to drift further undetected.

**Note on CR-03 (VizScope no Job):** CR-03 (cancel() is a no-op, scenarios are unparented) is a significant runtime defect but was not in the original ROADMAP success criteria or plan must_haves. It is flagged here as a WARNING rather than a BLOCKER for this verification (it pre-existed the phase and FIX-04 did not claim to fix it). It should be added to the next phase's must_haves.

**2 items requiring human verification** (visual/browser tests) remain before status can advance to `passed`.

---

_Verified: 2026-06-11T20:30:00Z_
_Verifier: Claude (gsd-verifier)_
