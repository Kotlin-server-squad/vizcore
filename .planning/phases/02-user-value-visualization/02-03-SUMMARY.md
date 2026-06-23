---
phase: 02-user-value-visualization
plan: 03
subsystem: frontend-replay-projections
tags: [replay, projections, oq-1, adr-017, rply-01, rply-02]
requires:
  - frontend/src/types/api.ts (CoroutineNode, CoroutineState, ThreadActivity, VizEvent)
  - frontend/src/lib/thread-lanes.ts (ThreadActivity wire shape contract)
  - backend EventApplier.kt / ProjectionService.kt (authoritative state machine, oracle)
provides:
  - projectCoroutines (VizEvent[] -> CoroutineNode[] pure reducer, OQ-1)
  - projectThreadActivity (VizEvent[] -> ThreadActivity pure reducer, OQ-1)
  - useReplay delay clamp aligned to ADR-017 (50-2000ms base, then / speed)
  - use-replay Wave-0 test coverage (clamp + speed + play/pause/step/seek)
affects:
  - plan 02-07 (replay mount — panels derive view-models from visibleEvents)
  - plan 02-08 (recording pipeline)
tech-stack:
  added: []
  patterns:
    - pure framework-free reducer (mirrors thread-lanes.ts), useMemo-safe
    - server snapshot as test oracle (deep-equal against EventApplier semantics)
key-files:
  created:
    - frontend/src/lib/projections/project-coroutines.ts
    - frontend/src/lib/projections/project-coroutines.test.ts
    - frontend/src/lib/projections/project-thread-activity.ts
    - frontend/src/lib/projections/project-thread-activity.test.ts
  modified:
    - frontend/src/hooks/use-replay.ts
    - frontend/src/hooks/use-replay.test.ts
decisions:
  - "projectCoroutines emits nodes in creation order (first CoroutineCreated seen), matching the backend RuntimeSnapshot.coroutines LinkedHashMap insertion order, so deep-equal against the server snapshot holds."
  - "Terminal state classified strictly by event kind (completed/cancelled/failed), never message text (FIX-03)."
  - "Existing two 10ms-interval use-replay timing tests realigned to the new 50ms clamp floor (Rule 1 — the clamp fix changes timing behavior the tests assert)."
metrics:
  duration: ~17 min
  completed: 2026-06-14
  tasks: 2
  files: 6
---

# Phase 2 Plan 03: Client-Side Event→Snapshot Projection Layer + Replay Clamp Summary

Built the OQ-1 client-side projection layer — `projectCoroutines` (VizEvent[] → CoroutineNode[]) and `projectThreadActivity` (VizEvent[] → Map<threadId, ThreadEvent[]>) pure reducers validated deep-equal against the backend state machine as oracle — and reconciled `useReplay`'s delay clamp to the ADR-017 50–2000ms base (clamp-then-÷-speed), adding the Wave-0 `use-replay` test that the rest of Wave 3/4 replay work depends on.

## What Was Built

### Task 1 — `projectCoroutines` + `projectThreadActivity` reducers (commit 171e72f)
- `frontend/src/lib/projections/project-coroutines.ts` — pure, side-effect-free reducer reimplementing the backend `EventApplier.kt` state machine in TS: CREATED→ACTIVE (started/resumed), ACTIVE→SUSPENDED (suspended), →WAITING_FOR_CHILDREN (body-completed), and the terminal trio COMPLETED/CANCELLED/FAILED classified by event kind. Nodes emitted in creation order (insertion-ordered Map) to match the server `RuntimeSnapshot.coroutines` iteration order. Lifecycle events for an uncreated coroutine are ignored (mirrors EventApplier's null-guard).
- `frontend/src/lib/projections/project-thread-activity.ts` — pure reducer folding `ThreadAssigned` events into the `Map<threadId, ThreadEvent[]>` wire shape `buildThreadLanes` consumes; every entry is `eventType: 'ASSIGNED'` (the backend records only ASSIGNED here), keyed by `threadId.toString()` in arrival order.
- Tests (14 cases): full-session oracle deep-equal, terminal-trio classification (FIX-03), mid-cursor in-progress prefix (SUSPENDED/ACTIVE), purity (no input mutation), unknown-coroutine guard, and a round-trip asserting `projectThreadActivity` output feeds `buildThreadLanes` without throwing.

### Task 2 — `useReplay` delay clamp reconciliation + Wave-0 test (commit d76a774)
- `frontend/src/hooks/use-replay.ts` — replaced the 10ms-floor-then-÷-speed delay with the ADR-017 clamp: `baseMs = min(max(gapMs, 50), 2000)`, then `delayMs = baseMs / speed`. Public API (ReplayState / ReplayControls / UseReplayReturn) unchanged; no useReducer refactor (per RESEARCH recommendation).
- `frontend/src/hooks/use-replay.test.ts` — added a dedicated `delay clamp (ADR-017)` describe block: sub-50ms clamped up, above-2000ms clamped down, in-range unchanged, and `÷ speed` (setSpeed(2) halves the effective delay). Realigned the two existing 10ms-interval timing tests to the new 50ms floor.

## Verification

- `cd frontend && pnpm test projections use-replay --run` → 42 tests green (3 files).
- `cd frontend && pnpm tsc --noEmit` → clean (strict, no `any`).
- `eslint` on all 6 changed files → clean.
- projectCoroutines output deep-equals the server snapshot oracle (EventApplier semantics) for a full captured session including terminal and mid-cursor states.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Existing 10ms-interval use-replay timing tests broke under the new clamp**
- **Found during:** Task 2
- **Issue:** Two pre-existing tests ("auto-advances through events during playback", "stops at the last event") used a 10ms inter-event gap and advanced fake timers by 15ms. Under the ADR-017 50ms floor those assertions no longer hold — the clamp fix is the intended behavior change, so the tests, not the fix, were stale.
- **Fix:** Realigned both tests to the clamped 50ms floor (advance 60ms). Scope is strictly the current task's behavior change.
- **Files modified:** frontend/src/hooks/use-replay.test.ts
- **Commit:** d76a774

**2. [Rule 1 - Bug] Test fixture helper coerced an explicit null dispatcherName to 'Default'**
- **Found during:** Task 1 (GREEN run)
- **Issue:** The `threadAssigned` test helper used `overrides.dispatcherName ?? 'Default'`, so the null-preservation test passed `null` but received `'Default'`.
- **Fix:** Switched to an `'dispatcherName' in overrides` presence check so an explicit null is preserved. Test-only; reducer behavior was already correct.
- **Files modified:** frontend/src/lib/projections/project-thread-activity.test.ts
- **Commit:** 171e72f (fixed before the Task 1 commit)

## Threat Model

T-02-05 (client projection diverging from server snapshot) is mitigated as planned: the projection output is asserted deep-equal against the backend `EventApplier` state-machine semantics as oracle, with terminal-state and mid-cursor cases covered explicitly. No new packages added (T-02-SC accept holds — pure TS reducers + existing Vitest). No new threat surface introduced.

## Notes for Downstream Plans

- Plan 02-07 can now wire CoroutineTree / Threads panels to `projectCoroutines(visibleEvents)` / `projectThreadActivity(visibleEvents)` instead of the server snapshot, so they reflect the replay cursor.
- The reducers assume frontend kebab-case event kinds (`coroutine.created`, `thread.assigned`) as produced by `normalizeEvent`; events fed in must already be normalized.

## Self-Check: PASSED

All 6 created/modified source files exist on disk; both task commits (171e72f, d76a774) are present in git history.
