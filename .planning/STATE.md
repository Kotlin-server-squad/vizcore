---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: executing
stopped_at: Completed 01-10-PLAN.md (TimingAnalyzer ns→ms conversion; magnitude-sanity test)
last_updated: "2026-06-12T09:32:21.049Z"
last_activity: 2026-06-12 -- Phase 01 execution started
progress:
  total_phases: 5
  completed_phases: 0
  total_plans: 12
  completed_plans: 10
  percent: 0
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-06-11)

**Core value:** A developer can SEE and UNDERSTAND coroutine/Flow/structured-concurrency execution that is otherwise invisible — reducing time-to-understand.
**Current focus:** Phase 01 — foundation-production-readiness

## Current Position

Phase: 01 (foundation-production-readiness) — EXECUTING
Plan: 2 of 12
Status: Ready to execute
Last activity: 2026-06-12 -- Phase 01 execution started

Progress: [████░░░░░░] 40% (remaining-scope milestone; product itself ~92% built)

## Performance Metrics

**Velocity:**

- Total plans completed: 2
- Average duration: ~17 min
- Total execution time: ~34 min

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 01 foundation | 2/5 | ~34 min | ~17 min |

**Recent Trend:**

- Last 5 plans: 01-01 (~20 min), 01-02 (~15 min)
- Trend: stable

*Updated after each plan completion*
| Phase 01 P03 | 6 | 3 tasks | 15 files |
| Phase 01 P04 | ~10min | 3 tasks | 4 files |
| Phase 01 P05 | 15min | 2 tasks | 8 files |
| Phase 01 P10 | 17 | 2 tasks | 2 files |

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table (26 Accepted ADRs locked; ADR-010 Proposed/advisory).
Recent decisions affecting current work:

- Treat the duplicate `session/` fork removal as the first work item — it is the root cause of the unbounded-store (gap 2) and unwired-perf (gap 3) findings.
- Persistence (ADR-015) and route-level auth (ADR-016) are designed-but-unimplemented; current deployment is in-memory/ephemeral (ADR-009).
- Business-model and KPI variants are unresolved — V2 is the working default; not blocking engineering.
- [Phase 1]: Folded runtime-audit fixes FIX-01..04 into Phase 1 (edited goal/requirements/success criteria); FIX wave executes first, before FND-01 de-fork. Evidence: VERIFICATION.md runtime addendum + SCENARIO-AUDIT.md (2026-06-11).
- [Phase 1, Plan 02]: ValidationResult renamed to ValidationResponse (no alias) — all consumers must use new name; ValidationWarningCard left in file with local type (unused, backend has no Warning variant); api-client.ts updated as part of type rename (Rule 3).
- [Phase ?]: D-01 big-bang delete: 10 fork files removed in one commit; no import changes needed (same package name)
- [Phase ?]: APP_VERSION constant '0.0.1' hardcoded from build.gradle.kts version string (simplest non-empty version for Phase 1)
- [Phase ?]: OpenAPI security: [] global declaration satisfies security-defined lint rule without real auth; ADR-016 auth planned for Phase 3
- [Phase ?]: PROD-02: logstash dep added; prod logback profile wired via JVM flag in Dockerfile ENTRYPOINT
- [Phase ?]: PROD-05: /metrics endpoint; all 7 ADR-020 metrics wired; coroutine-viz-core kept Micrometer-free via callbacks
- [Phase 1, Plan 09]: VizScope terminal ordering fix: emit JobStateChanged BEFORE coroutineFailed/coroutineCancelled so terminal event has highest seq; vizAsync left unchanged (no JobStateChanged emitted there)
- [Phase ?]: TimingAnalyzer ns->ms: divide by NANOS_PER_MILLI=1_000_000L; frontend BackendTimingReport unchanged (already ms contract)

### Pending Todos

None captured yet. (Existing repo todo: retire standalone-repo — see git log 2491764.)

### Blockers/Concerns

Verified gaps from the 2026-06-11 codebase audit (Phase 1 addresses 1–3; auth in Phase 3; plugin in Phase 5):

1. **Duplicate `com.jh.proj.coroutineviz.session.*` fork** in `backend/src/main/` shadows the stronger `coroutine-viz-core` versions at runtime — ROOT CAUSE of 2 and 3. Fix: delete the fork, resolve against core. (Phase 1 / FND-01)
2. **Persistence (ADR-015) unimplemented** — runtime EventStore is an unbounded `CopyOnWriteArrayList`; no DB. Sessions lost on restart. (Bounded store: Phase 1 / FND-02; DB: Phase 3 / PERS-*)
3. **Perf-scaling (ADR-020) not wired** — bounded EventStore/EventSampler/RetentionPolicy exist in core but are unused by the backend; only 2 of 7 Micrometer gauges present. (Phase 1 / PROD-05, FND-02; sampling/batching: Phase 4)
4. **Auth (ADR-016) effectively off** — `Auth.kt` + `authenticatedApi()` exist but `Routing.kt` registers all routes without auth; every endpoint open. Write enforcement tests BEFORE wrapping routes. (Phase 3 / AUTH-*)
5. **IntelliJ plugin (ADR-010)** mostly built but `RunWithVisualizerAction.actionPerformed` is a TODO stub (javaagent launch missing); zero plugin tests. (Phase 5 / IDE-*)

## Deferred Items

| Category | Item | Status | Deferred At |
|----------|------|--------|-------------|
| Business | Pricing/license model (competing PRD variants) | Pending resolution (V2 default) | 2026-06-11 |
| Business | Success-metric/KPI set (competing PRD variants) | Pending resolution (V2 default) | 2026-06-11 |
| Growth | Marketing site, theme/layout customization, onboarding tutorial | v2 | 2026-06-11 |
| SDK | Maven Central publication | v2 (after GitHub Packages) | 2026-06-11 |

## Session Continuity

Last session: 2026-06-12T09:32:21.045Z
Stopped at: Completed 01-10-PLAN.md (TimingAnalyzer ns→ms conversion; magnitude-sanity test)
Resume file: None
