---
status: complete
phase: 01-foundation-production-readiness
source: [01-VERIFICATION.md]
started: 2026-06-12T06:57:01Z
updated: 2026-06-12T08:25:00Z
---

## Current Test

[testing complete]

## Tests

### 1. Run Validation end-to-end browser test
expected: With the full stack running, triggering Run Validation in the browser renders the validation results panel (result cards + timing report) from the live POST /api/validate response shape {sessionId, results[], timing}. Mocked in automated tests only — this proves the live path.
result: issue
reported: "Feature works end-to-end (POST /api/validate/session/{id} 200, Failures(3)/Passes(13) cards + timing report render, no crash) — but the Timing Report shows ~109,172s total duration for a ~5s scenario. Unit bug: backend TimingAnalyzer returns nanoseconds, frontend formatMs() treats the value as milliseconds."
severity: major

### 2. SSE stream live rendering in browser
expected: With the full stack running, opening a session in the browser establishes the EventSource connection (use-event-stream.ts) and live events render in the visualization panels (tree/graph/timeline). SSE serialization and delivery are proven by automated tests; this proves browser rendering.
result: pass

## Summary

total: 2
passed: 1
issues: 1
pending: 0
skipped: 0
blocked: 0

## Gaps

- truth: "Validation Timing Report shows durations in correct units"
  status: failed
  reason: "User-observed: Total Duration 109172.17s (~30h) for a ~5s scenario; per-coroutine bars ~105000s"
  severity: major
  test: 1
  artifacts:
    - "backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/checksystem/TimingAnalyzer.kt (totalDuration documented as NANOSECONDS)"
    - "frontend/src/components/validation/TimingReportView.tsx (formatMs() treats value as MILLISECONDS)"
  missing:
    - "Unit conversion ns→ms at the FE boundary (or BE returns ms and documents it); test asserting a plausible duration magnitude"

- truth: "Session validation passes on a clean scenario run (no NoEventsAfterTerminal failures from the app's own instrumentation)"
  status: failed
  reason: "POST /api/validate on the Exception Handling session reports 3× NoEventsAfterTerminal: every coroutine emits JobStateChanged AFTER its terminal event (e.g. CoroutineFailed@seq=22 → JobStateChanged@seq=23). Related: sibling CoroutineCancelled (seq 17) is recorded before the causing child's CoroutineFailed (seq 19) — effect before cause in the event log."
  severity: major
  test: 1
  artifacts:
    - "backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt (invokeOnCompletion emits terminal event then jobStateChanged)"
    - "backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/checksystem/ (NoEventsAfterTerminal rule)"
  missing:
    - "Either emit JobStateChanged before the terminal lifecycle event, or exempt JobStateChanged from the NoEventsAfterTerminal rule — decide and align instrumentation with validator"

- truth: "Jobs tab shows job states for a session with running/completed coroutines"
  status: failed
  reason: "Jobs (0) for a session with 3-4 coroutines and JobStateChanged events present in the store"
  severity: major
  test: 2
  artifacts:
    - "frontend/src/components/SessionDetails.tsx:53 (filters event.kind === 'JobStateChanged')"
    - "REST /api/sessions/{id}/events payload carries discriminator field 'type', not 'kind'"
  missing:
    - "Consistent event discriminator between REST payloads and FE types (kind vs type); Jobs filter matching the actual field"

- truth: "Session page does not poll the REST API at high frequency while SSE is connected"
  status: failed
  reason: "~88 GET /api/sessions/{id} + /threads requests observed in ~3s while the SSE stream was active (Auto-updating poller)"
  severity: minor
  test: 2
  artifacts:
    - "frontend/src/hooks/use-thread-activity.ts:22 (refetchInterval: 2000)"
    - "frontend/src/hooks/ (suspected per-SSE-event query invalidation without debounce)"
  missing:
    - "Debounced/throttled invalidation; rely on SSE for live data instead of polling the snapshot per event"

- truth: "Scenario Controls return to runnable state after the scenario completes"
  status: failed
  reason: "Button stays disabled at 'Scenario Running' after all coroutines reach terminal state; persists across reload ('Scenario is running or has completed')"
  severity: minor
  test: 2
  artifacts:
    - "frontend/src/components/SessionDetails.tsx (scenario running state derivation)"
  missing:
    - "Completion detection driving the button back to a re-runnable or explicit 'Completed' state"

- truth: "Connection badge reflects actual SSE state"
  status: failed
  reason: "'Connecting…' badge stays lit while events are actively streaming on first session load; shows 'Connected' only after reload"
  severity: cosmetic
  test: 2
  artifacts:
    - "frontend/src/hooks/use-event-stream.ts (onopen state handling)"
  missing:
    - "Badge state transition on EventSource onopen for the initial connection"

- truth: "Structured Concurrency info panel matches implemented semantics"
  status: failed
  reason: "Panel says a failing child's parent 'gets CANCELLED', but backend emits CoroutineFailed for the parent and the tree renders FAILED (which matches Kotlin completes-exceptionally semantics)"
  severity: cosmetic
  test: 2
  artifacts:
    - "frontend/src/components/StructuredConcurrencyInfo.tsx (Failure Propagation copy)"
  missing:
    - "Panel copy aligned with actual parent-FAILED rendering"

- truth: "Gallery Run flow lands on a runnable session page"
  status: resolved
  reason: "Was: navigate() omitted scenarioId so the session page rendered no Scenario Controls (dead end) + silent catch{} swallowed errors + Gallery missing from nav. Fixed inline during this UAT (gallery/index.tsx passes scenarioId and logs errors; Layout.tsx adds Gallery nav link). Verified in browser: gallery → Run → session page with controls → scenario runs and renders."
  severity: major
  test: 2
  artifacts:
    - "frontend/src/routes/gallery/index.tsx (fixed)"
    - "frontend/src/components/Layout.tsx (fixed)"
  missing: []
