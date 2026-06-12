---
status: testing
phase: 01-foundation-production-readiness
source: [01-VERIFICATION.md]
started: 2026-06-12T06:57:01Z
updated: 2026-06-12T11:35:00Z
---

## Current Test

number: 1
name: Threads tab freshness during live session
expected: |
  Run a scenario with live streaming enabled. The Threads tab (thread lanes)
  keeps updating while the scenario executes — it does not freeze on its
  first snapshot for the duration of the live session.
awaiting: user response

## Tests

### 1. Threads tab freshness during live session
expected: Run a scenario with live streaming; thread lanes update during execution (no freeze on first snapshot). Closed by 01-13 (CR-01) — every SSE debounce flush now invalidates ['thread-activity', sessionId] plus a 5s fallback poll while live; this confirms it in a real browser.
result: [pending]

### 2. Bounded snapshot cadence under sustained stream
expected: With Network DevTools open during a dense event stream, GET /api/sessions/{id} fires at least once per ~1–1.5s (max-wait caps: 1000ms invalidation, 1500ms session refetch) — refresh is never starved — and the ~88-requests-in-3s polling storm does not return.
result: [pending]

## Summary

total: 2
passed: 0
issues: 0
pending: 2
skipped: 0
blocked: 0

## Gaps

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
