# Roadmap: vizcore — Visualizer for Coroutines

## Overview

vizcore is a brownfield product: an event-sourced Kotlin/Ktor backend, instrumentation wrappers, validation engine, and a rich React visualization frontend. The roadmap closes out remaining feature work and repairs verified structural gaps that block production, then layers on real-code observability (point vizcore at a developer's own running app) and the remaining scale/SDK/plugin/quality work.

## Milestones

- ✅ **v1.0 MVP & Production Foundation** — Phases 1–3 (foundation/runtime fixes, replay/export/compare, persistence/auth/sharing)
- ✅ **v1.1 Real-Code Coroutine Observability** — Phases 6–8.5 (shipped 2026-06-27) — archived: `milestones/v1.1-ROADMAP.md`
- 📋 **Future (post-v1.1)** — Phase 4 (Scale/Observability/SDK), Phase 5 (IntelliJ plugin + FE quality)

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

**Deferred (tech-debt):** ONB-01 ConnectWizard auto-resolve is decoupled from the real `VizcoreClient` session (onboarding UX polish; pipeline unaffected). Candidate for a small follow-up slice (08.6 or v1.2).

</details>

### 📋 Future (post-v1.1)

- [ ] **Phase 4: Scale, Observability & SDK** — sampling/batching/SSE-compression + load harness, OpenTelemetry/OTLP export, published SDK + CI/CD CLI (PERF-01..04, OTEL-01/02, SDK-01/02)
- [ ] **Phase 5: IntelliJ Plugin & Frontend Quality** — `RunWithVisualizerAction` javaagent launch + JCEF tool window, plugin tests, FE test coverage ≥80%, Playwright E2E, Storybook/visual regression (IDE-01..03, FETEST-01..03)

> The IntelliJ "Run with Visualizer" parts of Phase 5 (IDE-01..03) are the ergonomic delivery vehicle for v1.1 real-code observability and may pull forward.

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
| 4. Scale, Observability & SDK | future | 0/TBD | Not started | - |
| 5. IntelliJ Plugin & Frontend Quality | future | 0/TBD | Not started | - |
