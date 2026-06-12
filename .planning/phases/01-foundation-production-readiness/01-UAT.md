---
status: complete
phase: 01-foundation-production-readiness
source: [01-VERIFICATION.md]
started: 2026-06-12T06:57:01Z
updated: 2026-06-12T13:25:00Z
---

## Current Test

[testing complete — round 3 executed 2026-06-12 via browser automation after gap plans 01-14/01-15; both round-2 gaps verified closed in the live browser]

## Round 3 Tests (post 01-14/01-15)

### R3-1. Threads tab renders live thread-lane data (gap 1 re-test)
expected: Gallery → Run, Threads tab shows lanes + dispatcher cards, no permanent empty state.
result: pass
reported: "Thread Activity panel rendered 6 worker lanes (DefaultDispatcher-worker-1/3/5/6/8/10) with ASSIGNED chips, coroutine ids, and timestamps; Dispatchers.Default card showed Thread Pool Size 6 with thread IDs 56/65/78/79/81/83."

### R3-2. SSE connects on first load of a fresh 0-event session (gap 2 re-test)
expected: Badge goes live without reload; events update during run; button recovers on completion; curl prints ': connected' with HTTP 200.
result: pass
reported: "Fresh gallery session connected immediately (green 'Connected' badge, no reload), events live-updated 23→40 during the run, button transitioned 'Scenario Running'→'Scenario Completed'. curl on a fresh 0-event session printed ': connected' with HTTP 200 (previously HTTP 000/no bytes)."

## Tests

### 1. Threads tab freshness during live session
expected: Run a scenario with live streaming; thread lanes update during execution (no freeze on first snapshot). Closed by 01-13 (CR-01) — every SSE debounce flush now invalidates ['thread-activity', sessionId] plus a 5s fallback poll while live; this confirms it in a real browser.
result: issue
reported: "The 01-13 invalidation itself WORKS — GET /threads observed firing during a live SSE stream. But the Threads tab renders 'No thread activity data available yet' permanently: backend /threads returns Map<threadId, ThreadEvent[]> while the FE types expect {threads, dispatcherInfo} (REVIEW.md critical CR-02), so data.threads is always undefined. Separately, the primary gallery→Run flow never establishes SSE at all on first load (see gap 2), freezing the entire live view (events frozen at last REST fetch, badge 'Connecting…', button stuck 'Scenario Running') until reload."
severity: major

### 2. Bounded snapshot cadence under sustained stream
expected: With Network DevTools open during a dense event stream, GET /api/sessions/{id} fires at least once per ~1–1.5s (max-wait caps: 1000ms invalidation, 1500ms session refetch) — refresh is never starved — and the ~88-requests-in-3s polling storm does not return.
result: pass
reported: "Verified under a genuinely connected SSE stream (scenario re-triggered via API on an existing session): header live-updated 40→80 events / 6→12 coroutines in real time; network showed 2× GET session snapshot + 1× /events + 1× /threads during the ~5s burst — bounded, max-wait honored, no starvation. Degraded (SSE-dead) mode also bounded: ~3 snapshot GETs per 4.5s. The 88-req/3s storm is gone."

## Summary

total: 2
passed: 1
issues: 1
pending: 0
skipped: 0
blocked: 0

## Gaps

- truth: "Threads tab renders live thread-lane data during and after a scenario run"
  status: resolved
  reason: "GET /api/sessions/{id}/threads returns 200 with data shaped Map<String, List<ThreadEvent>> (e.g. {\"57\":[…],\"80\":[…]}), but frontend ThreadActivityResponse expects {threads, dispatcherInfo}; data.threads is undefined → permanent 'No thread activity data available yet'. SessionDetails.tsx hides the mismatch with an 'as unknown as' double cast. Same finding as 01-REVIEW.md critical CR-02."
  severity: major
  test: 1
  artifacts:
    - "frontend/src/lib/api-client.ts:134 (getThreadActivity return type)"
    - "frontend/src/hooks/use-thread-activity.ts (consumes .threads)"
    - "frontend/src/components/SessionDetails.tsx:408 (double cast)"
    - "backend ProjectionService.kt:234 (returns Map<String, List<ThreadEvent>>)"
  missing:
    - "Align FE types/adapters with the real Map response (or change BE to return {threads, dispatcherInfo}); remove the double cast; value-asserting integration test on the live shape"

- truth: "SSE connects on first load of a freshly created session (gallery → Run flow)"
  status: resolved
  reason: "On a session with zero events, the backend SSE handler writes no bytes (no headers flushed) until the first event — curl shows HTTP 000/no response; via the Vite proxy the browser's EventSource request died with HTTP 500. Per the EventSource spec a non-200 is FATAL (no auto-reconnect), so the live view is permanently dead: badge stuck 'Connecting…', event count frozen at last REST fetch (23 vs backend 40), Run button stuck 'Scenario Running'. Reload fixes it because replay bytes flush headers immediately. Round-1's 'Connecting… badge' cosmetic finding was this same bug, misdiagnosed."
  severity: major
  test: 1
  artifacts:
    - "backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt:176 (sse route — no initial flush/heartbeat for 0-event sessions)"
    - "frontend/src/hooks/use-event-stream.ts (no retry/recovery after fatal EventSource error)"
  missing:
    - "Backend: send an immediate comment/heartbeat (or connected event) on SSE open so headers flush for 0-event sessions"
    - "Frontend: handle EventSource fatal errors with bounded retry/backoff instead of staying 'Connecting…' forever"

## Resolved Gaps (previous rounds)

## Resolved Gaps (previous rounds)

- truth: "Validation Timing Report shows durations in correct units"
  status: resolved
  reason: "Closed by plan 01-10 — TimingAnalyzer converts ns→ms; verified 2026-06-12 re-verification (12/12)"
  severity: major
  test: 1

- truth: "Session validation passes on a clean scenario run (no NoEventsAfterTerminal failures from the app's own instrumentation)"
  status: resolved
  reason: "Closed by plan 01-09 — JobStateChanged emitted before terminal lifecycle event; verified 2026-06-12 re-verification (12/12)"
  severity: major
  test: 1

- truth: "Jobs tab shows job states for a session with running/completed coroutines"
  status: resolved
  reason: "Closed by plan 01-11 — discriminator normalization (type→kind); verified 2026-06-12 re-verification (12/12)"
  severity: major
  test: 2

- truth: "Session page does not poll the REST API at high frequency while SSE is connected"
  status: resolved
  reason: "Closed by plan 01-12 (debounced invalidation) + 01-13 (max-wait caps preserve freshness); verified 2026-06-12 re-verification (12/12)"
  severity: minor
  test: 2

- truth: "Scenario Controls return to runnable state after the scenario completes"
  status: resolved
  reason: "Closed by plan 01-12; verified 2026-06-12 re-verification (12/12)"
  severity: minor
  test: 2

- truth: "Connection badge reflects actual SSE state"
  status: resolved
  reason: "Closed by plan 01-12; verified 2026-06-12 re-verification (12/12)"
  severity: cosmetic
  test: 2

- truth: "Structured Concurrency info panel matches implemented semantics"
  status: resolved
  reason: "Closed by plan 01-12 — panel copy aligned with parent-FAILED rendering; verified 2026-06-12 re-verification (12/12)"
  severity: cosmetic
  test: 2

- truth: "Gallery Run flow lands on a runnable session page"
  status: resolved
  reason: "Fixed inline during UAT round 1 (gallery/index.tsx passes scenarioId; Layout.tsx adds Gallery nav link); verified in browser"
  severity: major
  test: 2
