---
phase: 01-foundation-production-readiness
plan: 13
subsystem: frontend
tags: [gap-closure, sse, debounce, max-wait, thread-activity, live-stream-freshness]
dependency_graph:
  requires: [01-12]
  provides: [FIX-02, CR-01-closure, CR-02-closure, max-wait-capped-debounce, thread-activity-invalidation]
  affects:
    - frontend/src/hooks/use-event-stream.ts
    - frontend/src/hooks/use-thread-activity.ts
    - frontend/src/components/SessionDetails.tsx
    - frontend/src/hooks/use-event-stream-debounce.test.ts
    - frontend/src/components/SessionDetails.test.tsx
tech_stack:
  added: []
  patterns:
    - Max-wait-capped trailing-edge debounce (firstEventAt ref + Math.min(debounce, maxWait - elapsed) scheduling)
    - Paired query invalidation per flush (['sessions', id] + ['thread-activity', id])
    - Teardown effect keyed on streamEnabled to avoid per-render cleanup resetting the max-wait window
key_files:
  created: []
  modified:
    - frontend/src/hooks/use-event-stream.ts
    - frontend/src/hooks/use-thread-activity.ts
    - frontend/src/components/SessionDetails.tsx
    - frontend/src/hooks/use-event-stream-debounce.test.ts
    - frontend/src/components/SessionDetails.test.tsx
decisions:
  - INVALIDATION_MAX_WAIT_MS=1000 and SESSION_REFETCH_MAX_WAIT_MS=1500 cap the trailing-edge debounces so a sustained sub-window stream flushes at a bounded cadence (CR-02)
  - Every SSE debounce flush invalidates BOTH ['sessions', sessionId] and ['thread-activity', sessionId]; useThreadActivity keeps a slow 5s fallback poll while live as defense-in-depth (CR-01)
  - SessionDetails max-wait window ref is reset in a streamEnabled-keyed teardown effect, NOT in the main effect cleanup (which runs on every liveEvents.length change and would restart the clock)
  - Existing burst-coalescing test assertions changed from total invalidateQueries call counts to per-queryKey flush counts, since each flush now makes two invalidation calls (semantics preserved, not weakened)
metrics:
  duration: "~7 min"
  completed: "2026-06-12"
  tasks: 2
  files_changed: 5
---

# Phase 01 Plan 13: Live-Stream Freshness Gap Closure (CR-01/CR-02) Summary

## One-liner

Max-wait-capped both post-01-12 debounces (1000ms SSE invalidation, 1500ms session refetch) and wired the missing `['thread-activity', sessionId]` invalidation into the SSE flush — the Threads tab and session snapshot now refresh at a bounded cadence during sustained live streams while burst coalescing stays intact.

## What Was Built

### Task 1: Max-wait cap on SSE invalidation debounce + thread-activity invalidation (commit f22b04f)

**use-event-stream.ts** — converted the pure trailing-edge 400ms debounce into a max-wait-capped debounce:
- `INVALIDATION_MAX_WAIT_MS = 1000` module constant alongside `INVALIDATION_DEBOUNCE_MS = 400`
- `firstInvalidationAtRef` (number | null) tracks the first un-flushed event; each event schedules the flush with `Math.min(INVALIDATION_DEBOUNCE_MS, INVALIDATION_MAX_WAIT_MS - elapsed)` or flushes synchronously once `elapsed >= INVALIDATION_MAX_WAIT_MS`
- The shared `flushInvalidation` closure resets both refs and invalidates **both** `['sessions', sessionId]` and `['thread-activity', sessionId]` — the latter is the CR-01 fix (the key was previously never invalidated anywhere in the repo)
- Effect cleanup also resets `firstInvalidationAtRef`

**use-thread-activity.ts** — `refetchInterval: isLive ? 5000 : 2000` (was `isLive ? false : 2000`); KDoc and inline comments updated to describe SSE-driven invalidation with a 5s fallback poll.

**use-event-stream-debounce.test.ts** — two new tests: (1) sustained 200ms-interval stream for 1400ms (> max-wait) produces at least one `['sessions', ...]` flush mid-stream; (2) every flush is paired with a `['thread-activity', 'session-1']` invalidation (asserted via `toHaveBeenCalledWith` plus a paired-count equality check).

### Task 2: Max-wait cap on SessionDetails session-refetch debounce (commit 4c5ec7b)

**SessionDetails.tsx** — same pattern as Task 1:
- `SESSION_REFETCH_DEBOUNCE_MS = 500` (extracted from the inline literal) and `SESSION_REFETCH_MAX_WAIT_MS = 1500` at module scope near `TERMINAL_STATES`
- `firstSessionRefetchAtRef` tracks the un-flushed window; `refetch()` flushes immediately once `elapsed >= SESSION_REFETCH_MAX_WAIT_MS`, otherwise scheduled with `Math.min(...)` so the trailing edge can never be starved
- Dependency array preserved as `[streamEnabled, liveEvents.length, refetch]`; `handleRunScenario`'s post-run `setTimeout(() => refetch(), 500)` and the auto-enable effect untouched (out of scope per plan)

**SessionDetails.test.tsx** — new describe block with fake timers: drives `liveEvents` through the `useEventStream` mock, pushing one event every 250ms for 2000ms (> max-wait) with rerenders, asserting the mocked `refetch` fires at least once mid-stream. The existing three-state scenario-button tests are unchanged.

## Verification Results

1. `vitest run src/hooks/use-event-stream-debounce.test.ts src/components/SessionDetails.test.tsx` — 15/15 pass (full suite also run: 254/254 + new tests pass)
2. CR-01 closed: `grep "queryKey: \['thread-activity'" src/hooks/use-event-stream.ts` → hit at line 114
3. CR-02 closed: `grep "MAX_WAIT"` hits in both `use-event-stream.ts` and `SessionDetails.tsx`
4. ESLint clean on all five modified files (one pre-existing `no-explicit-any` warning in use-event-stream.ts line 87, predates this plan, out of scope)

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Existing burst-coalescing assertions counted total invalidateQueries calls**
- **Found during:** Task 1
- **Issue:** The plan mandates a second `invalidateQueries` call (thread-activity) per flush, which makes the existing `toHaveBeenCalledTimes(1)`/`(2)` assertions fail — the plan simultaneously required those tests to "still pass unchanged," which is impossible as literally written.
- **Fix:** Added an `invalidationCount(queryClient, keyRoot)` helper and changed the assertions to count flushes per query key (`'sessions'`). The coalescing semantics the tests prove (zero flushes mid-burst, exactly one flush per burst, two flushes for two bursts) are fully preserved.
- **Files modified:** frontend/src/hooks/use-event-stream-debounce.test.ts
- **Commit:** f22b04f

**2. [Rule 1 - Bug] Plan's cleanup instruction would have broken the SessionDetails max-wait**
- **Found during:** Task 2
- **Issue:** The plan instructed resetting `firstSessionRefetchAtRef.current = null` in the main effect's cleanup. That cleanup runs on every `liveEvents.length` change (it's in the dependency array), so the max-wait clock would restart on every event — reintroducing the exact starvation the plan fixes. The new regression test catches this.
- **Fix:** The main effect cleanup only clears the timer (as before); the window-ref reset lives in a separate teardown effect keyed on `[streamEnabled]`, which runs only on stream toggle and unmount. A code comment documents why.
- **Files modified:** frontend/src/components/SessionDetails.tsx
- **Commit:** 4c5ec7b

## Known Stubs

None — no placeholder values, empty data sources, or TODO markers introduced.

## Threat Flags

None — no new endpoints, inputs, auth paths, or dependencies; pure client-side refresh-timing change per the plan's threat model (T-01-13-01 mitigated by the bounded cadence itself; T-01-13-02 N/A, zero packages installed).

## Commits

| Task | Commit | Description |
|------|--------|-------------|
| 1 | f22b04f | Max-wait cap on SSE invalidation debounce + thread-activity invalidation + 5s live fallback poll |
| 2 | 4c5ec7b | Max-wait cap on SessionDetails session-refetch debounce + sustained-stream regression test |

## Self-Check: PASSED

- SUMMARY.md exists on disk
- Commits f22b04f and 4c5ec7b found in git log
- All three modified source files present
