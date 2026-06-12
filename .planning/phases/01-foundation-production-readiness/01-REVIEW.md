---
phase: 01-foundation-production-readiness
reviewed: 2026-06-12T12:44:26Z
depth: standard
files_reviewed: 58
files_reviewed_list:
  - backend/build.gradle.kts
  - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/events/flow/FlowBackpressure.kt
  - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/events/flow/FlowOperatorApplied.kt
  - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/events/flow/FlowValueFiltered.kt
  - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/events/flow/FlowValueTransformed.kt
  - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/events/flow/SharedFlowEmission.kt
  - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/events/flow/SharedFlowSubscription.kt
  - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/events/flow/StateFlowValueChanged.kt
  - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/VizSession.kt
  - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt
  - backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/wrappers/VizScopeCancellationTest.kt
  - backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/wrappers/VizScopeTerminalOrderingTest.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/Application.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/checksystem/TimingAnalyzer.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/MetricsWiring.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/Monitoring.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/HealthRoutes.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/ScenarioRunnerRoutes.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/scenarios/ScenarioRunner.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/Serialization.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/VizEventSerializersModule.kt
  - backend/src/main/resources/application.yaml
  - backend/src/main/resources/openapi/documentation.yaml
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/BoundedStoreWiringTest.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/CorsConfigTest.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/events/VizEventSerializersModuleTest.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/ForkDeletionTest.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/checksystem/TimingAnalyzerTest.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/MetricsWiringTest.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/BoundedStoreRegressionTest.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/HealthRoutesTest.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/SessionEventsIntegrationTest.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/SseStreamTest.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/scenarios/CancellationScenarioRegressionTest.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/wrappers/VizScopeCompletionHandlerTest.kt
  - frontend/src/components/DispatcherOverview.tsx
  - frontend/src/components/SessionDetails.test.tsx
  - frontend/src/components/SessionDetails.tsx
  - frontend/src/components/StructuredConcurrencyInfo.test.tsx
  - frontend/src/components/StructuredConcurrencyInfo.tsx
  - frontend/src/components/validation/TimingReportView.tsx
  - frontend/src/components/validation/ValidationPanel.test.tsx
  - frontend/src/components/validation/ValidationPanel.tsx
  - frontend/src/components/validation/ValidationResultCard.tsx
  - frontend/src/hooks/use-event-stream-debounce.test.ts
  - frontend/src/hooks/use-event-stream-retry.test.ts
  - frontend/src/hooks/use-event-stream.ts
  - frontend/src/hooks/use-thread-activity.test.ts
  - frontend/src/hooks/use-thread-activity.ts
  - frontend/src/hooks/use-validation.ts
  - frontend/src/lib/api-client.ts
  - frontend/src/lib/event-discriminator.test.ts
  - frontend/src/lib/thread-lanes.test.ts
  - frontend/src/lib/thread-lanes.ts
  - frontend/src/mocks/handlers.ts
  - frontend/src/mocks/mock-data.ts
  - frontend/src/types/api.ts
findings:
  critical: 0
  warning: 2
  info: 8
  total: 10
  resolved: 2
status: triaged
---

# Phase 01: Code Review Report

**Reviewed:** 2026-06-12T12:44:26Z (delta review after gap-closure plans 01-14 and 01-15; original full-phase review 2026-06-12T11:20:32Z)
**Depth:** standard
**Files Reviewed:** 58
**Status:** issues_found

> Supersedes the previous 01-REVIEW.md content. Cumulative full-phase review covering all plans through 01-15. Counts in the frontmatter reflect OPEN findings; resolved findings are retained below with resolution notes.

## Summary

Delta review of gap-closure plans 01-14 (threads-tab wire-shape alignment, commits 329edb9..a133619) and 01-15 (SSE first-connect flush + bounded retry + seq replay dedup, commits b5ef92c..2dbe577), layered on the earlier full-phase review.

The two headline gap fixes are genuine: **CR-02 is resolved** — `api-client.getThreadActivity` is now typed to the real wire shape (`ThreadActivity`), the lane/dispatcher view model is derived client-side by a well-tested pure adapter (`buildThreadLanes`), the `as unknown as` double cast is gone from `SessionDetails`, and the epoch-vs-nanoTime comparison was replaced with the `endNanos == null` criterion. **WR-01 is resolved client-side** — the SSE hook keeps a seq high-water mark and drops replayed history on reconnect, with solid fake-timer tests for the backoff schedule and dedup. The 01-15 server change (comment-frame flush, subscribe-before-snapshot, CancellationException rethrow) is also an improvement and is regression-tested.

However, the gap code introduces four new warnings: the client-side high-water-mark dedup inherits the backend's seq/append ordering race and can permanently drop out-of-order events (WR-13); the new unbounded `liveBuffer` channel removes backpressure for slow SSE clients (WR-14); `useThreadLanesByDispatcher` hard-codes `isLive=false`, so `DispatcherOverview` re-arms the 2s poll on the shared query key and silently defeats the 01-13 live-mode slow-poll design whenever the Threads tab is open (WR-15); and `useEventStream` resets its seq watermark without clearing `events` on sessionId change, so param-only navigation mixes two sessions' events in one list (WR-16). Three new info items cover MSW `/events` mock drift, dynamically-constructed Tailwind classes in the now-live `DispatcherOverview`, and a duplicate-ASSIGNED edge case in `buildThreadLanes`.

**CR-01 (17-of-66 SSE event-kind subscription) was out of gap scope and is verified still open** — the hand-maintained listener list in `use-event-stream.ts` is unchanged.

## Critical Issues

### CR-01: Live SSE stream silently drops 49 of 66 event kinds

**Status:** OPEN — re-verified 2026-06-12T12:44Z against current code (out of 01-14/01-15 gap scope; listener list unchanged, now at `use-event-stream.ts:123-152`).
**File:** `frontend/src/hooks/use-event-stream.ts:123-152`
**Issue:** The SSE route (`SessionRoutes.kt:247-252`) names each SSE event after its `kind`, and the backend emits 66 registered kinds (`VizEventSerializersModule.kt`). `useEventStream` registers listeners for only 17 PascalCase kinds (coroutine/job/dispatcher/deferred) plus 9 legacy kebab-case names. Every `Channel*`, `Flow*`, `SharedFlow*`, `StateFlow*`, `Mutex*`, `Semaphore*`, `Actor*`, `Select*`, `Deadlock*`, `AntiPatternDetected`, and `WaitingForChildren` event is dropped by the browser because no listener exists for that event name. In live mode `SessionDetails` renders `allEvents = liveEvents` (`SessionDetails.tsx:70`), so the Events tab and the `OrderProcessingView`/`RegistrationFlowView` pipelines show an incomplete stream, while the same session's stored-events view shows the full set. `types/api.ts` already defines all of these kinds and category sets (`CHANNEL_EVENT_KINDS`, `FLOW_EVENT_KINDS`, `SYNC_EVENT_KINDS`, ...), so the gap is purely in the listener list. The `WaitingForChildren` omission also undercuts the WAITING_FOR_CHILDREN teaching feature advertised in `StructuredConcurrencyInfo`.
**Fix:** Derive the listener list from one shared constant instead of a hand-maintained inline array:
```ts
import { CHANNEL_EVENT_KINDS, FLOW_EVENT_KINDS, SYNC_EVENT_KINDS, JOB_EVENT_KINDS, ACTOR_EVENT_KINDS, SELECT_EVENT_KINDS } from '@/types/api'

const eventTypes = [
  ...COROUTINE_EVENT_KINDS,          // new shared constant for the 8 lifecycle kinds
  ...JOB_EVENT_KINDS, ...CHANNEL_EVENT_KINDS, ...FLOW_EVENT_KINDS,
  ...SYNC_EVENT_KINDS, ...ACTOR_EVENT_KINDS, ...SELECT_EVENT_KINDS,
  'ThreadAssigned', 'DispatcherSelected',
  'DeferredValueAvailable', 'DeferredAwaitStarted', 'DeferredAwaitCompleted',
  'AntiPatternDetected',
]
```
Add a regression test asserting the listener list covers every member of the `VizEventKind` union / kind sets in `types/api.ts`.

### CR-02: Thread-activity client type does not match the wire format — three hooks and ThreadLanesView are permanently broken

**Status:** RESOLVED by 01-14 (commits e71f476 `buildThreadLanes` adapter, da338c1 client retype + hook rewrite, e5d7d24 integration tests). Verified 2026-06-12T12:44Z:
- `api-client.ts:137-139` now returns `Promise<ThreadActivity>` (the real `Map<threadId, ThreadEvent[]>` wire shape) with an explanatory comment.
- `useThreadLanesByDispatcher` / `useThreadUtilizationStats` / `useActiveCoroutinesPerThread` are rewritten on top of the pure adapter `buildThreadLanes` (`lib/thread-lanes.ts`), unit-tested with exact-value assertions against a wire-shape fixture (`thread-lanes.test.ts`, `use-thread-activity.test.ts`).
- The `as unknown as ThreadActivity` double cast is gone from `SessionDetails.tsx` (the Threads tab now passes the typed wire shape directly to `ThreadTimeline`, integration-tested in `SessionDetails.test.tsx` "Threads tab wire shape (UAT gap 1)").
- The epoch-nanos vs `System.nanoTime()` comparison in `useActiveCoroutinesPerThread` was replaced with the `seg.endNanos == null` open-segment criterion.
- The MSW `/threads` mock (`mock-data.ts:generateMockThreadActivityWire`, `handlers.ts:190-195`) now serves the wire shape.
Residual: the OpenAPI spec still documents `/threads` as a bare array (tracked under WR-08, still open), and the lane hooks ignore the live flag (new WR-15).

<details>
<summary>Original finding (resolved)</summary>

**File:** `frontend/src/lib/api-client.ts:134-136`, `frontend/src/hooks/use-thread-activity.ts:41-130`, `frontend/src/components/SessionDetails.tsx:408`
**Issue:** Backend `ProjectionService.getThreadActivity()` returns `Map<String, List<ThreadEvent>>`, served verbatim by `GET /api/sessions/{id}/threads`. The client typed it as `ThreadActivityResponse { threads, dispatcherInfo }`, a shape the backend never produces. `useThreadLanesByDispatcher`, `useThreadUtilizationStats`, and `useActiveCoroutinesPerThread` always returned empty maps/zero stats; `SessionDetails` papered over the wrong type with an `as unknown as` double cast; `useActiveCoroutinesPerThread` compared epoch nanos against `System.nanoTime()` values.
</details>

## Warnings

### WR-01: EventSource auto-reconnect duplicates the entire event history in the live view

**Status:** RESOLVED (client-side) by 01-15 (commit 6c74c58). Verified 2026-06-12T12:44Z: `use-event-stream.ts:56-58, 171-177` keeps a `maxSeqRef` high-water mark and drops events with `seq <= maxSeqRef` before `setEvents` and before the invalidation debounce; regression-tested in `use-event-stream-retry.test.ts` ("does not duplicate replayed history after reconnect"). Residual notes: (1) the server still replays full history on every connection and ignores `Last-Event-ID` (`SessionRoutes.kt:217-224`) — wasted bandwidth on reconnect, but no longer a UI-correctness defect (performance is out of v1 review scope); (2) the watermark approach inherits the backend's seq-ordering race — see NEW finding WR-13.

<details>
<summary>Original finding (resolved)</summary>

**File:** `frontend/src/hooks/use-event-stream.ts:90-100` and `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt:209-214`
**Issue:** The SSE route replays all stored events on every new connection and never reads `Last-Event-ID`. The native `EventSource` auto-reconnects after transient errors, and the hook appended every received event with no dedup. One dropped connection mid-session duplicated the whole history in the UI.
</details>

### WR-02: SSE replay/dedup race can permanently drop events when seq order and store-append order diverge

**Status:** OPEN — re-verified 2026-06-12T12:44Z. The 01-15 rework kept the max-seq watermark (`SessionRoutes.kt:224-231`); subscribing before snapshotting fixes the *lost-window* problem but not this ordering race. The same defect class is now additionally present client-side (see NEW WR-13).
**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt:217-231`
**Issue:** `seq` is allocated at event construction (`nextSeq()` inside the EventContext factories), but `store.append` happens later in `VizSession.send`. With concurrent emitters, thread A can construct seq=10, thread B construct seq=11 and append it first; if the SSE handler snapshots the store at that instant, `lastReplayedSeq = 11`. When A's seq=10 event then arrives through the live buffer, the filter `event.seq > lastReplayedSeq` discards it even though it was never replayed — the event is lost for that client. Scenario coroutines run in parallel on `Dispatchers.Default`, so this interleaving is reachable, not theoretical.
**Fix:** Deduplicate by identity instead of a max-seq watermark: collect the set of seqs in the snapshot and filter the live buffer with `event.seq !in replayedSeqs` (the set can be discarded once the buffer drains past the snapshot window). Alternatively, make seq allocation and store append atomic under one lock in `VizSession.send`.

### WR-03: Per-session `events.buffer.size` gauge is never deregistered — meter and session leak

**Status:** OPEN — not in 01-14/01-15 delta scope; unchanged since 2026-06-12T11:20Z review.
**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/MetricsWiring.kt:51-68, 74`
**Issue:** `onSessionCreated` registers a `Gauge` tagged with `sessionId` whose value lambda strongly captures `session`. Nothing removes the meter when the session is closed (`DELETE /api/sessions/{id}` → `SessionManager.closeSession`). Every closed session stays strongly referenced by the registry (its EventStore included), `/metrics` accumulates one stale `events_buffer_size{sessionId=...}` series per session ever created, and the gauge keeps reporting the dead session's last buffer size forever. Scenario routes auto-create a session per run (`auto-<millis>`), so series grow with every scenario execution. Also `scenarioDurationTimerRef` (line 74) is a top-level mutable `var` — `wireMetrics` runs once per Application instance (multiple times across tests), so the ref silently points at the most recent registry.
**Fix:** Keep a `sessionId -> Meter.Id` map and call `registry.remove(meterId)` from a `SessionManager.onSessionClosed` callback, or register a single multi-gauge backed by `SessionManager.listSessions()`. Pass the scenario timer explicitly (e.g., via an Application attribute) instead of a global `var`.

### WR-04: Live-stream toggle can never be switched off on scenario pages (effect fight-loop that also wipes events)

**Status:** OPEN — re-verified 2026-06-12T12:44Z; auto-enable effect unchanged at `SessionDetails.tsx:152-157`, toggle handler at `:250-255`.
**File:** `frontend/src/components/SessionDetails.tsx:152-157, 250-255`
**Issue:** The auto-enable effect runs on every `streamEnabled` change: `if (hasScenario && !streamEnabled) setStreamEnabled(true)`. When the user clicks "Live Stream Active" to disable, the handler calls `clearEvents()` and sets `streamEnabled=false`; the effect immediately re-enables it. Net result on scenario sessions: the stream re-opens instantly and the accumulated live events were just cleared — the user loses the event list and cannot disable streaming.
**Fix:** Auto-enable only once, e.g.:
```ts
const autoEnabledRef = useRef(false)
useEffect(() => {
  if (hasScenario && !autoEnabledRef.current) {
    autoEnabledRef.current = true
    setStreamEnabled(true)
  }
}, [hasScenario])
```

### WR-05: "Reset" and "Clear" buttons are duplicates — both delete the session

**Status:** OPEN — re-verified 2026-06-12T12:44Z; both buttons still invoke `handleReset` (`SessionDetails.tsx:339-358`).
**File:** `frontend/src/components/SessionDetails.tsx:339-358`
**Issue:** Both buttons invoke `handleReset`, which deletes the session and navigates away. A user clicking "Clear" (reasonably expecting to clear the event list) destroys the session. Two differently-labeled destructive controls with identical behavior is a logic error, not a style choice.
**Fix:** Wire "Clear" to `clearEvents()` (and optionally `refetch()`); keep "Reset" as the delete-and-navigate action — or remove one button.

### WR-06: Scenario error handlers swallow CancellationException and return stack traces to clients

**Status:** OPEN — not in 01-14/01-15 delta scope; unchanged since 2026-06-12T11:20Z review. (Note: the SSE route now rethrows `CancellationException` correctly after 01-15 — apply the same pattern here.)
**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/ScenarioRunnerRoutes.kt:293-304, 448-454`
**Issue:** (1) `runScenarioWithResponse` and the custom-scenario handler catch bare `Exception`, which includes `CancellationException`. Realistic scenarios block the request 15-35s (`job.join()` in `ScenarioRunner`); if the client disconnects, Ktor cancels the handler, the catch traps the cancellation and attempts `respond(...)` inside a cancelled coroutine — breaking cooperative cancellation (and the project's own structured-concurrency convention). (2) The custom-scenario 500 response includes `e.stackTraceToString()` in `errors` (line 301), disclosing internal class names, file paths, and framework internals to any unauthenticated caller.
**Fix:**
```kotlin
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    logger.error("Error running custom scenario", e)
    call.respond(HttpStatusCode.InternalServerError,
        ScenarioExecutionResponse(success = false, sessionId = "",
            message = "Error executing scenario", errors = listOf(e.message ?: "internal error")))
}
```
Keep stack traces in server logs only. Apply the same `CancellationException` rethrow in `runScenarioWithResponse`.

### WR-07: getOrCreateSession silently substitutes a new session when the requested sessionId does not exist

**Status:** OPEN — not in 01-14/01-15 delta scope; unchanged since 2026-06-12T11:20Z review.
**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/ScenarioRunnerRoutes.kt:457-460`
**Issue:** `sessionId?.let { SessionManager.getSession(it) } ?: SessionManager.createSession("auto-...")` — if a client passes a stale/wrong `sessionId` (e.g., after `SessionDetails`'s Reset flow deletes a session), the scenario runs in a brand-new auto session instead of failing. A client streaming `/api/sessions/{oldId}/stream` sees nothing and gets no error signal (the substituted id is only in the response body). Also `"auto-${System.currentTimeMillis()}"` can collide when two scenario requests land in the same millisecond. Related: most basic-scenario responses claim "completed" while the scenario is still running (the launched job is not joined for nested/parallel/cancellation/deep-nesting/mixed/exception), and `coroutineCount`/`eventCount` are mid-run snapshots — only the nested route's message hints at the live stream.
**Fix:** Return 404 when an explicit `sessionId` is provided but not found; auto-create only when the parameter is absent. Use a UUID-style suffix for auto names. Reword non-joined scenario messages to "Scenario started" and document that counts are snapshots.

### WR-08: OpenAPI spec contradicts the implementation — and shared/api-types are generated from it

**Status:** OPEN — re-verified 2026-06-12T12:44Z: `/api/sessions/{id}/threads` is still documented as `type: array, items: ThreadEvent` (`documentation.yaml:444-452`) while both backend and (since 01-14) frontend treat the map shape as the source of truth. The 01-14 fix made drift #3 *more* important to close: the spec is now the only remaining artifact with the wrong shape.
**File:** `backend/src/main/resources/openapi/documentation.yaml:2083-2111, 1730-1732, 1796-1798, 1889-1891, 444-452`
**Issue:** Three provable drifts against code reviewed in this phase:
1. `TimingReport` (lines 2090-2111) documents all durations as **nanoseconds**; `TimingAnalyzer.kt` returns **milliseconds** (NANOS_PER_MILLI division, asserted by `TimingAnalyzerTest` and matched by the frontend `BackendTimingReport`, which is documented as milliseconds). The spec was not updated with the unit fix.
2. The `state` enums on `CoroutineNodeDto`, `HierarchyNode`, and `CoroutineTimeline` list `RUNNING` and omit `ACTIVE` and `WAITING_FOR_CHILDREN`; the backend enum (`models/CoroutineState.kt`) is `CREATED, ACTIVE, SUSPENDED, WAITING_FOR_CHILDREN, COMPLETED, CANCELLED, FAILED` — `RUNNING` does not exist. The frontend's local `CoroutineState` enum already uses ACTIVE/WAITING_FOR_CHILDREN, confirming the spec is the odd one out.
3. `/api/sessions/{id}/threads` is documented as `type: array, items: ThreadEvent`; the endpoint returns a map keyed by threadId (the wire shape the frontend now consumes after 01-14).
Per CLAUDE.md, `shared/api-types` is generated from this spec, so each drift propagates into the shared TypeScript types. (Minor: the `VizEvent` description still says "32+ event types" vs the actual 66.)
**Fix:** Update `TimingReport` descriptions to milliseconds, correct the state enums, and document `/threads` as `additionalProperties: { type: array, items: ThreadEvent }`. Regenerate shared/api-types afterwards.

### WR-09: api-client advertises pagination/filtering the backend ignores; two methods mis-shape responses

**Status:** OPEN — re-verified 2026-06-12T12:44Z; `getSessionEventsPaginated` still present (`api-client.ts:75-95`), dead option params on `getSessionEvents` (`:58-66`), `getValidationRules` still mis-typed (`:157-159`).
**File:** `frontend/src/lib/api-client.ts:58-95, 157-159`
**Issue:**
1. `getSessionEvents(sessionId, {sinceStep, limit, filter})` sends query params that `GET /api/sessions/{id}/events` never reads (`SessionRoutes.kt:94-109` returns `store.all()` unconditionally). Callers believe they are limiting/filtering but always receive the full event list.
2. `getSessionEventsPaginated` hits the same endpoint and does `{ ...response, events: normalizeEvents(response.events || []) }` — but the endpoint returns a bare JSON array, so `response.events` is always undefined, `events` is always `[]`, `hasMore`/`total` are undefined, and the array spread leaks numeric keys into the result. The method can never work against this backend (only the MSW mock in `mocks/handlers.ts` satisfies it — see new IN-11).
3. `getValidationRules()` declares `Promise<{ rules: Array<{id, name, description}> }>`, but `/api/validate/rules` returns a bare array of `{name, description}` (no wrapper, no `id` — verified in `ValidationRoutes.kt:64-106`).
**Fix:** Delete `getSessionEventsPaginated` and the dead option params (or implement them server-side), and retype `getValidationRules` as `Promise<Array<{ name: string; description: string }>>`.

### WR-10: vizLaunch emits lifecycle events from inside the coroutine body — cancel-before-start produces terminal events with no Created/Started

**Status:** OPEN — not in 01-14/01-15 delta scope; unchanged since 2026-06-12T11:20Z review.
**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:131-172, 179-239`
**Issue:** `CoroutineCreated`/`CoroutineStarted` are emitted as the first statements of the launched coroutine body, while `invokeOnCompletion` emits terminal events unconditionally. If a job is cancelled before its first dispatch (scope cancelled right after `vizLaunch` returns, or a sibling fails first — exactly what the exception scenario does to slower siblings), the handler fires with a `CancellationException` and emits `JobStateChanged` + `CoroutineCancelled` for a coroutineId that has no `CoroutineCreated`. That orphan terminal event violates the app's own `CreatedHasStarted` lifecycle validation — the same self-flagging class of bug the FIX-03/terminal-ordering work was meant to eliminate.
**Fix:** Emit `CoroutineCreated` synchronously in `vizLaunch` before `targetScope.launch` (ids and EventContext already exist there), keeping `CoroutineStarted`/`ThreadAssigned` inside the body; or track a "created emitted" flag on the EventContext and skip terminal emission in the handler when it never ran.

### WR-11: /api/health test asserts strict 200/UP while sibling tests document the same code path flips to 503 under shared-JVM heap pressure

**Status:** OPEN — not in 01-14/01-15 delta scope; unchanged since 2026-06-12T11:20Z review.
**File:** `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/HealthRoutesTest.kt:129-149`
**Issue:** The file's own doc comment (lines 45-49) explains `/health` verdicts depend on suite ordering and GC timing, and four tests use `assertHealthReachable` accepting 200 or 503. But `GET api health returns 200 with version and components` asserts `HttpStatusCode.OK` and `"UP"` strictly against the identical `respondHealth()` implementation — the exact nondeterminism the other tests were hardened against. Under heap pressure this test fails flakily.
**Fix:** Use the same `assertHealthReachable(response.status)` + verdict-consistency pattern as the `/health` alias test.

### WR-12: VizSession.send is not atomic and its timing callback has a check-then-act race

**Status:** OPEN — not in 01-14/01-15 delta scope; unchanged since 2026-06-12T11:20Z review. (Root enabler of WR-02 and new WR-13.)
**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/VizSession.kt:88-97`
**Issue:** (1) `store.append` / `applier.apply` / `eventBus.send` run without a common lock, so concurrent senders interleave — this is the root enabler of the WR-02 SSE dedup race and means `store.all()` is append-ordered, not seq-ordered. (2) `onEventProcessed` is read twice: if the callback is assigned between the first null check (`startNanos = ... else 0L`) and the invocation, the recorded duration is `System.nanoTime() - 0` — a garbage multi-year sample that wrecks the `event.processing.duration` histogram. `MetricsWiring` assigns these callbacks after session creation while scenario sends may already be in flight.
**Fix:** Capture once: `val cb = onEventProcessed; val start = if (cb != null) System.nanoTime() else 0L; ...; cb?.invoke(System.nanoTime() - start)`. Synchronize seq allocation + append if strict store ordering is required (see WR-02).

### WR-13 (NEW, 01-15): Client-side seq high-water-mark dedup drops legitimately out-of-order events

**File:** `frontend/src/hooks/use-event-stream.ts:171-177`
**Issue:** The new replay-dedup guard assumes events arrive in monotonically increasing `seq` order: `if (seq <= maxSeqRef.current) return; maxSeqRef.current = seq`. That assumption is false on this backend: `seq` is allocated at event construction but `store.append` happens later without a common lock (WR-12), so `store.all()` — which the SSE route replays in order (`SessionRoutes.kt:217-221`) — is append-ordered, not seq-ordered, and live-bus delivery can interleave the same way. If seq 11 reaches the hook before seq 10 (reachable with parallel scenario emitters), the watermark jumps to 11 and seq 10 is **silently and permanently dropped from the live view** — on first connect, not just on reconnect. This is the same defect class as the server-side WR-02 race, now duplicated in the client. The dedup tests (`use-event-stream-retry.test.ts:194-221`) only exercise monotonic sequences, so the gap is untested.
**Fix:** Dedup by membership instead of a watermark — keep a bounded `Set<number>` of seen seqs (or only dedup while a reconnect-replay is in progress), e.g.:
```ts
const seenSeqsRef = useRef(new Set<number>())
...
if (typeof seq === 'number') {
  if (seenSeqsRef.current.has(seq)) return
  seenSeqsRef.current.add(seq)
}
```
Alternatively fix the backend ordering first (WR-02/WR-12: make seq allocation + append atomic) and document the monotonic-delivery guarantee the watermark relies on, with a backend regression test enforcing it.

### WR-14 (NEW, 01-15): Unbounded SSE live buffer removes backpressure — slow client can grow server memory without bound

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt:211-213`
**Issue:** The 01-15 subscribe-before-snapshot rework introduces `Channel<VizEvent>(Channel.UNLIMITED)` fed by a collector coroutine (`session.bus.stream().collect { liveBuffer.send(it) }`) that never suspends on send. Previously the SSE writer's own suspension applied backpressure to that subscriber; now the collector drains the bus as fast as events are produced regardless of whether the client is reading. A slow, stalled, or half-open SSE client (writer suspended on the socket) causes the channel to accumulate every event for the lifetime of the connection — per client. Combined with the bounded EventStore work elsewhere in this phase (whose entire purpose was capping per-session memory), this re-opens an unbounded-growth path keyed to remote-client behavior.
**Fix:** Use a bounded channel sized to the expected replay window, e.g. `Channel<VizEvent>(capacity = 4096, onBufferOverflow = BufferOverflow.DROP_OLDEST)` plus a logged warning (and ideally a "stream gap — refetch /events" SSE comment to the client) on overflow; or close the connection when the buffer limit is hit so the client's retry path (01-15 frontend backoff) re-syncs via replay.

### WR-15 (NEW, 01-14): Lane hooks hard-code isLive=false — DispatcherOverview re-arms the 2s poll and defeats the live-mode slow-poll design

**File:** `frontend/src/hooks/use-thread-activity.ts:50, 129`, `frontend/src/components/DispatcherOverview.tsx:11`
**Issue:** `useThreadActivity(sessionId, isLive)` was given the `isLive` flag (01-13) precisely so the Threads view stops polling every 2s while SSE-driven invalidation is active, and `SessionDetails.tsx:59` passes `streamEnabled` accordingly. But the rewritten `useThreadLanesByDispatcher` (line 50) and `useActiveCoroutinesPerThread` (line 129) call `useThreadActivity(sessionId)` with the default `isLive=false`. `DispatcherOverview` — rendered on the same Threads tab as `ThreadTimeline` (`SessionDetails.tsx:420-422`) — therefore registers a second observer on the **same** query key `['thread-activity', sessionId]` with `refetchInterval: 2000`. TanStack Query refetches a query at the smallest interval among its observers, so whenever the Threads tab is open during a live stream, the 2s poll silently wins and the documented "slow 5s fallback while live" behavior (use-thread-activity.ts:33-37) never happens. The polling-storm mitigation this phase shipped is defeated in exactly the scenario it targeted.
**Fix:** Thread the flag through:
```ts
export function useThreadLanesByDispatcher(sessionId: string | undefined, isLive = false) {
  const { data: activity, ...query } = useThreadActivity(sessionId, isLive)
  ...
}
```
and have `DispatcherOverview` accept/forward an `isLive` prop from `SessionDetails` (same for `useActiveCoroutinesPerThread` callers). Add a test asserting only one effective interval per query key while live.

### WR-16 (NEW, 01-15): useEventStream resets the seq watermark but not `events` — sessionId change mixes two sessions' events; re-enable duplicates history

**File:** `frontend/src/hooks/use-event-stream.ts:41, 73-75`
**Issue:** The effect now resets `maxSeqRef.current = 0` on every `sessionId`/`enabled` change (line 74) but never clears the `events` state. Two reachable consequences:
1. **Cross-session mixing:** TanStack Router reuses the component instance on param-only navigation, so navigating from session A's page to session B's re-runs the effect with the new `sessionId` while `events` still holds session A's list. The watermark is 0, so session B's full replay passes the dedup guard and is **appended after session A's events** — the Events tab and pipeline views render an interleaved two-session list until a manual clear.
2. **Re-enable duplication:** any consumer that toggles `enabled` false→true without calling `clearEvents()` gets the full history appended a second time (watermark reset to 0 lets the replay through). `SessionDetails` happens to call `clearEvents()` in its toggle handler, so the hook's correctness currently depends on caller discipline.
**Fix:** Reset the buffer where the watermark is reset:
```ts
retryCountRef.current = 0
maxSeqRef.current = 0
setEvents([])
```
(Keep `clearEvents` for explicit user-driven clearing.)

## Info

### IN-01: `sent()` is documented as the "suspending version" but is not suspend

**Status:** OPEN — not in delta scope; unchanged since 2026-06-12T11:20Z review.
**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/VizSession.kt:109-115`
**Issue:** `sent(event)` is a plain function whose KDoc claims it is the suspending variant; `VizScope` mixes `send`/`sent` arbitrarily (e.g., `VizScope.kt:149` vs `:143`).
**Fix:** `@Deprecated("use send")` and migrate call sites.

### IN-02: Duplicate `registerJob` call in vizLaunch

**Status:** OPEN — not in delta scope; unchanged since 2026-06-12T11:20Z review.
**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:135, 141`
**Issue:** `session.snapshot.registerJob(currentJob, coroutineId)` is invoked twice back-to-back.
**Fix:** Remove the second call.

### IN-03: `mergedTimelineFlow(newestFirst)` parameter is dead — both branches return the same flow

**Status:** OPEN — not in delta scope; unchanged since 2026-06-12T11:20Z review.
**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/VizSession.kt:163-176`
**Issue:** `if (newestFirst) flow else flow` does nothing; the parameter silently has no effect for callers.
**Fix:** Drop the parameter and the no-op `let`, or implement the documented sorting.

### IN-04: Stale comment claims children complete before CoroutineBodyCompleted

**Status:** OPEN — not in delta scope; unchanged since 2026-06-12T11:20Z review.
**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:169-171`
**Issue:** Comment says body completion is emitted after "all children have completed (due to coroutineScope above)" — there is no `coroutineScope` wrapper; children may still be running (that is what `checkAndSendJobStateEvent`/`WaitingForChildren` detects).
**Fix:** Correct the comment.

### IN-05: vizAsync terminal path lacks the JobStateChanged-before-terminal parity applied to vizLaunch

**Status:** OPEN — not in delta scope; unchanged since 2026-06-12T11:20Z review.
**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:347-369`
**Issue:** `vizLaunch`'s completion handler emits `JobStateChanged` before `CoroutineFailed`/`CoroutineCancelled`; `vizAsync`'s handler emits only the terminal event. Downstream views see job-state transitions for launched coroutines but not async ones.
**Fix:** Mirror the `jobStateChanged(...)` emission in `vizAsync`'s failure/cancel branches, or document the deliberate omission.

### IN-06: Leftover scaffolding endpoint and unused imports in Serialization.kt / HTTP.kt

**Status:** OPEN — not in delta scope; unchanged since 2026-06-12T11:20Z review.
**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/Serialization.kt:19-23`, `backend/src/main/kotlin/com/jh/proj/coroutineviz/HTTP.kt:5-17`
**Issue:** `GET /json/kotlinx-serialization` is Ktor template scaffolding exposed in production routing. Both files carry unused imports (micrometer/swagger/openapi/sse in Serialization.kt; contentnegotiation/json/micrometer/sse in HTTP.kt).
**Fix:** Delete the demo route and prune imports (verify the detekt baseline is not suppressing these).

### IN-07: Unused `requiredMetrics` list in MetricsWiringTest

**Status:** OPEN — not in delta scope; unchanged since 2026-06-12T11:20Z review.
**File:** `backend/src/test/kotlin/com/jh/proj/coroutineviz/MetricsWiringTest.kt:93-101`
**Issue:** `requiredMetrics` is declared and never used; the actual assertion uses the separate `metricsToCheck` map.
**Fix:** Delete the dead list.

### IN-08: Duplicate import of kotlin.test.assertTrue in HealthRoutesTest

**Status:** OPEN — not in delta scope; unchanged since 2026-06-12T11:20Z review.
**File:** `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/HealthRoutesTest.kt:23, 25`
**Issue:** `import kotlin.test.assertTrue` appears twice (compiler warning).
**Fix:** Remove the duplicate line.

### IN-09: Explicit `any` usage in the live-stream hook and api-client despite strict-TS convention

**Status:** OPEN — re-verified 2026-06-12T12:44Z; `(event as any).kind` now at `use-event-stream.ts:163`, `fetchJson<any[]>`/`fetchJson<any>` unchanged at `api-client.ts:70, 89`.
**File:** `frontend/src/hooks/use-event-stream.ts:163`, `frontend/src/lib/api-client.ts:70, 89`
**Issue:** `(event as any).kind = ...` and `fetchJson<any[]>` / `fetchJson<any>` contradict the project convention ("no `any` where avoidable") and hide exactly the contract mismatch class found in CR-02.
**Fix:** Use `(event as { kind?: VizEventKind }).kind = ...` and type the fetches as `unknown[]` flowing through `normalizeEvents`.

### IN-10: Dead legacy warning-card code; unencoded ids in URL paths

**Status:** OPEN — not in delta scope; unchanged since 2026-06-12T11:20Z review (note: the unencoded-path list now also includes `getThreadActivity` at `api-client.ts:138`).
**File:** `frontend/src/components/validation/ValidationResultCard.tsx:6-13, 65-111`, `frontend/src/lib/api-client.ts:50-56, 138, 148-149`
**Issue:** `LegacyValidationWarning` and `ValidationWarningCard` are self-described as unused (backend has no Warning variant) — dead code in the production bundle. Separately, `sessionId`/`coroutineId` are interpolated into URL paths without `encodeURIComponent` (other params in the same file are encoded); server-generated ids are currently safe, but ids derived from user-supplied names would break the path.
**Fix:** Delete the legacy card (or move it out of production source); wrap path segments in `encodeURIComponent` for consistency.

### IN-11 (NEW, 01-14): MSW `/events` mock still serves the fictional paginated wrapper — same wire-drift class 01-14 closed for `/threads`

**File:** `frontend/src/mocks/handlers.ts:206-219`
**Issue:** 01-14 fixed the `/threads` mock to serve the real wire shape specifically because a fictional mock shape let CR-02 ship green. The sibling handler for `GET /api/sessions/:sessionId/events` still returns `{ events: [], nextStep, hasMore, total }`, while the real backend returns a bare JSON array (`SessionRoutes.kt:107-108`). Against this mock, `apiClient.getSessionEvents` passes the wrapper object into `normalizeEvents(events: any[])`, which expects an array — dev-mode MSW behavior diverges from production and props up the dead `getSessionEventsPaginated` path (WR-09).
**Fix:** Return a bare array of mock events (and delete the wrapper once WR-09's `getSessionEventsPaginated` is removed).

### IN-12 (NEW, 01-14): Dynamically-constructed Tailwind classes in DispatcherOverview never compile — dispatcher icon tile renders unstyled

**File:** `frontend/src/components/DispatcherOverview.tsx:52`
**Issue:** `className={`p-2 rounded-lg bg-${color}/10 text-${color}`}` builds class names at runtime. Tailwind's JIT scanner only extracts complete literal class strings, so `bg-secondary/10`, `text-warning`, etc. are not generated unless they happen to appear verbatim elsewhere or are safelisted — the icon tile silently loses its background/text color. This was invisible while CR-02 kept `DispatcherOverview` dead; after 01-14 the component renders real data, making the styling defect user-visible.
**Fix:** Map to full literal strings:
```ts
const tileClasses: Record<string, string> = {
  primary: 'bg-primary/10 text-primary',
  secondary: 'bg-secondary/10 text-secondary',
  success: 'bg-success/10 text-success',
  warning: 'bg-warning/10 text-warning',
}
```

### IN-13 (NEW, 01-14): buildThreadLanes orphans the first segment on duplicate ASSIGNED for the same coroutine

**File:** `frontend/src/lib/thread-lanes.ts:55-74`
**Issue:** A second `ASSIGNED` for a coroutineId that already has an open segment overwrites the `openByCoroutine` entry; the subsequent `RELEASED` closes only the second segment, leaving the first open forever. The orphaned segment then (a) inflates busy time until the global max timestamp and (b) makes `useActiveCoroutinesPerThread` report the coroutine as permanently active. Reachable when the bounded EventStore (or projection input) drops an intermediate `RELEASED`. Orphan `RELEASED` is handled; orphan re-`ASSIGNED` is not.
**Fix:** On `ASSIGNED` with an existing open segment for the same coroutineId, close the stale segment at the new event's timestamp (or replace it) before opening the new one, and add a unit test for the ASSIGNED→ASSIGNED→RELEASED sequence.

---

## Delta Review Notes (2026-06-12T12:44Z)

- **Scope:** 16 files from gap-closure plans 01-14 (329edb9..a133619) and 01-15 (b5ef92c..2dbe577), reviewed at standard depth; remaining prior findings re-dated where their files were in scope, otherwise carried forward unchanged.
- **Resolved this delta:** CR-02 (01-14), WR-01 (01-15, client-side; server full-replay residual is a perf concern only).
- **New this delta:** WR-13, WR-14, WR-15, WR-16, IN-11, IN-12, IN-13.
- **Verified positives (no findings):** `buildThreadLanes` is a clean, framework-free pure adapter with exact-value tests including degenerate inputs; the SSE comment-frame flush is regression-tested for both the zero-event and ordering cases (`SseStreamTest.kt:282-383`); the SSE route now correctly rethrows `CancellationException`; the bounded retry/backoff schedule (1s/2s/4s/8s/8s, budget 5, reset on open, timer cancelled on unmount) matches its tests exactly; duplicate-seq events are dropped before both `setEvents` and the invalidation debounce.

_Reviewed: 2026-06-12T12:44:26Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_


## Triage (2026-06-12, user-approved)

**Fix now — backend unit:** WR-02 (+WR-12 merged: atomic seq+append in VizSession.send), WR-03 (gauge deregistration on session close), WR-06 (rethrow CancellationException, sanitize error bodies), WR-11 (heap-tolerant health test), WR-14 (bounded SSE live buffer, DROP_OLDEST + overflow signal), WR-08 (OpenAPI spec: threads map shape, timing ms units, remove RUNNING enum; re-validate), IN-05 (vizAsync JobStateChanged-before-terminal parity).

**Fix now — frontend unit:** CR-01 (full event-kind listener list from shared constants + completeness test), WR-04 (auto-enable once via ref), WR-05 (Clear → clearEvents()), WR-09 (remove fictional pagination/filter client surface), WR-13 (seen-set dedup replaces watermark), WR-15 (thread isLive through lane hooks + DispatcherOverview), WR-16 (setEvents([]) on sessionId/enabled reset), IN-09 (remove explicit any), IN-11 (MSW /events mock → wire shape), IN-12 (static Tailwind class map), IN-13 (close previous segment on duplicate ASSIGNED).

**Backlog:** WR-07 (getOrCreateSession silent substitution — needs 404-vs-create design decision), WR-10 (vizLaunch emits lifecycle from inside body — instrumentation redesign).

**Accepted (no action):** IN-01, IN-02, IN-03, IN-04, IN-06, IN-07, IN-08, IN-10.

## Fix Round (2026-06-12, post-triage)

All 18 fix-now findings resolved and committed atomically:
- Backend (7): WR-02+WR-12 a89ce08, WR-03 b01e7b9, WR-06 4ec349e, WR-11 6325282, WR-14 354c0d1, WR-08 f438c18, IN-05 1c7c44e — `./gradlew test` green
- Frontend (11): CR-01 616ebc9, WR-13 885c2f4, WR-16 ec7c834, WR-04 e0fdd24, WR-15 e076772, WR-05 a624a3f, WR-09 a2cc21f, IN-09 f261232, IN-11 569d51f, IN-12 5c29124, IN-13 926529d — 281/281 tests, tsc + eslint clean

Remaining open: WR-07, WR-10 (backlogged as todos), IN-01–04/06–08/10 (accepted). New backlog todos: backend events-package fork, shared/api-types regeneration, ThreadLanesView unused-component cleanup.
