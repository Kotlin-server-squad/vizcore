---
phase: 01-foundation-production-readiness
plan: 09
subsystem: backend/coroutine-viz-core
tags: [fix, tdd, event-ordering, validation, vizscope]
dependency_graph:
  requires: []
  provides: [terminal-last-event-ordering, NoEventsAfterTerminalRule-clean]
  affects: [backend/coroutine-viz-core/wrappers/VizScope.kt]
tech_stack:
  added: []
  patterns: [TDD RED/GREEN, invokeOnCompletion ordering, seq-based event ordering]
key_files:
  created:
    - backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/wrappers/VizScopeTerminalOrderingTest.kt
  modified:
    - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt
decisions:
  - Emit JobStateChanged BEFORE terminal lifecycle event in vizLaunch completion handler (ordering-only fix, no logic change)
  - Leave vizAsync invokeOnCompletion untouched (no JobStateChanged there — already terminal-last)
  - Preserve all argument values and branch conditions exactly; only swap send() call order
metrics:
  duration: ~2 min
  completed: 2026-06-12
  tasks_completed: 2
  files_changed: 2
---

# Phase 01 Plan 09: VizScope Terminal Event Ordering Summary

**One-liner:** Reordered VizScope.vizLaunch invokeOnCompletion so JobStateChanged is emitted before CoroutineFailed/CoroutineCancelled, ensuring every coroutine's terminal event carries the highest seq and NoEventsAfterTerminalRule produces zero self-findings.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 (RED) | Add failing terminal-ordering regression test | b8c5ee9 | VizScopeTerminalOrderingTest.kt (new) |
| 2 (GREEN) | Reorder VizScope terminal emission | a7ea515 | VizScope.kt |

## What Was Done

### Task 1 — TDD RED (test file)

Created `VizScopeTerminalOrderingTest` in the coroutine-viz-core test source set with three tests:

- **Test A (Failed ordering):** Launches an instrumented coroutine via vizLaunch that throws `IllegalStateException`; asserts the max-seq event for the failing coroutine is `CoroutineFailed`; asserts any `JobStateChanged` for that coroutineId has a strictly lower seq.
- **Test B (Cancelled ordering):** Launches an instrumented coroutine, cancels it; asserts the max-seq event is `CoroutineCancelled`; asserts `JobStateChanged` has a strictly lower seq.
- **Test C (Validator clean):** Feeds the captured event list through `NoEventsAfterTerminalRule().validate(events)` and asserts zero findings.

All three tests failed as expected before the production fix (confirmed RED state).

### Task 2 — TDD GREEN (production code fix)

In `VizScope.vizLaunch`'s `job.invokeOnCompletion { cause -> ... }` block:

**Failed branch** (`cause !is CancellationException`): Moved `session.send(ctx.jobStateChanged(...))` call **above** `session.send(ctx.coroutineFailed(...))`. Because `nextSeq()` is called at data-class construction time inside each `ctx.*` factory, constructing `jobStateChanged` first gives it the lower seq and leaves `coroutineFailed` with the highest seq.

**Cancelled branch** (`else`): Same reordering — `session.send(ctx.jobStateChanged(...))` moved **above** `session.send(ctx.coroutineCancelled(...))`.

**Unchanged:** The `cause == null` (Completed) branch emits only `CoroutineCompleted` with no trailing `JobStateChanged` — already terminal-last. The `vizAsync` deferred's `invokeOnCompletion` block emits no `JobStateChanged` — already terminal-last.

All argument values (childrenCount, isActive/isCompleted/isCancelled flags, cause/exception arguments) and branch conditions preserved exactly.

## Verification

```
./gradlew :coroutine-viz-core:test --tests 'com.jh.proj.coroutineviz.wrappers.VizScopeTerminalOrderingTest'
```
Result: 3/3 tests PASS.

```
./gradlew :coroutine-viz-core:test --tests 'com.jh.proj.coroutineviz.checksystem.LifecycleValidatorTest'
```
Result: PASS (no regression to the rule).

```
./gradlew :coroutine-viz-core:test
```
Result: Full test suite PASS.

## TDD Gate Compliance

- RED gate commit: `b8c5ee9` — `test(01-09): add failing test for terminal-last event ordering` (3 tests failed)
- GREEN gate commit: `a7ea515` — `feat(01-09): reorder VizScope terminal emission so JobStateChanged precedes terminal event` (3 tests passed)

## Deviations from Plan

None — plan executed exactly as written.

## Threat Surface Scan

No new network endpoints, auth paths, file access patterns, or schema changes introduced. The change is purely an in-process event emission ordering fix within the existing `VizScope.vizLaunch` completion handler.

## Self-Check: PASSED

- [x] `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/wrappers/VizScopeTerminalOrderingTest.kt` — FOUND
- [x] `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt` — FOUND (modified)
- [x] RED commit `b8c5ee9` — FOUND
- [x] GREEN commit `a7ea515` — FOUND
- [x] All 3 terminal-ordering tests PASS
- [x] LifecycleValidatorTest PASS
- [x] Full coroutine-viz-core test suite PASS
