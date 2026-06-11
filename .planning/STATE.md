---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: planning
stopped_at: Phase 1 context gathered
last_updated: "2026-06-11T11:34:40.196Z"
last_activity: 2026-06-11 — Brownfield ingest + codebase audit complete; PROJECT/REQUIREMENTS/ROADMAP/STATE initialized
progress:
  total_phases: 5
  completed_phases: 0
  total_plans: 0
  completed_plans: 0
  percent: 0
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-06-11)

**Core value:** A developer can SEE and UNDERSTAND coroutine/Flow/structured-concurrency execution that is otherwise invisible — reducing time-to-understand.
**Current focus:** Phase 1 — Foundation & Production Readiness

## Current Position

Phase: 1 of 5 (Foundation & Production Readiness)
Plan: 0 of TBD in current phase
Status: Ready to plan
Last activity: 2026-06-11 — Brownfield ingest + codebase audit complete; PROJECT/REQUIREMENTS/ROADMAP/STATE initialized

Progress: [░░░░░░░░░░] 0% (remaining-scope milestone; product itself ~92% built)

## Performance Metrics

**Velocity:**

- Total plans completed: 0
- Average duration: -
- Total execution time: -

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| - | - | - | - |

**Recent Trend:**

- Last 5 plans: -
- Trend: -

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table (26 Accepted ADRs locked; ADR-010 Proposed/advisory).
Recent decisions affecting current work:

- Treat the duplicate `session/` fork removal as the first work item — it is the root cause of the unbounded-store (gap 2) and unwired-perf (gap 3) findings.
- Persistence (ADR-015) and route-level auth (ADR-016) are designed-but-unimplemented; current deployment is in-memory/ephemeral (ADR-009).
- Business-model and KPI variants are unresolved — V2 is the working default; not blocking engineering.
- [Phase 1]: Folded runtime-audit fixes FIX-01..04 into Phase 1 (edited goal/requirements/success criteria); FIX wave executes first, before FND-01 de-fork. Evidence: VERIFICATION.md runtime addendum + SCENARIO-AUDIT.md (2026-06-11).

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

Last session: 2026-06-11T11:34:40.191Z
Stopped at: Phase 1 context gathered
Resume file: .planning/phases/01-foundation-production-readiness/01-CONTEXT.md
