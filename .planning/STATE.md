---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: ready
stopped_at: Phase 02 complete (verified + UAT 5/5 passed) — Phase 03 ready to plan
last_updated: "2026-06-20T18:49:04.883Z"
last_activity: 2026-06-20
progress:
  total_phases: 5
  completed_phases: 2
  total_plans: 23
  completed_plans: 23
  percent: 40
---

# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-06-11)

**Core value:** A developer can SEE and UNDERSTAND coroutine/Flow/structured-concurrency execution that is otherwise invisible — reducing time-to-understand.
**Current focus:** Phase 03 — persistence, auth & sharing

## Current Position

Phase: 3
Plan: Not started
Status: Phase 02 complete (verified + UAT 5/5 passed) — Phase 03 ready to discuss/plan
Last activity: 2026-06-20

Progress: [████░░░░░░] 40% (remaining-scope milestone; product itself ~92% built)

## Performance Metrics

**Velocity:**

- Total plans completed: 33
- Average duration: ~17 min
- Total execution time: ~34 min

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 01 foundation | 2/5 | ~34 min | ~17 min |
| 01 | 15 | - | - |
| 02 | 8 | - | - |

**Recent Trend:**

- Last 5 plans: 01-01 (~20 min), 01-02 (~15 min)
- Trend: stable

*Updated after each plan completion*
| Phase 01 P03 | 6 | 3 tasks | 15 files |
| Phase 01 P04 | ~10min | 3 tasks | 4 files |
| Phase 01 P05 | 15min | 2 tasks | 8 files |
| Phase 01 P10 | 17 | 2 tasks | 2 files |
| Phase 01 P11 | 4min | 2 tasks | 1 files |
| Phase 01 P12 | 22 | 2 tasks | 7 files |
| Phase 01 P13 | ~7 min | 2 tasks | 5 files |
| Phase 01 P14 | ~10 min | 3 tasks | 10 files |
| Phase 01 P15 | ~14min | 3 tasks | 5 files |
| Phase 02 P01 | ~18min | 2 tasks | 8 files |
| Phase 02 P02 | ~12 min | 2 tasks | 4 files |
| Phase 02 P03 | ~17 min | 2 tasks | 6 files |
| Phase 02 P04 | ~25 min | 3 tasks | 5 files |
| Phase 02 P05 | ~12 min | 2 tasks | 10 files |
| Phase 02 P06 | ~14 min | 2 tasks | 7 files |
| Phase 02 P07 | ~16 min | 3 tasks | 7 files |
| Phase 02 P08 | ~20 min | 2 tasks | 7 files |

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
- [Phase ?]: Normalization already correct; added discriminator regression test as the missing proof artifact
- [Phase ?]: [Phase 1, Plan 13]: Max-wait caps added to both 01-12 debounces (SSE invalidation 1000ms, session refetch 1500ms); every SSE flush invalidates both sessions and thread-activity keys with a 5s live fallback poll (CR-01/CR-02 closed)
- [Phase ?]: [Phase 1, Plan 13]: SessionDetails max-wait window ref reset moved to a streamEnabled-keyed teardown effect — per-event cleanup would restart the max-wait clock (plan instruction corrected via Rule 1)
- [Phase 1, Plan 14]: Frontend aligned to the real GET /threads wire shape (Map<threadId, ThreadEvent[]>) via pure buildThreadLanes adapter; fictional {threads, dispatcherInfo} mock generators deleted so MSW serves the backend's exact shape (CR-02 regression trap closed)
- [Phase 1, Plan 14]: useThreadLanesByDispatcher groups null-dispatcher lanes via (dispatcherId ?? 'Unknown') — strict id equality would silently drop them (Rule 1 fix to plan instruction)
- [Phase 1, Plan 14]: SessionDetails.test.tsx mocks apiClient (not the hook) and renders the real ThreadTimeline so the UAT-gap-1 integration test exercises the genuine wire-shape pipeline
- [Phase 1]: [Phase 1, Plan 15]: SSE first-connect fix is two-sided: backend ': connected' comment frame flushes headers on 0-event sessions; frontend retries fatal (readyState=2) EventSource errors with bounded backoff (1s,2s,4s,8s,8s; 5 retries, budget reset on open) plus maxSeqRef replay dedup so WR-01 full-history replay never duplicates the live view
- [Phase 1]: [Phase 1, Plan 15]: Ktor testApplication live-stream reads must run under withContext(Dispatchers.Default) — the virtual-time dispatcher fires withTimeout before the wall-clock server writes bytes (MetricsWiringTest convention)
- [Phase 2, Plan 01]: Compare endpoint renamed to GET /api/sessions/compare?a=&b= (D-09); SessionComparison gained distinctThreadsDiff (CMPR-01 thread metric) derived from distinct ThreadAssigned.threadId via store.all(); strict 404 on unknown id retained (D-12, no getOrCreate)
- [Phase 2, Plan 01]: Static path /api/sessions/compare coexists safely with /api/sessions/{id} — Ktor prioritizes constant segments over parameterized ones (confirmed by passing integration test)
- [Phase 2, Plan 01]: shared/api-types/openapi.json synced surgically (compare path + SessionComparison/CoroutineComparison/ErrorResponse) not full YAML->JSON regen — JSON copy is stale (22 vs 55 paths); full regen remains a pending todo
- [Phase ?]: [Phase 2, Plan 02]: events/ + checksystem/ forks deleted (54 .kt); core is keeper (flow events keep @SerialName the forks lacked). ns->ms fix (NANOS_PER_MILLI) landed in core TimingAnalyzer with proving test; core's own TimingAnalyzerTest rescaled to ms.
- [Phase ?]: [Phase 2, Plan 02]: D-12 strict-404 audit complete — all 22 getOrCreateSession callers are scenario/pattern/flow create POSTs; every read/SSE/compare route uses getSession->404 (WR-07 closed backend-wide).
- [Phase ?]: [Phase 2, Plan 02]: parallel Gradle test-worker flakiness is pre-existing/environmental — full suite green with --max-workers=1; deferred, not a de-fork regression.
- [Phase ?]: [Phase 2, Plan 03]: projectCoroutines emits nodes in creation order (matches backend RuntimeSnapshot LinkedHashMap) so deep-equal vs server snapshot holds; terminal state by event kind only (FIX-03). useReplay clamp aligned to ADR-017 (base 50-2000ms then /speed); two 10ms timing tests realigned to 50ms floor (Rule 1).
- [Phase ?]: [Phase 2, Plan 04]: @heroui/react pinned to ~2.7.11 (tilde, not ^2.7 caret) to keep the React Aria bump isolated to the 2.7 line — lockfile had drifted to 2.8.5 and an open caret would bypass the full-suite + human-smoke gate; ToastProvider mounted above the router (issue #5086 ordering).
- [Phase ?]: [Phase 2, Plan 05]: Replaced pre-existing foreignObject exportToSvg(HTMLElement) with D-21 findSvgRoot + exportToSvg(SVGSVGElement) whitelist (10 props, Pattern 4/T-02-08); no production importer existed (Rule 1).
- [Phase ?]: [Phase 2, Plan 05]: ExportMenu Record item enabled-but-no-op (onRecord placeholder) when supported, disabled+D-25 tooltip when MediaRecorder/codec unsupported; 02-08 wires recording.
- [Phase 2, Plan 07]: RPLY-01/02/03 complete — replay mounted in SessionDetails as a frozen-snapshot time-travel view (useReplay over an entry snapshot; panelEvents/panelCoroutines switch to projected visibleEvents). SSE invalidation gated via a replayActive ref so the EventSource stays open and events buffer for the clickable "N new events" badge, with exactly one flush on exit (D-04); both useEventStream and the local debounced refetch gated. Phase-1 dedup/backoff/max-wait untouched. Recording-state slot is props-driven for 02-08. Closeout (SUMMARY+tracking) done by a continuation agent after an executor API socket drop — no work lost, no new feat commits.
- [Phase 2, Plan 08]: EXPT-02 WebM tier complete — pure record-replay.ts pipeline (pickMimeType vp9→vp8→webm cascade/D-25, estimateDurationMs 50–2000ms clamp÷speed/D-26, createReplayRecorder 2x mirror-canvas + captureStream(0)+requestFrame/D-27) + useRecordReplay scripted glue (enter replay→seek0→record→auto-stop at last event→download .webm, >120s confirm modal/D-26, visibilitychange→hidden abort/D-24, controller Stop discard/D-23) + RecordConfirmModal + SessionDetails wiring (ExportMenu onRecord→startRecording, ReplayController recording props). record-replay.ts stop() now resolves the .webm filename so the success toast shows the real name. Real-codec validation deferred to Phase 5 Playwright per VALIDATION.md. Continuation closeout: Task 1 (cd8fc20) committed by prior executor before an API socket drop; this agent finished Task 2 (236c255) + wrote SUMMARY/tracking — no Task 1 re-commit.
- [Phase 2, Plan 06]: CMPR-02 complete — /compare?a=&b= route (validateSearch normalizes/drops blank ids, T-02-10) drives controlled ComparisonView selection (shareable URL, D-10); SyncedTreePair renders its own clickable tree nodes (not CoroutineTree) for selection rings + delta badges, counterpart match by label then coroutineId (D-19/D-20); session-not-found EmptyState on 404 (D-12). Route component uses useSearch({strict:false}) so it mounts under a standalone test router (file-route re-parent duplicates __root__).

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

Last session: 2026-06-14T18:42:28.000Z
Stopped at: Completed 02-08-PLAN.md (Phase 02 plans complete — 8/8)
Resume file: None
