---
phase: 8
slug: live-real-app-view-metrics
status: approved
nyquist_compliant: true
wave_0_complete: false
created: 2026-06-24
---

# Phase 8 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Sourced from 08-RESEARCH.md "Validation Architecture". Nyquist enabled.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework (backend)** | JUnit 5 + Ktor Test Host; `runTest` virtual time; `@Tag("integration")` for timing-bearing |
| **Framework (frontend)** | Vitest + Testing Library |
| **Config file (backend)** | Gradle (`backend/build.gradle.kts`, `coroutine-viz-core/build.gradle.kts`); `-PincludeIntegration` opt-in |
| **Config file (frontend)** | `frontend/vitest.config.*` (existing) |
| **Quick run (backend)** | `cd backend && ./gradlew :coroutine-viz-core:test` (excludes `@Tag("integration")`) |
| **Quick run (FE)** | `cd frontend && pnpm test -- --run` |
| **Full suite (backend)** | `cd backend && ./gradlew test -PincludeIntegration` |
| **Full suite (FE)** | `cd frontend && pnpm test` |
| **Live harness** | `cd examples/spring-vizcore-demo && ./gradlew bootRun` (against running backend) |
| **Algorithm harness** | `cd examples/spring-vizcore-demo && ./gradlew spike` |
| **Estimated runtime** | quick: < 30s each; full backend incl. integration: ~2–4 min |

> JDK 21 required for Gradle gates (MEMORY gotcha).

---

## Sampling Rate

- **After every task commit:** backend tasks → `./gradlew :coroutine-viz-core:test` (or `:backend:test` for route tasks); FE tasks → `pnpm test -- --run <name>`. Deterministic, < 30s.
- **After every plan wave:** full deterministic backend suite + FE suite; plus `./gradlew spike` for any reconstruction change (Wave 1 / 08-01).
- **Before `/gsd-verify-work`:** `./gradlew test -PincludeIntegration` green (incl. `DebugProbesDemoIT`) + FE suite green + manual live-harness check (demo streams a legible named tree + populated metrics).
- **Max feedback latency:** < 30 seconds (per-task quick run).

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 08-01-01 | 01 | 1 | RCO-06 | T-08-02 / T-08-03 | nearest-observed-ancestor map; cycle-guarded walk; consistent keys | unit | `./gradlew :coroutine-viz-core:test --tests "*HierarchyReconstructor*"` | ❌ W0 | ⬜ pending |
| 08-01-02 | 01 | 1 | RCO-06 | T-08-02 | parent/child keys from same jobKeys cache (no second scheme) | unit | `./gradlew :coroutine-viz-core:test --tests "*CoroutineInfoAdapter*"` | ✅ extend | ⬜ pending |
| 08-01-03 | 01 | 1 | RCO-06 | T-08-01 | non-null dp- parentCoroutineId + dispatcher-derived scopeId | unit | `./gradlew :coroutine-viz-core:test --tests "*DebugProbesEventSynthesizer*" --tests "*ProjectionService*"` | ✅ extend | ⬜ pending |
| 08-02-01 | 02 | 2 | RCO-07 | T-08-06 | active/peak/throughput/dispatcher-util/leak; replay-consistent; bounded active tracking | unit | `./gradlew :coroutine-viz-core:test --tests "*MetricsProjection*"` | ❌ W0 | ⬜ pending |
| 08-02-02 | 02 | 2 | RCO-07 | T-08-04 / T-08-05 | tenant-scoped 404 cross-tenant; leakThresholdMs validated+clamped | integration (Ktor test host) | `./gradlew :backend:test --tests "*MetricsRoute*"` | ❌ W0 | ⬜ pending |
| 08-03-01 | 03 | 3 | RCO-07 | T-08-08 | poll-while-live; disabled when !sessionId / read-only | unit | `pnpm test -- --run use-session-metrics` | ❌ W0 | ⬜ pending |
| 08-03-02 | 03 | 3 | RCO-07 | T-08-10 | literal Tailwind classes; leaks=warning not danger; StateIndicator reuse | component | `pnpm test -- --run SessionMetrics LeakList` | ❌ W0 | ⬜ pending |
| 08-03-03 | 03 | 3 | RCO-06 | T-08-08 | active-only default + "Show completed (N)" + "N more"; metrics mount | component | `pnpm test -- --run SessionDetails` | ✅ extend | ⬜ pending |
| 08-03-04 | 03 | 3 | RCO-06 / RCO-07 | — | live demo: legible named tree + populated metrics + leak surfacing | manual (live harness) | `./gradlew bootRun` (demo) + `pnpm dev` | n/a | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `HierarchyReconstructorTest.kt` — pure unit for invert + nearest-observed-ancestor (RCO-06); model on the spike (`withParent >= 3`, `named >= 4`).
- [ ] `MetricsProjectionTest.kt` — active/peak/throughput/dispatcher-util/leak + a `rebuildFrom`/replay-consistency case (RCO-07).
- [ ] `MetricsRouteTest.kt` (backend Ktor test host) — 200 shape + cross-tenant 404 + leakThresholdMs clamp (RCO-07 + Pitfall 3).
- [ ] FE: `use-session-metrics.test.ts` (hook), `SessionMetrics.test.tsx` + `LeakList.test.tsx` (components), `SessionDetails.test.tsx` active-only additions.
- [ ] Extend `DebugProbesEventSynthesizerTest.kt`, `ProjectionServiceTest`, `DebugProbesDemoIT.kt` for parent/scope propagation (existing files — assert the new edges).

*Existing infra covers: pure-layer fakeability (`RawInfo` / fake `Job` trees), `runTest` virtual-time tests, Ktor test host route tests, Vitest component tests. No framework install needed.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Live view renders a legible named hierarchical tree (not a flat wall) for a streaming real app | RCO-06 | Requires a real running JVM app streaming over the Phase-7 WS transport; the reconstruction's "legibility" is a visual judgment | 08-03 Task 4 checkpoint: backend up, `./gradlew bootRun` demo, open session + Enable Live Stream, confirm tree + names + grouping + active-only controls |
| Metrics panel + leak list populate with non-zero values from real traffic | RCO-07 | Requires real coroutine traffic to produce active/peak/throughput/leaks | 08-03 Task 4 checkpoint: confirm Session metrics panel non-zero + leak surfacing (warning/amber, ~30s threshold) |

> Note: `DebugProbesDemoIT.kt` (integration, `-PincludeIntegration`) provides automated coverage of the reconstruction against a real DebugProbes dump; the manual checks above are the end-to-end legibility/UX confirmation on top of it.

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or are manual-only checkpoints with documented instructions
- [x] Sampling continuity: no 3 consecutive tasks without automated verify (only Task 08-03-04 is manual, terminal checkpoint)
- [x] Wave 0 covers all MISSING references (HierarchyReconstructor, MetricsProjection, MetricsRoute, FE hook/components)
- [x] No watch-mode flags (`--run` used for Vitest)
- [x] Feedback latency < 30s (per-task quick run)
- [x] `nyquist_compliant: true` set in frontmatter

**Approval:** approved 2026-06-24
