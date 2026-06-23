---
phase: 01-foundation-production-readiness
plan: 12
subsystem: frontend
tags: [polling-storm, sse, debounce, scenario-controls, structured-concurrency, tdd, gap-closure]
dependency_graph:
  requires: [01-11]
  provides: [FIX-02, FIX-03, polling-storm-fix, completion-aware-scenario-button, connection-badge-fix, failure-propagation-copy]
  affects:
    - frontend/src/components/SessionDetails.tsx
    - frontend/src/hooks/use-event-stream.ts
    - frontend/src/hooks/use-thread-activity.ts
    - frontend/src/components/StructuredConcurrencyInfo.tsx
    - frontend/src/components/SessionDetails.test.tsx
    - frontend/src/components/StructuredConcurrencyInfo.test.tsx
    - frontend/src/hooks/use-event-stream-debounce.test.ts
tech_stack:
  added: []
  patterns:
    - TDD RED/GREEN (4 commits: 2 RED, 2 GREEN)
    - Trailing-edge debounce via useRef timer
    - Three-state derived value (notStarted/running/completed) from session.coroutines
    - isLive gating of React Query refetchInterval
key_files:
  created:
    - frontend/src/hooks/use-event-stream-debounce.test.ts
    - frontend/src/components/StructuredConcurrencyInfo.test.tsx
  modified:
    - frontend/src/hooks/use-event-stream.ts
    - frontend/src/hooks/use-thread-activity.ts
    - frontend/src/components/SessionDetails.tsx
    - frontend/src/components/StructuredConcurrencyInfo.tsx
    - frontend/src/components/SessionDetails.test.tsx
decisions:
  - Debounce window for SSE invalidations set to 400ms (trailing edge); session refetch debounce at 500ms — both eliminate the per-event ~88-in-3s request storm
  - useThreadActivity accepts isLive flag; when true, refetchInterval=false disabling 2s poll while SSE drives updates; falls back to 2s when SSE inactive
  - autoRefresh state and chip removed entirely (no longer needed; badge correctness relies on onopen handler which was already correct)
  - Three-state scenarioState (notStarted/running/completed) derived from session.coroutines using TERMINAL_STATES set; avoids hasStarted ambiguity
  - Completed state renders a disabled 'Scenario Completed' button (success color) to be visually distinct from in-progress; no re-run affordance (session reset required)
  - StructuredConcurrencyInfo copy: parent "gets FAILED (completes exceptionally)" — matches CoroutineFailed backend event and rendered tree node color
metrics:
  duration: "~22 min"
  completed: "2026-06-12"
  tasks: 2
  files_changed: 7
---

# Phase 01 Plan 12: Session Page Polish (UAT Gap Closure) Summary

## One-liner

Eliminated the ~88-in-3s SSE polling storm, made the scenario button completion-aware, fixed the connection badge, and corrected the Failure Propagation copy to say the parent becomes FAILED.

## What Was Built

### Task 1: Stop the polling storm and fix the connection badge (commits: 85ac017, bcf229d)

**Root cause audit** identified three sources of the ~88-request-in-3s storm:
1. `useEventStream`: fired `queryClient.invalidateQueries` on **every** SSE event
2. `SessionDetails`: two concurrent timers — a 200ms debounce on `liveEvents.length` changes AND a 500ms `setInterval` (both calling `refetch()`)
3. `useThreadActivity`: `refetchInterval: 2000` fired regardless of whether SSE was active

**Fixes applied:**

- **`use-event-stream.ts`** — replaced the per-event `invalidateQueries` call with a trailing-edge debounce using `useRef<ReturnType<typeof setTimeout>>`. A burst of N events resets the timer on each arrival; only the trailing edge (400ms after last event) fires one `invalidateQueries`. Cleanup cancels the timer on unmount.

- **`use-thread-activity.ts`** — added optional `isLive = false` parameter; when `true`, sets `refetchInterval: false` to disable the 2s background polling. When SSE is not active the original 2s polling is preserved.

- **`SessionDetails.tsx`** — three changes:
  - Removed the `autoRefresh` state and the 500ms `setInterval` effect entirely
  - Replaced the 200ms `liveEvents.length` debounce with a single 500ms trailing-edge debounce (same `useRef` pattern)
  - Passes `streamEnabled` as `isLive` to `useThreadActivity`
  - Removed the `autoRefresh` chip UI (no longer meaningful)

**Connection badge:** The `onopen` handler was already calling `setIsConnected(true)` correctly. The `Connecting...` badge persisted because the `autoRefresh`/stream-enable effect was resetting state indirectly. With the autoRefresh state removed, the `isConnected` value flows directly from `onopen` → `setIsConnected(true)` with no competing reset path.

**Net observable behavior:** A freshly loaded live session with SSE active produces a bounded, small number of REST requests. The `use-thread-activity` no longer polls every 2s during live sessions.

**Tests added:** `frontend/src/hooks/use-event-stream-debounce.test.ts` (4 tests) using `vi.useFakeTimers()` + `act()` with synchronous timer advancement (avoiding `waitFor` / fake-timer interaction issues):
- mid-burst: 0 invalidations at t=100ms (inside 400ms debounce window)
- post-burst: exactly 1 invalidation after advancing 600ms
- two separate bursts: 2 total invalidations
- `useThreadActivity` signature accepts `isLive` flag

---

### Task 2: Completion-aware scenario controls + corrected concurrency copy (commits: 9d56fa7, 8023344)

**Scenario button three-state logic (`SessionDetails.tsx`):**

Added `TERMINAL_STATES = new Set([COMPLETED, CANCELLED, FAILED])` and replaced the binary `hasStarted` gate with a `useMemo`-derived `scenarioState`:

| State | Condition | Button | Helper text |
|-------|-----------|--------|-------------|
| `notStarted` | `coroutineCount === 0` or `coroutines.length === 0` | "Run Scenario" (enabled) | "Ready to run the scenario" |
| `running` | coroutines exist, some non-terminal | "Scenario Running" (disabled) | "Scenario is running" |
| `completed` | coroutines exist, all terminal | "Scenario Completed" (disabled, success color) | "Scenario completed" |

The completed state shows a visually distinct success-colored button; reset is always available via the Reset/Clear buttons. Re-run from a completed state requires session reset.

**StructuredConcurrencyInfo copy fix:** Changed the Failure Propagation description from:
> "...the parent, which gets `CANCELLED`..."

to:
> "...the parent, which gets `FAILED` (completes exceptionally)..."

This matches: (a) the backend `CoroutineFailed` event emitted for the parent, (b) the FAILED (red) node color in the rendered coroutine tree, and (c) Kotlin's actual structured-concurrency semantics where an uncaught child exception fails the parent scope.

**Tests:**
- `SessionDetails.test.tsx` +3 new tests covering the three button states
- `StructuredConcurrencyInfo.test.tsx` (new file): asserts "which gets FAILED" present and "which gets CANCELLED" absent in the Failure Propagation block; retains sibling-CANCELLED assertion

---

## Test Results

All 252 frontend tests pass (up from 242 before this plan):
- `use-event-stream-debounce.test.ts`: 4 new tests
- `StructuredConcurrencyInfo.test.tsx`: 3 new tests
- `SessionDetails.test.tsx`: 3 new tests + 5 existing
- All pre-existing 242 tests: pass unchanged

`pnpm lint`: 0 errors, 9 warnings (all pre-existing, none in files modified by this plan except the pre-existing `no-explicit-any` in `use-event-stream.ts`).

## Deviations from Plan

**[Rule 1 - Bug] `autoRefresh` state reference removed from JSX**
- **Found during:** Task 1 implementation
- **Issue:** After removing `autoRefresh` state variable, the JSX chip `{autoRefresh && <Chip ...>Auto-updating</Chip>}` caused a `ReferenceError`. The chip relied on the now-removed state.
- **Fix:** Removed the `autoRefresh` chip entirely. It was purely cosmetic and tracked the same condition as `streamEnabled` anyway.
- **Files modified:** `SessionDetails.tsx`
- **Commit:** bcf229d

**[Rule 1 - Bug] Test regex `/parent.*CANCELLED/i` false-matched sibling text**
- **Found during:** Task 2 RED → GREEN iteration
- **Issue:** The text "sibling coroutines are also cancelled" comes after "parent" in the same text block; the greedy `.*` caused the regex to match across both phrases even after the fix.
- **Fix:** Changed assertion to `expect(text).not.toMatch(/which gets CANCELLED/i)` — anchored to the specific phrase that would describe the parent's state, not a cross-sentence greedy match.
- **Files modified:** `StructuredConcurrencyInfo.test.tsx`
- **Commit:** 8023344

**[Rule 1 - Bug] `getByText(/scenario completed/i)` found multiple elements**
- **Found during:** Task 2 GREEN verification
- **Issue:** "Scenario completed" appeared in both the button label ("Scenario Completed") and helper text ("Scenario completed"), causing `getByText` to throw a multiple-elements error.
- **Fix:** Changed to `getByRole('button', { name: /scenario completed/i })` which is unambiguous.
- **Files modified:** `SessionDetails.test.tsx`
- **Commit:** 8023344

## Known Stubs

None — all changes are functional fixes with no placeholder data.

## Threat Surface Scan

No new network endpoints, auth paths, or schema changes introduced. T-01-12-01 (DoS: self-inflicted polling storm) is mitigated by the debounce + isLive gating changes. T-01-12-02 (no new dependencies) holds — this plan introduced zero new packages.

## Self-Check: PASSED

- `frontend/src/hooks/use-event-stream-debounce.test.ts` exists: FOUND
- `frontend/src/components/StructuredConcurrencyInfo.test.tsx` exists: FOUND
- Task 1 RED commit 85ac017: confirmed
- Task 1 GREEN commit bcf229d: confirmed
- Task 2 RED commit 9d56fa7: confirmed
- Task 2 GREEN commit 8023344: confirmed
- 252 tests pass: confirmed
