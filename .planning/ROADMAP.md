# Roadmap: vizcore — Visualizer for Coroutines

## Overview

vizcore is a brownfield product: an event-sourced Kotlin/Ktor backend, instrumentation wrappers, validation engine, and a rich React visualization frontend. The roadmap closes out remaining feature work and repairs verified structural gaps that block production, then layers on real-code observability (point vizcore at a developer's own running app), and finally takes the product from feature-complete to production-grade and developer-distributable — hardened at scale, published as a consumable SDK, wired for first-class observability, and delivered with an IntelliJ "Run with Visualizer" experience and a tested, polished frontend.

## Milestones

- ✅ **v1.0 MVP & Production Foundation** — Phases 1–3 (foundation/runtime fixes, replay/export/compare, persistence/auth/sharing)
- ✅ **v1.1 Real-Code Coroutine Observability** — Phases 6–8.5 (shipped 2026-06-27) — archived: `milestones/v1.1-ROADMAP.md`
- 📋 **v1.2 Production Hardening, SDK & IDE Delivery** — Phases 9–14 (Scale/SDK first → IDE/FE last)

## Phases

<details>
<summary>✅ v1.0 MVP & Production Foundation (Phases 1–3) — complete</summary>

- [x] Phase 1: Foundation & Production Readiness (15/15 plans) — completed 2026-06-12
- [x] Phase 2: User-Value Visualization — replay/export/compare (8/8 plans) — completed 2026-06-20
- [x] Phase 3: Persistence, Auth & Sharing (7/7 plans) — completed 2026-06-21

</details>

<details>
<summary>✅ v1.1 Real-Code Coroutine Observability (Phases 6–8.5) — SHIPPED 2026-06-27</summary>

Full detail archived in `milestones/v1.1-ROADMAP.md`. Requirements: RCO-01..07, FE-ALIGN, ONB-01 (9/9 satisfied).

- [x] Phase 6: Pluggable Instrumentation Source + DebugProbesSource (2/2 plans) — RCO-01/02/03
- [x] Phase 7: Real-App Transport (client lib + ingest) (4/4 plans) — RCO-04/05
- [x] Phase 8: Live Real-App View + Metrics (4/4 plans) — RCO-06/07
- [x] Phase 8.1: Align live view → IDE-docked metric tiles (2/2 plans)
- [x] Phase 8.2: Surface source attribution + jump-to-code (mounted) (2/2 plans)
- [x] Phase 8.3: Populate per-coroutine timeline source frames end-to-end (3/3 plans) — RCO-06 e2e
- [x] Phase 8.4: Eliminate duplicate-FQN model shadowing hazard (CR-01 hardening) (1/1 plan)
- [x] Phase 8.5: Align frontend to validated sketch winners (3/3 plans) — FE-ALIGN, ONB-01

**Deferred (tech-debt):** ONB-01 ConnectWizard auto-resolve is decoupled from the real `VizcoreClient` session (onboarding UX polish; pipeline unaffected). **Closed in v1.2 Phase 9.**

</details>

### 📋 v1.2 Production Hardening, SDK & IDE Delivery (Phases 9–14)

Sequenced Scale/SDK-first → IDE/FE-last, honoring the dependency-driven build order. Two governing invariants apply throughout: **(1)** all event emission flows through `VizSession.send()` — the store-write path is sacred (sampling/batching/OTel touch only the post-bus egress); **(2)** `coroutine-viz-core` + `coroutine-viz-client` stay pure-Kotlin JVM-17 (backend-only OTLP/CLI/harness may use JVM 21).

- [x] **Phase 9: Session Correlation (shared foundation) + ONB-01 close-out** — one in-memory correlation mechanism (`correlation` token + `GET /api/sessions/resolve`) serving both the web ConnectWizard and IntelliJ auto-connect; closes the carried-forward ONB-01 debt (completed 2026-06-28)
- [x] **Phase 10: Scale & Resilience (PERF wiring + load harness)** — bus-only sampling, SSE batching, anti-buffering headers, ingest rate-cap, dev-only load harness (completed 2026-06-28; verification 5/5, security 19/19)
- [x] **Phase 11: SDK Distribution + JVM-17 guard** — publish core + client to GitHub Packages (reconciled coordinates), CLI fat JAR, `coroutineVizCheck` Gradle task, CI bytecode guard *(all 3 plans built; remote publish + SC#1 remote resolution confirmed via `/gsd-verify-work 11`; secured 11/11 threats)* (completed 2026-06-28)
- [x] **Phase 12: Observability Integration (OpenTelemetry/OTLP)** — config-gated causality-based span exporter, zero overhead when off, verified in Jaeger/Zipkin (completed 2026-06-28)
- [x] **Phase 13: IntelliJ Plugin Delivery (rebuild-by-deletion)** — `RunWithVisualizerAction` javaagent launch, JCEF tool window, correlation auto-connect, plugin tests + Marketplace (completed 2026-06-29)
- [ ] **Phase 14: Frontend Testing & Quality** — actor/select/anti-pattern tests, FE coverage ≥80% gated, Playwright E2E, Storybook + visual regression (parallelizable with Phase 13)

## Phase Details

### Phase 9: Session Correlation (shared foundation) + ONB-01 close-out

**Goal**: A connecting app and the UI that wants to watch it reliably converge on the *same* live session, closing the v1.1 ONB-01 debt with one mechanism that the IntelliJ plugin will later reuse.
**Depends on**: Nothing new (builds on the shipped v1.1 client → ingest → SSE pipeline)
**Requirements**: CORR-01, CORR-02, ONB-01
**Success Criteria** (what must be TRUE):

  1. A developer running `VizcoreClient.start(appName, …, correlation=token)` causes the backend to record that token against the session it actually creates (no separately-minted id).
  2. A caller hitting `GET /api/sessions/resolve?correlation=token` gets back the real, live session id that `VizcoreClient` created, scoped to their tenant (cross-tenant tokens do not resolve).
  3. After connecting via the ConnectWizard, the user is auto-navigated to the **real** connected app's live view (the wizard polls `resolve`, not a self-minted id), and the displayed `DEP_SNIPPET` coordinates match the published artifact.

**Plans**: 3 plans in 2 waves

**Wave 1**

- [x] 09-01-PLAN.md — Backend: CorrelationRegistry + GET /api/sessions/resolve + record token on POST /api/sessions + evict on close (CORR-01, CORR-02)
- [x] 09-02-PLAN.md — Client: optional correlation param through VizcoreClient.start → createSession → POST /api/sessions (CORR-01)

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 09-03-PLAN.md — Frontend: ConnectWizard resolve-poll rewire + resolveCorrelation + locked DEP_SNIPPET coordinate (ONB-01, CORR-02)

**UI hint**: yes

### Phase 10: Scale & Resilience (PERF wiring + load harness)

**Goal**: The live pipeline sheds load gracefully under sustained high event throughput without ever corrupting the event-sourced store, dropping coroutine lifecycle events, or breaking the SSE stream through proxies.
**Depends on**: Nothing new (independent of Phase 9; validates the hot path before SDK/OTEL attach more subscribers)
**Requirements**: PERF-01, PERF-02, PERF-03, PERF-04, PERF-05
**Success Criteria** (what must be TRUE):

  1. Under heavy load, per-event-type sampling reduces emitted SSE volume while the EventStore stays 100% complete — a `rehydrateFromStore()` / `/events` refetch after a sampled live run reproduces every event, and coroutine create/complete/cancel are never dropped (sampling is bus-only; the store-write path stays sacred).
  2. The SSE egress emits batched frames under high throughput and the frontend renders a batched frame array correctly, with single-event back-compat preserved.
  3. The live SSE response carries `X-Accel-Buffering: no` (and appropriate `Cache-Control`) so an intermediary proxy does not buffer/break the stream; bulk JSON routes may be compressed but `/stream` is never gzip-buffered.
  4. Under an overload flood, bounded buffers + an ingest event-rate cap shed load without OOM and without silently losing the *wrong* (structural) events — drops are counted and observable.
  5. A dev-only, Gradle-gated load-test harness drives sustained synthetic event load and reports store/bus/sampling drops separately; it is never present in the production image.

**Plans**: 5 plans in 3 waves

**Wave 1** *(pure-core primitives, no file overlap → parallel)*

- [x] 10-01-PLAN.md — StructuralClassifier (shared spine) + adaptive EventSampler (PERF-01) ✅ 2026-06-28
- [x] 10-02-PLAN.md — EventBatcher (count-or-time) + StructuralAwareBuffer (two-lane shedding) (PERF-02, PERF-04) ✅ 2026-06-28

**Wave 2** *(egress wiring; route vs FE hook → no file overlap → parallel)*

- [x] 10-03-PLAN.md — Wire egress chain + hybrid frames + dropped marker + anti-buffering headers + 3 drop counters into SSE route (PERF-02, PERF-03, PERF-04) ✅ 2026-06-28
- [x] 10-04-PLAN.md — Frontend batch + dropped SSE listeners (PERF-02, PERF-04) ✅ 2026-06-28

**Wave 3**

- [x] 10-05-PLAN.md — Dev-only Gradle-gated load harness; separate store/bus/sampling counters; jar-exclusion guard (PERF-05) ✅ 2026-06-28

### Phase 11: SDK Distribution + JVM-17 guard

**Goal**: vizcore is consumable as a published library and command-line tool — a fresh consumer build can resolve the artifacts and run the validation engine against their own code — with the JVM-17 purity that the IntelliJ plugin depends on enforced by CI.
**Depends on**: Phase 10 (publish against the load-validated core; the packaged client lib is a prerequisite for Phase 13's agent jar)
**Requirements**: SDK-01, SDK-02, SDK-03, PERF-06
**Success Criteria** (what must be TRUE):

  1. Both `coroutine-viz-core` AND `coroutine-viz-client` publish to GitHub Packages with an MIT POM and reconciled Maven coordinates — the exact `DEP_SNIPPET` coordinate resolves from a clean, fresh consumer build.
  2. A user can run a Shadow (relocated) fat-JAR CLI to invoke vizcore from the command line and get validation findings (non-zero exit on violations).
  3. A consumer can add a `coroutineVizCheck` Gradle task that runs the existing validation engine against their build and reports coroutine anti-patterns (zero rule duplication).
  4. CI fails the build if `coroutine-viz-core` or `coroutine-viz-client` produce class files above JVM-17 bytecode (and core stays free of `io.ktor`).

**Plans**: 3 plans in 3 waves

**Wave 1** *(independent build config — no module sources change)*

- [x] 11-01-PLAN.md — Client publish block (MIT POM + sources jar, locked coordinate) + `checkBytecode` guard (JVM-17 floor + io.ktor-free core) + CI JDK 17/21 matrix (SDK-01, PERF-06) ✓ 3/3 tasks

**Wave 2** *(blocked on Wave 1)*

- [x] 11-02-PLAN.md — `:coroutine-viz-cli` Shadow fat-JAR CLI: `runCli` drives the 5 validators + AntiPatternDetector over a recorded export, non-zero exit on violations; Shadow legitimacy human-verify gate (SDK-02) — non-autonomous

**Wave 3** *(blocked on Waves 1+2)*

- [x] 11-03-PLAN.md — `coroutineVizCheck` consumer snippet docs + POM-assertion script + throwaway fresh-consumer proof + manual client publish wiring (SDK-01 + SDK-03 closed) ✓ Tasks 1-4/4. Task 4 HUMAN remote GitHub Packages publish + remote fresh-consumer resolution (SC#1) completed via `/gsd-verify-work 11` on 2026-06-28 (agent never published, D-03)

### Phase 12: Observability Integration (OpenTelemetry/OTLP)

**Goal**: Coroutine execution exports as a correct span tree to standard observability backends, with truly zero cost when the feature is disabled.
**Depends on**: Phase 10 (slot after PERF so the hot `send()` path is already load-validated before another bus subscriber attaches)
**Requirements**: OTEL-01, OTEL-02
**Success Criteria** (what must be TRUE):

  1. With OpenTelemetry disabled (default), the OTLP exporter and OTel SDK are never constructed and no EventBus listener/coroutine is registered — measured off-vs-on throughput delta is within noise (gated at construction, not just use).
  2. With OTel enabled, coroutine spans export over OTLP with parentage derived from event causality (`coroutineId`/`parentCoroutineId`/`jobId`), never ThreadLocal, and one span per coroutine *lifecycle* (not per event).
  3. The exported spans are verifiable end-to-end in both Jaeger and Zipkin (OTLP → Collector topology), and the exporter runs out-of-band off the `sendLock` path so a stalled exporter cannot block emission.

**Plans**: 4 plans

- [x] 12-01-PLAN.md — OTel BOM deps (:backend only) + OtelConfig data class (D-01/D-12)
- [x] 12-02-PLAN.md — OtelTracing SDK factory + DropCountingSpanProcessor + CoroutineSpanExporter (causality spans, OTEL-02)
- [x] 12-03-PLAN.md — configureObservability() construction gate + application.yaml block + zero-cost-when-off tests (OTEL-01)
- [x] 12-04-PLAN.md — Collector->Jaeger+Zipkin compose topology + ADR-030 + dual-UI SC#3 verification (D-13)

### Phase 13: IntelliJ Plugin Delivery (rebuild-by-deletion)

**Goal**: A developer can click "Run with Visualizer" in IntelliJ and watch their own app's live coroutines inside the IDE, auto-connected to the right session — the ergonomic delivery vehicle for the whole real-app pipeline.
**Depends on**: Phase 9 (correlation auto-connect), Phase 11 (packaged client lib for the agent jar)
**Requirements**: IDE-01, IDE-02, IDE-03, IDE-04
**Success Criteria** (what must be TRUE):

  1. "Run with Visualizer" launches the developer's app with the vizcore javaagent attached, pointed at the running backend — `RunWithVisualizerAction` is no longer a stub, and the legacy in-IDE Ktor receiver (`PluginEventReceiver`:8090) + Swing UI are deleted.
  2. A JCEF tool window embeds the vizcore React frontend inside IntelliJ (with a graceful fallback when JCEF is unavailable).
  3. The tool window auto-connects to the launched app's live session via the shared correlation mechanism (Phase 9), opening directly on the correct session.
  4. The plugin has automated (headless-safe) tests and is packaged for JetBrains Marketplace distribution.

**Plans**: 7 plans in 5 waves
Plans:
**Wave 1**

- [x] 13-01-PLAN.md — coroutine-viz-agent Shadow fat-jar module (Premain-Class → VizcoreClient.start) + arg-parse test
- [x] 13-02-PLAN.md — rebuild-by-deletion: delete legacy receiver/Swing UI + io.ktor deps + plugin.xml registrations
- [x] 13-03-PLAN.md — frontend ?correlation= deep-link entry on the index route (auto-resolve + navigate)

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 13-04-PLAN.md — backend-URL settings + health-check + loopback static-SPA/-api-proxy server (+ first headless tests)

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 13-05-PLAN.md — JCEF tool window (gated by isSupported) + system-browser fallback (same correlation URL)

**Wave 4** *(blocked on Wave 3 completion)*

- [x] 13-06-PLAN.md — launch path: AgentJarExtractor + RunConfigurationExtension (VM args) + RunWithVisualizerAction rebuild + correlation threading

**Wave 5** *(blocked on Wave 4 completion)*

- [x] 13-07-PLAN.md — packaging: agent-jar + frontend-build wires, coroutines-bundling human-verify spike, verifyPlugin/signPlugin, distributable zip

**UI hint**: yes

### Phase 14: Frontend Testing & Quality

**Goal**: The frontend is trustworthy to ship and refactor — its key panels and user flows are tested, coverage is gated, and components are cataloged with visual-regression protection.
**Depends on**: Nothing new (FE/CI only; runs in parallel with Phase 13)
**Requirements**: FETEST-01, FETEST-02, FETEST-03, FETEST-04
**Success Criteria** (what must be TRUE):

  1. The actor, select, and anti-pattern panels have unit tests, closing the known FE test gaps.
  2. Frontend test coverage is ≥80% and gated (ratcheted) in CI so it cannot regress.
  3. Playwright E2E covers core user flows — including a live-connect flow — against the live SSE app, non-flaky against the streaming view (web-first assertions on terminal states, dedicated ports).
  4. Storybook 10 catalogs key components with visual-regression checks (addon-vitest, animation-frozen), compatible with React 19 / Vite 6 / Vitest 4.

**Plans**: TBD
**UI hint**: yes

> **Parallelism note:** Phases 13 and 14 have no shared dependency (13 is backend/plugin + JCEF; 14 is FE/CI only) and may execute concurrently.

### Phase 15: Plugin Problems + data surfacing

**Goal**: The native plugin surfaces problems first and shows all the data it already has — a debugger-grade main view (sketch 004 winner D) and inspector (sketch 005 winner A), plus a Live/All view toggle so past coroutine rounds remain inspectable without re-introducing render lag.
**Depends on**: Phase 13 (native plugin redesign on branch `feat/intellij-plugin-native-redesign` — tree/graph view, SessionModel live filter, inspector, tiles)
**Requirements**: TBD (sketch-driven: `.planning/sketches/004-plugin-main-view` winner D, `005-plugin-inspector` winner A, `006-plugin-live-all-toggle` winner A — winners marked in MANIFEST/README)
**Success Criteria** (what must be TRUE):

  1. A persistent Problems strip with filter chips sits above a split tree|problems-detail panel (sketch 004 variant D = join of A+B); selecting a problem cross-highlights the coroutine in the tree.
  2. Tree rows carry the reco tiering: state · name · ~age · child count · leak/exception badge, with dispatcher/thread dimmed (row-data density pass, exception badge included).
  3. Inspector is reordered to stacked cards, most-diagnostic-first: timing → suspended-at → runs-on → identity → events (sketch 005 variant A), with a placeholder card for future multi-frame stacks.
  4. A Live/All view toggle exists per sketch 006 winner A: a segmented `[Live | All n]` control in the header next to Tree/Graph (LIVE ⇄ HISTORY pill swap, refresh indicator ~200ms ⇄ ~1.5s). Live = active + recently-completed (current 5s-window behavior); All = full session history grouped by round (collapsible groups w/ ✓/✗/⚠ counts, current round marked IN PROGRESS, older rounds collapsed into one summary node, history search) with no UI lag at 2,800+ nodes.
  5. Tiles (Active · Throughput · Leaks · Peak) keep showing full-session totals in both view modes.

**Plans**: 6 plans
Plans:
**Wave 1**

- [x] 15-01-PLAN.md — Problem taxonomy + SuspensionTracker + SessionModel pinning + tile retarget (D-06/07/09/10/11/12/22)

**Wave 2** *(blocked on Wave 1 completion)*

- [x] 15-02-PLAN.md — Row density + exception badge + soft-highlight primitive + inspector reorder (D-23/24, D-08 primitive)
- [x] 15-03-PLAN.md — ViewMode + RoundGrouping collapse economy + poll cadence/tracker wiring (D-13..17/19/21)

**Wave 3** *(blocked on Wave 2 completion)*

- [x] 15-04-PLAN.md — Problems strip + detail panel + contextual right pane + cross-highlight (D-01..08)
- [x] 15-05-PLAN.md — All-mode lazy RoundTreeModel + group-row rendering (D-13..17 materialization)

**Wave 4** *(blocked on Wave 3 completion)*

- [x] 15-06-PLAN.md — [Live | All n] toggle, search, graph disable, mode wiring (D-17..21, SC#5)

**UI hint**: yes

## Progress

| Phase | Milestone | Plans | Status | Completed |
|-------|-----------|-------|--------|-----------|
| 1. Foundation & Production Readiness | v1.0 | 15/15 | Complete | 2026-06-12 |
| 2. User-Value Visualization | v1.0 | 8/8 | Complete | 2026-06-20 |
| 3. Persistence, Auth & Sharing | v1.0 | 7/7 | Complete | 2026-06-21 |
| 6. Instrumentation Source + DebugProbesSource | v1.1 | 2/2 | Complete | 2026-06-24 |
| 7. Real-App Transport (client lib + ingest) | v1.1 | 4/4 | Complete | 2026-06-24 |
| 8. Live Real-App View + Metrics | v1.1 | 4/4 | Complete | 2026-06-25 |
| 8.1 Align live view → IDE-dock tiles | v1.1 | 2/2 | Complete | 2026-06-25 |
| 8.2 Surface source attribution (mounted) | v1.1 | 2/2 | Complete | 2026-06-27 |
| 8.3 Populate timeline source frames (e2e) | v1.1 | 3/3 | Complete | 2026-06-27 |
| 8.4 Eliminate duplicate-FQN shadowing (CR-01) | v1.1 | 1/1 | Complete | 2026-06-27 |
| 8.5 Align FE to sketch winners | v1.1 | 3/3 | Complete | 2026-06-27 |
| 9. Session Correlation + ONB-01 close-out | v1.2 | 3/3 | Complete   | 2026-06-28 |
| 10. Scale & Resilience (PERF + load harness) | v1.2 | 4/5 | In progress | - |
| 11. SDK Distribution + JVM-17 guard | v1.2 | 3/3 | Complete    | 2026-06-28 |
| 12. Observability Integration (OTEL/OTLP) | v1.2 | 4/4 | Complete    | 2026-06-28 |
| 13. IntelliJ Plugin Delivery | v1.2 | 7/7 | Complete   | 2026-06-29 |
| 14. Frontend Testing & Quality | v1.2 | 0/TBD | Not started | - |
| 15. Plugin Problems + data surfacing | v1.2 | 14/14 | Complete   | 2026-07-10 |
