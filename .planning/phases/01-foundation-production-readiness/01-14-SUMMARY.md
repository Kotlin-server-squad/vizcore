---
phase: 01-foundation-production-readiness
plan: 14
subsystem: ui
tags: [react, typescript, tanstack-query, vitest, msw, thread-activity]

# Dependency graph
requires:
  - phase: 01-foundation-production-readiness (plan 13)
    provides: "thread-activity SSE invalidation + isLive 5s/2s refetch logic (locked, untouched)"
provides:
  - "buildThreadLanes pure adapter: wire shape Map<threadId, ThreadEvent[]> -> derived ThreadActivityResponse view model"
  - "apiClient.getThreadActivity retyped to the real wire shape (Promise<ThreadActivity>)"
  - "useThreadLanesByDispatcher/useActiveCoroutinesPerThread rewritten over the adapter; ThreadLanesView external contract unchanged"
  - "MSW /threads mock serves the same wire shape as the backend (generateMockThreadActivityWire)"
  - "Value-asserting unit + integration tests on the live wire shape (UAT gap 1)"
affects: [01-verification, uat-retest, threads-tab, dispatcher-overview]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Wire shape is the client type; derived view models are built by pure adapters in src/lib (no React imports)"
    - "MSW mocks must serialize exactly what the backend serializes — never a hand-invented shape"

key-files:
  created:
    - frontend/src/lib/thread-lanes.ts
    - frontend/src/lib/thread-lanes.test.ts
  modified:
    - frontend/src/types/api.ts
    - frontend/src/lib/api-client.ts
    - frontend/src/hooks/use-thread-activity.ts
    - frontend/src/hooks/use-thread-activity.test.ts
    - frontend/src/components/DispatcherOverview.tsx
    - frontend/src/components/SessionDetails.tsx
    - frontend/src/components/SessionDetails.test.tsx
    - frontend/src/mocks/mock-data.ts

key-decisions:
  - "Unknown-dispatcher lanes (dispatcherId null) are matched into the 'Unknown' DispatcherInfo group via (dispatcherId ?? 'Unknown') in useThreadLanesByDispatcher — strict id equality would silently drop them"
  - "Fictional-shape mock generators (generateMockThreadActivity/Lane/Segment) deleted outright rather than kept as orphans, closing the CR-02 regression trap"
  - "SessionDetails.test.tsx mocks apiClient (not the hook) and renders the real ThreadTimeline so the integration test exercises the genuine wire-shape pipeline"

patterns-established:
  - "Pure adapter pattern: buildThreadLanes in src/lib derives UI view models from wire shapes, unit-testable without renderHook"
  - "Exact-value test assertions (toBe/toEqual on full objects) instead of presence-of-name checks for derived math"

requirements-completed: [FIX-02]

# Metrics
duration: 10min
completed: 2026-06-12
---

# Phase 01 Plan 14: Threads-Tab Wire-Shape Alignment Summary

**Frontend retyped to the real GET /threads wire shape (Map<threadId, ThreadEvent[]>) with a pure buildThreadLanes adapter deriving lanes/dispatchers — Threads tab and DispatcherOverview now render live data instead of permanent empty states (UAT round-2 gap 1, REVIEW CR-02)**

## Performance

- **Duration:** ~10 min
- **Started:** 2026-06-12T12:10:53Z
- **Completed:** 2026-06-12T12:20:17Z
- **Tasks:** 3
- **Files modified:** 10

## Accomplishments
- `buildThreadLanes(activity: ThreadActivity): ThreadActivityResponse` pure adapter (no React imports): per-coroutine ASSIGNED/RELEASED segment pairing, global-span utilization (open segments closed at max timestamp, zero-span yields 0 not NaN), dispatcher grouping with null -> 'Unknown'
- `ThreadEvent` gained `dispatcherName?: string | null` matching the backend model; `ThreadActivityResponse` documented as a derived view model, not a wire shape
- `apiClient.getThreadActivity` returns `Promise<ThreadActivity>`; the `as unknown as` double cast in SessionDetails.tsx:408 is gone — the prop is typed end-to-end
- `useThreadLanesByDispatcher`/`useActiveCoroutinesPerThread` rewritten over the adapter; the meaningless `Date.now() * 1_000_000` epoch-vs-nanoTime comparison deleted; ThreadLanesView compiles unchanged
- DispatcherOverview reads `dispatcherInfo` from `useThreadLanesByDispatcher` — the permanent "No dispatcher data available" state is structurally fixed
- MSW now serves the wire shape via `generateMockThreadActivityWire`; the fictional `{threads, dispatcherInfo}` generators are deleted, so the fictional shape can never ship green again
- Full suite green: 29 test files, 263 tests; `tsc --noEmit` clean; new SessionDetails integration test proves the Threads tab renders worker names and ASSIGNED chips from the exact UAT-observed payload

## Task Commits

Each task was committed atomically:

1. **Task 1: Wire-shape types + buildThreadLanes adapter (TDD)** - `329edb9` (test, RED) + `e71f476` (feat, GREEN)
2. **Task 2: Retype client, rewrite consumers, remove double cast, fix MSW mock** - `da338c1` (feat)
3. **Task 3: Value-asserting integration tests on the live wire shape** - `e5d7d24` (test)

## TDD Gate Compliance

- RED gate: `329edb9` test commit — 6 failing tests (module absent, verified failing before implementation)
- GREEN gate: `e71f476` feat commit — all 6 tests passing
- REFACTOR gate: not needed (implementation clean on first pass)

## Files Created/Modified
- `frontend/src/lib/thread-lanes.ts` - Pure adapter: wire shape -> derived lane/dispatcher view model (new)
- `frontend/src/lib/thread-lanes.test.ts` - 6 exact-value unit tests: segment pairing, utilization math (toBe(1)/toBe(0.75)), dispatcher grouping, lane metadata, degenerate inputs, unmatched RELEASED (new)
- `frontend/src/types/api.ts` - ThreadEvent.dispatcherName added; ThreadActivityResponse re-documented as derived view model
- `frontend/src/lib/api-client.ts` - getThreadActivity returns Promise<ThreadActivity> (wire type only)
- `frontend/src/hooks/use-thread-activity.ts` - Lane hooks derive via buildThreadLanes; epoch-nanos comparison deleted; queryKey/refetchInterval untouched (01-13 lock respected)
- `frontend/src/hooks/use-thread-activity.test.ts` - Rewritten to wire-shape fixtures; exact derived values (utilization 0.25/0.5/0.75, avg 0.5); new useActiveCoroutinesPerThread open-segment test
- `frontend/src/components/DispatcherOverview.tsx` - Consumes useThreadLanesByDispatcher dispatcherInfo
- `frontend/src/components/SessionDetails.tsx` - Double cast removed (one-line change; 01-12/01-13 debounce logic untouched)
- `frontend/src/components/SessionDetails.test.tsx` - New UAT-gap-1 integration test with real ThreadTimeline + mocked apiClient serving the byte-for-byte UAT payload
- `frontend/src/mocks/mock-data.ts` - generateMockThreadActivityWire (wire shape); fictional generators deleted

## Decisions Made
- **Unknown-dispatcher grouping:** `useThreadLanesByDispatcher` matches lanes with `(dispatcherId ?? 'Unknown') === dispatcher.id` — strict equality against the 'Unknown' group id would have dropped null-dispatcher lanes (latent bug in the plan's "exactly as the current code does" instruction)
- **Fictional generators deleted:** `generateMockThreadActivity`, `generateMockThreadLane`, `generateMockThreadSegment` were orphaned after pointing `generateCompleteScenario` at the wire generator; deleted per plan guidance so the fictional shape has no remaining producer
- **Integration test strategy:** replaced the file-wide `useThreadActivity` hook mock and `ThreadTimeline` stub with an `apiClient` mock, so the real hook + real component run end-to-end against the wire payload

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] thread-lanes.test.ts violated strict indexed access**
- **Found during:** Task 2 (tsc verification)
- **Issue:** `threads[0].field` in the new test file failed `tsc --noEmit` under strict indexed access (Object is possibly 'undefined')
- **Fix:** Non-null assertions (`threads[0]!`) at four assertion sites
- **Files modified:** frontend/src/lib/thread-lanes.test.ts
- **Verification:** tsc clean, tests still pass
- **Committed in:** da338c1 (Task 2 commit)

**2. [Rule 1 - Bug] Unknown-dispatcher lanes would be dropped by strict id grouping**
- **Found during:** Task 2 (hook rewrite)
- **Issue:** Lanes without a dispatcherName carry `dispatcherId: null` but group under the 'Unknown' DispatcherInfo entry (id 'Unknown'); the plan's "group exactly as the current code does" filter (`t.dispatcherId === dispatcher.id`) would produce an empty 'Unknown' group
- **Fix:** Filter with `(t.dispatcherId ?? 'Unknown') === dispatcher.id`
- **Files modified:** frontend/src/hooks/use-thread-activity.ts
- **Verification:** thread-lanes 'Unknown' grouping test + full suite green
- **Committed in:** da338c1 (Task 2 commit)

**3. [Rule 3 - Blocking] Task 2 tsc gate blocked by Task-3-owned test file**
- **Found during:** Task 2 (verification)
- **Issue:** Task 2's `tsc --noEmit` gate could not pass while `use-thread-activity.test.ts` (assigned to Task 3) still fed the fictional shape into the retyped client mock — a sequencing conflict in the plan
- **Fix:** Verified at Task 2 commit time that the ONLY remaining tsc error was in the Task-3-owned file; full tsc gate re-run and passed after Task 3's rewrite
- **Files modified:** none (sequencing resolution only)
- **Verification:** `tsc --noEmit` exits 0 after e5d7d24
- **Committed in:** e5d7d24 (Task 3 commit)

---

**Total deviations:** 3 auto-fixed (2 bugs, 1 blocking/sequencing)
**Impact on plan:** All fixes necessary for compile correctness; no scope creep. Locked files (use-event-stream.ts, use-event-stream-debounce.test.ts) and locked logic (queryKey, isLive refetchInterval) untouched.

## Issues Encountered
None beyond the deviations above.

## Known Stubs
None — all wired paths carry real data; the MSW mock now produces the same shape as the backend serializer.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- UAT gap 1 is structurally closed; human UAT re-test (browser walkthrough: run a scenario, confirm Threads tab lanes + dispatcher cards) remains as the plan's verification item 5
- Plan 01-15 (SSE first-connect) can proceed — its locked files were not touched

## Self-Check: PASSED

- frontend/src/lib/thread-lanes.ts: FOUND
- frontend/src/lib/thread-lanes.test.ts: FOUND
- Commits 329edb9, e71f476, da338c1, e5d7d24: FOUND in git log
- tsc --noEmit: exit 0; pnpm test: 29 files / 263 tests passed

---
*Phase: 01-foundation-production-readiness*
*Completed: 2026-06-12*
