---
title: Sync "Producer-Consumer Buffer" scenario crashes 500 — vizSemaphore(permits = 0) is rejected by kotlinx Semaphore
area: backend (scenario + coroutine-viz-core VizSemaphore wrapper)
severity: medium
status: fixed
fixed: 2026-06-22
found: 2026-06-22
phase: 2 (instrumentation wrappers) / scenario library
requirement: sync-scenario library / Gallery Sync tab
discovered_during: Phase-3 browser UAT deep-dive (Gallery Sync family, /api/sync/semaphore/producer-consumer)
ledger_id: F10
---

## Symptom (reproduced live, 100% deterministic)
`GET /api/sync/semaphore/producer-consumer` → HTTP 500:

```json
{"success":false,"sessionId":"","scenario":"Producer-Consumer Buffer",
 "message":"Scenario failed: Semaphore should have at least 1 permit, but had 0","eventCount":0}
```

The scenario never produces a session or any events — it throws before doing anything.

## Root cause
`scenarios/SyncScenarios.kt::producerConsumerBuffer` (line ~337) does:

```kotlin
val emptySlots = scope.vizSemaphore("buffer-empty-slots", permits = bufferSize)
val fullSlots  = scope.vizSemaphore("buffer-full-slots", permits = 0)   // <-- throws
```

`fullSlots = 0` is the textbook-correct initial value for a bounded-buffer counting semaphore (no
items to consume yet). BUT `coroutine-viz-core/wrappers/VizSemaphore.kt` constructs
`private val delegate = Semaphore(permits)` and `kotlinx.coroutines.sync.Semaphore` requires
`permits >= 1` (`require(permits >= 1) { "Semaphore should have at least 1 permit, but had $permits" }`).
So the wrapper rejects the valid producer-consumer initial state.

`VizSemaphore`/`vizSemaphore` expose only `permits` — there is **no `acquiredPermits` parameter**, so
the standard kotlinx idiom for "0 available out of N" (`Semaphore(permits = N, acquiredPermits = N)`)
isn't reachable.

## Why tests didn't catch it
No test (or prior UAT) ran `/api/sync/semaphore/producer-consumer`. The whole Sync family was
previously unreachable under DB+auth mode anyway (see F9 — sync sessions weren't tenant-scoped), so
this 500 was masked.

## Candidate fixes
- Preferred: add `acquiredPermits: Int = 0` to `VizSemaphore` + the `vizSemaphore(...)` builder, pass
  it to `Semaphore(permits, acquiredPermits)`, and make the available-permits accounting
  (`availablePermits`, `totalPermits`) account for it. Then the scenario uses
  `vizSemaphore("buffer-full-slots", permits = bufferSize, acquiredPermits = bufferSize)`.
- Add a unit test: `VizSemaphore` with acquiredPermits == permits reports 0 available and blocks an
  acquire until a release.
- Add a route/integration smoke test that every `/api/sync/*` endpoint returns 200 (would have caught
  both this and F9).

## Scope note
This is a deeper change than the F5/F7/F9 one-line scoping fixes (touches the core wrapper's permit
accounting), so logged for a focused follow-up rather than fixed inline during the walkthrough.

## Related
- F9 (sync routes now tenant-scoped) — fixing F9 is what made this scenario reachable to surface the 500.

## ✅ RESOLUTION (2026-06-22)
Added a backward-compatible `acquiredPermits: Int = 0` parameter to `VizSemaphore` and the
`vizSemaphore(...)` builder, passed through to `Semaphore(permits, acquiredPermits)`. The scenario now
builds `fullSlots` as `vizSemaphore("buffer-full-slots", permits = bufferSize, acquiredPermits = bufferSize)`
(0 available, capacity bufferSize). Default `acquiredPermits = 0` keeps every existing caller unchanged.
Regression: `VizSemaphoreTest` (acquiredPermits == permits → 0 available, release/acquire cycle; default
keeps all permits available). Full backend suite green.
