---
title: SharedFlow scenario event log violates structured concurrency — parent CoroutineCompleted recorded before a child's terminal event
area: backend (coroutine-viz-core instrumentation wrappers)
severity: medium
status: fixed
fixed: 2026-06-22
found: 2026-06-22
phase: 2 (instrumentation / event ordering)
requirement: ADR-012 (validation: ParentNotCompletedBeforeChildren) / structured-concurrency invariant
discovered_during: Phase-3 browser UAT deep-dive (Gallery Flow family, /api/scenarios/flow/sharedflow)
ledger_id: F8
---

## Symptom (reproduced live, DB mode)
Validating a `flow/sharedflow` run reports exactly one failure:

```
ParentNotCompletedBeforeChildren: Parent coroutine-2 completed before child coroutine-9
```

The recorded terminal-event order (session `flow-sharedflow-1782131119668`):

```
seq 3    CoroutineCreated    coroutine-2  (sharedflow-demo)        parent, root
seq 15   CoroutineBodyCompleted coroutine-2                        <-- BEFORE children created (!)
seq 16   CoroutineCreated    coroutine-10 (publisher)    parent=coroutine-2
seq 19   CoroutineCreated    coroutine-9  (subscriber-2) parent=coroutine-2
seq 25   CoroutineCreated    coroutine-8  (subscriber-1) parent=coroutine-2
...
seq 103  CoroutineCancelled  coroutine-8  (subscriber-1)
seq 104  CoroutineCompleted  coroutine-10 (publisher)
seq 105  CoroutineCompleted  coroutine-2  (sharedflow-demo)         <-- parent terminal
seq 107  CoroutineCancelled  coroutine-9  (subscriber-2)            <-- child terminal AFTER parent
```

Two ordering inversions in the recorded stream:
1. Parent `CoroutineBodyCompleted` (seq 15) precedes its children's `CoroutineCreated` (seq 16/19/25).
2. Parent `CoroutineCompleted` (seq 105) precedes child `subscriber-2` `CoroutineCancelled` (seq 107).

## Root cause — instrumentation, NOT scenario logic
The scenario (`scenarios/FlowScenarios.kt::sharedFlowDemo`) is correct structured concurrency:
`scope.vizLaunch("sharedflow-demo") { val sub1 = vizLaunch("subscriber-1"){…}; val sub2 = vizLaunch("subscriber-2"){…}; vizLaunch("publisher"){ … sub1.cancel(); sub2.cancel() } }.join()`.
Children are launched in the parent block's scope and the parent is `.join()`ed, so the parent Job
genuinely cannot complete until all children finish. The violation therefore lives in the **Viz
instrumentation wrappers' terminal-event emission/sequencing** (VizScope / vizLaunch): during
cancel-based teardown the parent's `CoroutineCompleted` event is emitted/sequenced ahead of a child's
`CoroutineCancelled` event, producing an event stream that contradicts the real Job topology. The
seq-15-before-children-created inversion is the same class of emission-ordering race.

## Why existing tests didn't catch it
`VizScopeTerminalOrderingTest` / `VizScopeAsyncTerminalOrderingTest` cover terminal ordering for
launch/async completion, but not the **hot-flow + explicit `child.cancel()` teardown** path that
sharedflow exercises. `stateflow` does NOT trip it (its collectors are cancelled, but the resulting
event order happened to stay consistent — created 4 / completed 2 / cancelled 2, 0 validation
failures), so the bug is specific to this cancel-during-teardown ordering.

## Impact
- The validation engine correctly flags it, so any user validating a sharedflow session sees a real
  (if confusingly-attributed) failure.
- More broadly: a tree/timeline rendered from this stream can momentarily show a parent as completed
  while a child is still live — a correctness wrinkle in the visualization's structured-concurrency story.

## Candidate fixes
- In the Viz wrappers, gate the parent coroutine's terminal event emission on completion of all child
  terminal events (mirror the real Job parent-after-children ordering) — extend the terminal-ordering
  invariant the existing tests assert to the cancel-based teardown path.
- Add a regression in coroutine-viz-core: parent that launches children then has them cancelled must
  emit its own terminal event AFTER all children's terminal events.
- Separately: the parent `BodyCompleted`-before-children-`Created` inversion suggests child
  `CoroutineCreated` events should be sequenced at launch time (synchronously within the parent body),
  not on the child's first dispatch.

## Not a defect (verified same session)
Event counts are otherwise balanced and the run is functionally correct (publisher emits 5, both
subscribers collect, both cancelled on teardown). Ordering check (monotonic seq, no dups/reorder of
the seq counter itself) passes — the issue is the *causal* ordering of terminal events, which only the
structured-concurrency validator catches.

## ✅ RESOLUTION (2026-06-22) — solution A + B1
Both anomalies fixed in `coroutine-viz-core/wrappers/VizScope.kt::vizLaunch`:
- **A (Created at launch):** the coroutine is now launched with `CoroutineStart.LAZY`; `CoroutineCreated`
  is emitted synchronously *before* `job.start()`, so it precedes the launching coroutine's
  `CoroutineBodyCompleted` and siblings order by launch rather than by first-dispatch.
- **B1 (terminal barrier):** each viz coroutine registers a `CompletableDeferred` "terminalEmitted"
  signal at launch (+ a parent->children index). A coroutine emits its terminal event only after all
  its children have emitted theirs. Fast path (children already finished — the common case, e.g. a
  joined child) emits synchronously in `invokeOnCompletion`, preserving prior timing and the existing
  terminal-ordering guarantees; racy path (a child's terminal not yet emitted — cancel-based teardown
  of fire-and-forget children) defers to an awaiter on the session scope that awaits each child's
  signal first. The awaiter runs on `session.sessionScope` (NOT a tracked child), so it doesn't affect
  structured concurrency or childrenCount.

Regression: `VizScopeFireForgetOrderingTest` reproduces the sharedflow shape (fire-and-forget children
cancelled by a sibling at teardown) and asserts (A) every child Created precedes the parent
BodyCompleted and (B1) the parent terminal seq exceeds every child terminal seq. Existing
`VizScopeTerminalOrderingTest` / `VizScopeAsyncTerminalOrderingTest` / `VizScopeCancellationTest` still
pass; full backend suite green.

Follow-up (not in this change): `vizAsync` uses the same `invokeOnCompletion` pattern and could get the
same A+B1 treatment for full consistency; left as-is here to keep the change focused (vizAsync children
are simply not awaited by the barrier, preserving current behavior — no deadlock).
