---
phase: 01-foundation-production-readiness
reviewed: 2026-06-12T12:00:00Z
depth: standard
files_reviewed: 12
files_reviewed_list:
  - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt
  - backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/wrappers/VizScopeTerminalOrderingTest.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/checksystem/TimingAnalyzer.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/checksystem/TimingAnalyzerTest.kt
  - frontend/src/components/SessionDetails.test.tsx
  - frontend/src/components/SessionDetails.tsx
  - frontend/src/components/StructuredConcurrencyInfo.test.tsx
  - frontend/src/components/StructuredConcurrencyInfo.tsx
  - frontend/src/hooks/use-event-stream-debounce.test.ts
  - frontend/src/hooks/use-event-stream.ts
  - frontend/src/hooks/use-thread-activity.ts
  - frontend/src/lib/event-discriminator.test.ts
findings:
  critical: 2
  warning: 6
  info: 10
  total: 18
status: issues_found
---

# Phase 01: Code Review Report (Gap-Closure Plans 01-09 .. 01-12)

**Reviewed:** 2026-06-12T12:00:00Z
**Depth:** standard
**Files Reviewed:** 12
**Status:** issues_found

> Supersedes the previous 01-REVIEW.md (gap-closure 01-06/01-07/01-08 review of 2026-06-12T06:49:46Z).

## Summary

Reviewed the gap-closure changes from plans 01-09..01-12: terminal-event ordering in `VizScope.invokeOnCompletion`, ns-to-ms conversion in `TimingAnalyzer`, event-discriminator normalization tests, and session-page polish (debounced SSE invalidation, completion-aware scenario button, connection badge, failure-propagation copy).

The `TimingAnalyzer` rewrite and its tests are correct (integer ns/1_000_000 division, empty-input handling, suspension pairing — all branches traced). The VizScope reorder (JobStateChanged before terminal event) is sound for the single-handler path: `seq` is assigned via `AtomicLong` at event-construction time inside each `EventContext` factory, and both emissions happen sequentially inside one `invokeOnCompletion` lambda, so the terminal event always carries the highest per-coroutine seq. The discriminator tests match the real `lib/utils.ts` implementation, and the `StructuredConcurrencyInfo` copy change matches its tests.

However, the "polling storm" fix introduces two live-mode freshness regressions. First, `useThreadActivity` polling is disabled during streaming on the explicit premise that "SSE-triggered cache invalidations handle refreshes" — but no code anywhere invalidates the `['thread-activity', sessionId]` query key (verified by repo-wide grep of all `invalidateQueries` call sites; the SSE hook only invalidates `['sessions', sessionId]`, and the only other consumer of that key, `ThreadLanesView`, is not mounted anywhere). The Threads tab is therefore frozen for the entire live session. Second, both new debounces are pure trailing-edge with no max-wait, so a sustained event stream starves the session-snapshot refresh indefinitely — the removed code guaranteed a 500ms periodic refresh; the new code can refresh never while events keep flowing.

## Critical Issues

### CR-01: Threads tab data freezes during live streaming — the invalidation premise is false

**File:** `frontend/src/hooks/use-thread-activity.ts:30` and `frontend/src/components/SessionDetails.tsx:46-48`
**Issue:** `useThreadActivity(sessionId, streamEnabled)` sets `refetchInterval: false` when live, justified by the comment "SSE-triggered cache invalidations handle refreshes." That invalidation never happens. The only SSE-driven invalidation is in `use-event-stream.ts:98`:
```ts
queryClient.invalidateQueries({ queryKey: ['sessions', sessionId] })
```
This does not match `['thread-activity', sessionId]` (React Query prefix matching). A repo-wide search of `invalidateQueries` call sites confirms nothing ever invalidates `thread-activity`. Since scenario pages auto-enable streaming (`SessionDetails.tsx:107-111`), the Threads tab (`ThreadTimeline`) fetches once and then shows stale data for the entire live session — a regression versus the previous unconditional 2s poll, on exactly the view this phase's UAT polish targeted.
**Fix:** Invalidate the thread-activity key in the same debounced block in `use-event-stream.ts`:
```ts
invalidationTimerRef.current = setTimeout(() => {
  invalidationTimerRef.current = null
  queryClient.invalidateQueries({ queryKey: ['sessions', sessionId] })
  queryClient.invalidateQueries({ queryKey: ['thread-activity', sessionId] })
}, INVALIDATION_DEBOUNCE_MS)
```
(Alternatively keep a slow fallback poll: `refetchInterval: isLive ? 5000 : 2000`.)

### CR-02: Trailing-edge debounce with no max-wait starves session refresh under sustained event streams

**File:** `frontend/src/hooks/use-event-stream.ts:93-99` and `frontend/src/components/SessionDetails.tsx:87-104`
**Issue:** Both new debounces reset their timer on every incoming event and fire only on the trailing edge. If SSE events keep arriving with gaps shorter than the window (400ms in the hook, 500ms in the component) — realistic for scenarios that loop `vizDelay` at sub-400ms cadence and continuously emit `CoroutineSuspended`/`CoroutineResumed` — the timer is perpetually reset and `invalidateQueries`/`refetch` never fire until the stream goes quiet. The session header counts, coroutine tree, and the new completion-aware scenario button (all derived from the REST `session` snapshot) freeze for the duration of exactly the long-running scenarios this tool exists to visualize live. The removed `setInterval` guaranteed a refresh at least every 500ms while streaming; the replacement guarantees nothing.
**Fix:** Add a max-wait cap (debounce with maxWait) or use leading-edge throttling (fire immediately, then at most once per window), e.g.:
```ts
const firstEventAtRef = useRef<number | null>(null)
// in the event handler:
if (firstEventAtRef.current === null) firstEventAtRef.current = Date.now()
if (Date.now() - firstEventAtRef.current >= MAX_WAIT_MS) {
  firstEventAtRef.current = null
  fireInvalidationNow()
} else {
  resetTrailingTimer()
}
```

## Warnings

### WR-01: VizScope emits JobStateChanged with hardcoded flags describing an impossible Job state

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:201-212, 224-235`
**Issue:** The failure branch emits `isActive = false, isCompleted = false, isCancelled = false`; the cancellation branch emits `isCompleted = false`. Inside `invokeOnCompletion` the job is by definition completed (`job.isCompleted == true`), and in kotlinx.coroutines a failed job reports `isCancelled == true`. The emitted triple (false/false/false) is a state no kotlinx `Job` can occupy. Downstream, `SessionDetails.jobStates` keeps the latest `JobStateChanged` per `jobId`, so the Jobs panel renders an incoherent final state — a semantic-correctness defect in a tool whose purpose is teaching coroutine semantics.
**Fix:**
```kotlin
session.send(
    ctx.jobStateChanged(
        isActive = job.isActive,
        isCompleted = job.isCompleted,
        isCancelled = job.isCancelled,
        childrenCount = job.children.count(),
    ),
)
```

### WR-02: Duplicated, divergent refresh machinery for the same query

**File:** `frontend/src/components/SessionDetails.tsx:87-104` and `frontend/src/hooks/use-event-stream.ts:93-99`
**Issue:** Two independent debounced mechanisms refresh the same `['sessions', sessionId]` data: the hook's 400ms `invalidateQueries` and the component's 500ms `refetch()` driven by `liveEvents.length`. A single burst produces up to two network refetches at different times, and the two windows (400 vs 500) must be co-maintained. The component comment claims the old double mechanism (debounce + interval) was removed — the replacement reintroduced a different double mechanism.
**Fix:** Delete the component-level debounce effect and rely on the hook's invalidation (the `['sessions', sessionId]` prefix match covers both `useSession` and `useSessionEvents`).

### WR-03: vizAsync terminal branches lack the JobStateChanged parity that vizLaunch has

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:347-369`
**Issue:** `vizLaunch`'s failure/cancellation branches emit a final `JobStateChanged` before the terminal event; `vizAsync`'s branches (annotated "FIX-03 parity for vizAsync") emit only the terminal event. Coroutines launched via `vizAsync` therefore never record their final job state, leaving the Jobs panel with a stale last-known (active) state for failed/cancelled deferreds. The parity claimed in the comment is only partial.
**Fix:** Mirror the `vizLaunch` pattern in `deferred.invokeOnCompletion`: emit `ctx.jobStateChanged(...)` (with real `deferred.isActive/isCompleted/isCancelled` flags per WR-01) before `coroutineFailed`/`coroutineCancelled`.

### WR-04: `as any` mutation plus inconsistent fallback `kind` in the SSE handler

**File:** `frontend/src/hooks/use-event-stream.ts:87`
**Issue:** `(event as any).kind = eventType as VizEventKind` violates the project convention (strict mode, "no `any` where avoidable"). Worse, when the fallback triggers it assigns the raw SSE event name as `kind` — for lifecycle events registered under PascalCase listeners that yields `'CoroutineCreated'`, while the canonical kind produced by `normalizeEvent` is `'coroutine.created'`. Consumers filtering on canonical kebab-case kinds would silently miss these events.
**Fix:** Route the fallback through the existing mapper and drop `any`:
```ts
if (!event.kind) {
  (event as { kind: VizEventKind }).kind = eventTypeToKind(eventType)
}
```

### WR-05: Live-stream toggle is broken on scenario pages — the auto-enable effect fights the user

**File:** `frontend/src/components/SessionDetails.tsx:107-111, 204-209`
**Issue:** The auto-enable effect runs `if (hasScenario && !streamEnabled) setStreamEnabled(true)` on every render where streaming is off. When the user clicks "Live Stream Active" to disable it, the handler calls `clearEvents()` then `setStreamEnabled(false)` — and the effect immediately flips it back to `true`. Net result on any scenario page: the toggle cannot turn streaming off, and clicking it silently destroys the accumulated live event list. Pre-existing logic, but this change set reworked the surrounding button block and the new completion-aware UX makes the control more prominent.
**Fix:** Auto-enable once per mount:
```ts
const autoEnabledRef = useRef(false)
useEffect(() => {
  if (hasScenario && !autoEnabledRef.current) {
    autoEnabledRef.current = true
    setStreamEnabled(true)
  }
}, [hasScenario])
```

### WR-06: `as unknown as` double cast bypasses type checking at the ThreadTimeline boundary

**File:** `frontend/src/components/SessionDetails.tsx:362`
**Issue:** `threadActivity as unknown as import('@/types/api').ThreadActivity` force-casts `ThreadActivityResponse` to `ThreadActivity`. Any future divergence between the two shapes (the exact class of mismatch the discriminator work in this phase was fixing elsewhere) will compile cleanly and fail at runtime in the Threads tab.
**Fix:** Align `ThreadTimeline`'s prop type with `ThreadActivityResponse` (or add a typed mapping function) and delete the cast.

## Info

### IN-01: Duplicate `registerJob` call

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:135,141`
**Issue:** `session.snapshot.registerJob(currentJob, coroutineId)` is called twice in succession in the `vizLaunch` body.
**Fix:** Remove the second call (line 141) and its stray comment.

### IN-02: Mixed `session.send()` / `session.sent()` usage

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:149,171,302,323,328,406`
**Issue:** `sent()` is a pure delegating alias for `send()` (VizSession.kt:113-114). Mixing both names in one file implies a semantic difference that does not exist.
**Fix:** Replace all `session.sent(...)` with `session.send(...)`; deprecate the alias.

### IN-03: JobStatusMonitor can still violate the terminal-ordering invariant when enabled

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/JobStatusMonitor.kt:40-72`
**Issue:** The poller emits `JobStateChanged` whenever a tracked job's children count changes — including the change caused by completion — before its `isCompleted` cleanup check in the same iteration. With monitoring enabled, a `JobStateChanged` can land after the coroutine's terminal event, retriggering the exact `NoEventsAfterTerminalRule` finding this phase fixed. Currently latent (`enableJobMonitoring()` has no production callers) but undocumented.
**Fix:** In `pollAllJobs`, skip the emit when `tracked.job.isCompleted` (cleanup only), or document the constraint at `enableJobMonitoring()`.

### IN-04: `delay(200)` in ordering tests provides zero real time under `runTest`

**File:** `backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/wrappers/VizScopeTerminalOrderingTest.kt:54,111,170`
**Issue:** The comment "Give invokeOnCompletion handlers time to fire" is misleading: under `runTest` virtual time, `delay(200)` advances instantly. The tests pass only because handlers run synchronously during job completion before `join()` resumes. If event emission ever gains an async hop, these tests become flaky with no protective margin despite the comment claiming one.
**Fix:** Remove the delays (they assert nothing) or replace with a real synchronization point, and correct the comments.

### IN-05: Untracked `setTimeout(refetch, 500)` in handleRunScenario

**File:** `frontend/src/components/SessionDetails.tsx:119`
**Issue:** The timer is never cleared, so `refetch()` fires after unmount/navigation; it is also redundant with `useRunScenario`'s `onSuccess` invalidation of `['sessions', sessionId]`.
**Fix:** Delete the `setTimeout`; rely on the mutation's invalidation.

### IN-06: "Reset" and "Clear" buttons are identical destructive actions

**File:** `frontend/src/components/SessionDetails.tsx:293-312`
**Issue:** Both buttons call `handleReset`, which deletes the session and navigates away. "Clear" implies clearing events, not deleting the session — users will lose sessions unexpectedly.
**Fix:** Remove the Clear button or wire it to `clearEvents()` only.

### IN-07: Discriminator test claims "All 17 backend event type values" — the backend has 32+

**File:** `frontend/src/lib/event-discriminator.test.ts:14-16`
**Issue:** The backend defines 32+ serializable event types (flow, channel, sync, etc.). Those rely on `eventTypeToKind`'s identity fallback (`utils.ts:38`), which is untested here; the "all" claim overstates coverage.
**Fix:** Reword to "the 17 coroutine/job/deferred/dispatcher types" and add one fallback-path assertion (e.g. `eventTypeToKind('FlowCreated') === 'FlowCreated'`).

### IN-08: Tautological `useThreadActivity` assertion in the debounce test file

**File:** `frontend/src/hooks/use-event-stream-debounce.test.ts:147-154`
**Issue:** Asserting `typeof mod.useThreadActivity === 'function'` and `length >= 1` cannot fail under any meaningful regression. Real behavior lives in `use-thread-activity.test.ts` (which exists); this block adds maintenance noise with zero protection.
**Fix:** Delete the describe block.

### IN-09: Connection badge shows "Connecting..." forever on permanent failure

**File:** `frontend/src/components/SessionDetails.tsx:221-226` and `frontend/src/hooks/use-event-stream.ts:39-42`
**Issue:** `onerror` sets `error = 'Connection lost'`, but `SessionDetails` never destructures `error`, and the badge maps every `!isConnected` state to "Connecting...". For a deleted session or downed backend the UI claims it is connecting indefinitely.
**Fix:** Surface the hook's `error` and render a "Disconnected" state when it is non-null.

### IN-10: `useEventStream` does not reset events when `sessionId` changes

**File:** `frontend/src/hooks/use-event-stream.ts:23-134`
**Issue:** The effect tears down and recreates the EventSource on `sessionId` change but keeps the `events` state, so events from the previous session leak into the new one if the hook survives a session switch (currently masked by remount-per-route, but the hook's contract is unsafe).
**Fix:** Reset events at the start of the effect (or in cleanup) when `sessionId` changes.

---

_Reviewed: 2026-06-12_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
