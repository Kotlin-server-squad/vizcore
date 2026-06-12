---
phase: 01-foundation-production-readiness
reviewed: 2026-06-12T11:20:32Z
depth: standard
files_reviewed: 51
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
  - frontend/src/components/SessionDetails.test.tsx
  - frontend/src/components/SessionDetails.tsx
  - frontend/src/components/StructuredConcurrencyInfo.test.tsx
  - frontend/src/components/StructuredConcurrencyInfo.tsx
  - frontend/src/components/validation/TimingReportView.tsx
  - frontend/src/components/validation/ValidationPanel.test.tsx
  - frontend/src/components/validation/ValidationPanel.tsx
  - frontend/src/components/validation/ValidationResultCard.tsx
  - frontend/src/hooks/use-event-stream-debounce.test.ts
  - frontend/src/hooks/use-event-stream.ts
  - frontend/src/hooks/use-thread-activity.ts
  - frontend/src/hooks/use-validation.ts
  - frontend/src/lib/api-client.ts
  - frontend/src/lib/event-discriminator.test.ts
  - frontend/src/types/api.ts
findings:
  critical: 2
  warning: 12
  info: 10
  total: 24
status: issues_found
---

# Phase 01: Code Review Report

**Reviewed:** 2026-06-12T11:20:32Z
**Depth:** standard
**Files Reviewed:** 51
**Status:** issues_found

> Supersedes the previous 01-REVIEW.md (gap-closure 01-09..01-12 review). This is the cumulative full-phase review covering all 13 plans.

## Summary

Reviewed the cumulative output of all 13 phase-01 plans: backend de-fork wiring, bounded EventStore, metrics, health routes, CORS config, terminal-event ordering fixes in VizScope, TimingAnalyzer ms conversion, SSE replay/dedup, and the frontend live-stream/debounce/validation work. The targeted fixes (FIX-01 serializers module, FIX-03 terminal classification, FIX-04 targeted cancellation, CR-01/CR-02 debounce caps) are implemented and well-tested. I verified Ktor 3.3.2's YAML config does support the `${VAR:default}` syntax used in `application.yaml` (decompiled `ktor-server-config-yaml` to confirm), and `HTTP.kt` correctly strips the quotes that leak through the default value — no config bug there.

However, the frontend/backend API contract has real, provable breaks: the thread-activity REST endpoint returns a `Map<String, List<ThreadEvent>>` while the typed client declares `ThreadActivityResponse {threads, dispatcherInfo}` (three hooks and `ThreadLanesView` are permanently no-op against the real backend, papered over with an `as unknown as` double cast in `SessionDetails`), and the SSE hook subscribes to only 17 of the 66 event kinds the backend emits, silently dropping all channel/flow/sync/select/actor events from the live stream. Several secondary issues: SSE reconnect duplicates events, an SSE replay/dedup race that can drop events, a per-session Micrometer gauge leak, stack-trace disclosure in the custom-scenario error path, OpenAPI spec drift (units, enums, response shapes) that poisons generated shared types, and a live-stream toggle that can never be turned off on scenario pages.

## Critical Issues

### CR-01: Live SSE stream silently drops 49 of 66 event kinds

**File:** `frontend/src/hooks/use-event-stream.ts:58-87`
**Issue:** The SSE route (`SessionRoutes.kt:240-245`) names each SSE event after its `kind`, and the backend emits 66 registered kinds (`VizEventSerializersModule.kt`). `useEventStream` registers listeners for only 17 PascalCase kinds (coroutine/job/dispatcher/deferred) plus 9 legacy kebab-case names. Every `Channel*`, `Flow*`, `SharedFlow*`, `StateFlow*`, `Mutex*`, `Semaphore*`, `Actor*`, `Select*`, `Deadlock*`, `AntiPatternDetected`, and `WaitingForChildren` event is dropped by the browser because no listener exists for that event name. In live mode `SessionDetails` renders `allEvents = liveEvents` (`SessionDetails.tsx:70`), so the Events tab and the `OrderProcessingView`/`RegistrationFlowView` pipelines show an incomplete stream, while the same session's stored-events view shows the full set. `types/api.ts` already defines all of these kinds and category sets (`CHANNEL_EVENT_KINDS`, `FLOW_EVENT_KINDS`, `SYNC_EVENT_KINDS`, ...), so the gap is purely in the listener list. The `WaitingForChildren` omission also undercuts the WAITING_FOR_CHILDREN teaching feature advertised in `StructuredConcurrencyInfo`.
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

**File:** `frontend/src/lib/api-client.ts:134-136`, `frontend/src/hooks/use-thread-activity.ts:41-130`, `frontend/src/components/SessionDetails.tsx:408`
**Issue:** Backend `ProjectionService.getThreadActivity()` returns `Map<String, List<ThreadEvent>>` (verified at `ProjectionService.kt:234-238`), served verbatim by `GET /api/sessions/{id}/threads`. The client types it as `ThreadActivityResponse { threads: ThreadLaneData[], dispatcherInfo: DispatcherInfo[] }`, a shape the backend never produces. Consequences:
- `useThreadLanesByDispatcher`, `useThreadUtilizationStats`, and `useActiveCoroutinesPerThread` read `activity.threads` / `activity.dispatcherInfo`, which are always `undefined` against the real backend → they always return empty maps/zero stats. `ThreadLanesView.tsx:13-14` consumes them, so that view is dead.
- `SessionDetails.tsx:408` works around the wrong type with `threadActivity as unknown as ThreadActivity` — a double cast that defeats TypeScript entirely and is the tell that the declared client type is wrong (the cast target happens to be the actual wire shape, which is the only reason the Threads tab renders).
- Additionally, `useActiveCoroutinesPerThread` (use-thread-activity.ts:113-118) compares `Date.now() * 1_000_000` (epoch nanos) against `seg.startNanos`, which come from `System.nanoTime()` (arbitrary-origin monotonic clock). The comparison is semantically meaningless; it only "works" because nanoTime values are always smaller than epoch nanos.
**Fix:** Type the client to the actual wire shape (or change the backend to actually serve `ThreadActivityResponse` — pick one source of truth):
```ts
async getThreadActivity(sessionId: string): Promise<ThreadActivity> {
  return this.fetchJson<ThreadActivity>(`/sessions/${sessionId}/threads`)
}
```
Then remove the `as unknown as` cast in `SessionDetails.tsx` and either delete or rewrite the three lane/utilization hooks against the real shape. Replace the epoch-vs-nanoTime comparison with "segment has no endNanos" as the activity criterion.

## Warnings

### WR-01: EventSource auto-reconnect duplicates the entire event history in the live view

**File:** `frontend/src/hooks/use-event-stream.ts:90-100` and `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt:209-214`
**Issue:** The SSE route replays all stored events on every new connection and never reads `Last-Event-ID` (it sets `id:` but ignores it on reconnect). The native `EventSource` auto-reconnects after transient errors, and the hook appends every received event with no dedup (`setEvents(prev => [...prev, event])`). One dropped connection mid-session duplicates the whole history in the UI (event counts, job states, pipeline views all double).
**Fix:** Track the max `seq` seen in the hook and drop events with `seq <= maxSeenSeq` during replay, or honor `Last-Event-ID` server-side and replay only `seq > lastId`.

### WR-02: SSE replay/dedup race can permanently drop events when seq order and store-append order diverge

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt:204-224`
**Issue:** `seq` is allocated at event construction (`nextSeq()` inside the EventContext factories), but `store.append` happens later in `VizSession.send`. With concurrent emitters, thread A can construct seq=10, thread B construct seq=11 and append it first; if the SSE handler snapshots the store at that instant, `lastReplayedSeq = 11`. When A's seq=10 event then arrives through the live buffer, the filter `event.seq > lastReplayedSeq` discards it even though it was never replayed — the event is lost for that client. Scenario coroutines run in parallel on `Dispatchers.Default`, so this interleaving is reachable, not theoretical.
**Fix:** Deduplicate by identity instead of a max-seq watermark: collect the set of seqs in the snapshot and filter the live buffer with `event.seq !in replayedSeqs` (the set can be discarded once the buffer drains past the snapshot window). Alternatively, make seq allocation and store append atomic under one lock in `VizSession.send`.

### WR-03: Per-session `events.buffer.size` gauge is never deregistered — meter and session leak

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/MetricsWiring.kt:51-68, 74`
**Issue:** `onSessionCreated` registers a `Gauge` tagged with `sessionId` whose value lambda strongly captures `session`. Nothing removes the meter when the session is closed (`DELETE /api/sessions/{id}` → `SessionManager.closeSession`). Every closed session stays strongly referenced by the registry (its EventStore included), `/metrics` accumulates one stale `events_buffer_size{sessionId=...}` series per session ever created, and the gauge keeps reporting the dead session's last buffer size forever. Scenario routes auto-create a session per run (`auto-<millis>`), so series grow with every scenario execution. Also `scenarioDurationTimerRef` (line 74) is a top-level mutable `var` — `wireMetrics` runs once per Application instance (multiple times across tests), so the ref silently points at the most recent registry.
**Fix:** Keep a `sessionId -> Meter.Id` map and call `registry.remove(meterId)` from a `SessionManager.onSessionClosed` callback, or register a single multi-gauge backed by `SessionManager.listSessions()`. Pass the scenario timer explicitly (e.g., via an Application attribute) instead of a global `var`.

### WR-04: Live-stream toggle can never be switched off on scenario pages (effect fight-loop that also wipes events)

**File:** `frontend/src/components/SessionDetails.tsx:153-157, 250-255`
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

**File:** `frontend/src/components/SessionDetails.tsx:339-358`
**Issue:** Both buttons invoke `handleReset`, which deletes the session and navigates away. A user clicking "Clear" (reasonably expecting to clear the event list) destroys the session. Two differently-labeled destructive controls with identical behavior is a logic error, not a style choice.
**Fix:** Wire "Clear" to `clearEvents()` (and optionally `refetch()`); keep "Reset" as the delete-and-navigate action — or remove one button.

### WR-06: Scenario error handlers swallow CancellationException and return stack traces to clients

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

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/ScenarioRunnerRoutes.kt:457-460`
**Issue:** `sessionId?.let { SessionManager.getSession(it) } ?: SessionManager.createSession("auto-...")` — if a client passes a stale/wrong `sessionId` (e.g., after `SessionDetails`'s Reset flow deletes a session), the scenario runs in a brand-new auto session instead of failing. A client streaming `/api/sessions/{oldId}/stream` sees nothing and gets no error signal (the substituted id is only in the response body). Also `"auto-${System.currentTimeMillis()}"` can collide when two scenario requests land in the same millisecond. Related: most basic-scenario responses claim "completed" while the scenario is still running (the launched job is not joined for nested/parallel/cancellation/deep-nesting/mixed/exception), and `coroutineCount`/`eventCount` are mid-run snapshots — only the nested route's message hints at the live stream.
**Fix:** Return 404 when an explicit `sessionId` is provided but not found; auto-create only when the parameter is absent. Use a UUID-style suffix for auto names. Reword non-joined scenario messages to "Scenario started" and document that counts are snapshots.

### WR-08: OpenAPI spec contradicts the implementation — and shared/api-types are generated from it

**File:** `backend/src/main/resources/openapi/documentation.yaml:2083-2111, 1730-1732, 1796-1798, 1889-1891, 444-452`
**Issue:** Three provable drifts against code reviewed in this phase:
1. `TimingReport` (lines 2090-2111) documents all durations as **nanoseconds**; `TimingAnalyzer.kt` returns **milliseconds** (NANOS_PER_MILLI division, asserted by `TimingAnalyzerTest` and matched by the frontend `BackendTimingReport`, which is documented as milliseconds). The spec was not updated with the unit fix.
2. The `state` enums on `CoroutineNodeDto`, `HierarchyNode`, and `CoroutineTimeline` list `RUNNING` and omit `ACTIVE` and `WAITING_FOR_CHILDREN`; the backend enum (`models/CoroutineState.kt`) is `CREATED, ACTIVE, SUSPENDED, WAITING_FOR_CHILDREN, COMPLETED, CANCELLED, FAILED` — `RUNNING` does not exist. The frontend's local `CoroutineState` enum already uses ACTIVE/WAITING_FOR_CHILDREN, confirming the spec is the odd one out.
3. `/api/sessions/{id}/threads` is documented as `type: array, items: ThreadEvent`; the endpoint returns a map keyed by threadId (see CR-02).
Per CLAUDE.md, `shared/api-types` is generated from this spec, so each drift propagates into the shared TypeScript types. (Minor: the `VizEvent` description still says "32+ event types" vs the actual 66.)
**Fix:** Update `TimingReport` descriptions to milliseconds, correct the state enums, and document `/threads` as `additionalProperties: { type: array, items: ThreadEvent }` (or fix the endpoint to match the spec — one source of truth). Regenerate shared/api-types afterwards.

### WR-09: api-client advertises pagination/filtering the backend ignores; two methods mis-shape responses

**File:** `frontend/src/lib/api-client.ts:58-95, 154-156`
**Issue:**
1. `getSessionEvents(sessionId, {sinceStep, limit, filter})` sends query params that `GET /api/sessions/{id}/events` never reads (`SessionRoutes.kt:94-109` returns `store.all()` unconditionally). Callers believe they are limiting/filtering but always receive the full event list.
2. `getSessionEventsPaginated` hits the same endpoint and does `{ ...response, events: normalizeEvents(response.events || []) }` — but the endpoint returns a bare JSON array, so `response.events` is always undefined, `events` is always `[]`, `hasMore`/`total` are undefined, and the array spread leaks numeric keys into the result. The method can never work against this backend (only the MSW mock in `mocks/handlers.ts` satisfies it).
3. `getValidationRules()` declares `Promise<{ rules: Array<{id, name, description}> }>`, but `/api/validate/rules` returns a bare array of `{name, description}` (no wrapper, no `id` — verified in `ValidationRoutes.kt:64-106`).
**Fix:** Delete `getSessionEventsPaginated` and the dead option params (or implement them server-side), and retype `getValidationRules` as `Promise<Array<{ name: string; description: string }>>`.

### WR-10: vizLaunch emits lifecycle events from inside the coroutine body — cancel-before-start produces terminal events with no Created/Started

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:131-172, 179-239`
**Issue:** `CoroutineCreated`/`CoroutineStarted` are emitted as the first statements of the launched coroutine body, while `invokeOnCompletion` emits terminal events unconditionally. If a job is cancelled before its first dispatch (scope cancelled right after `vizLaunch` returns, or a sibling fails first — exactly what the exception scenario does to slower siblings), the handler fires with a `CancellationException` and emits `JobStateChanged` + `CoroutineCancelled` for a coroutineId that has no `CoroutineCreated`. That orphan terminal event violates the app's own `CreatedHasStarted` lifecycle validation — the same self-flagging class of bug the FIX-03/terminal-ordering work was meant to eliminate.
**Fix:** Emit `CoroutineCreated` synchronously in `vizLaunch` before `targetScope.launch` (ids and EventContext already exist there), keeping `CoroutineStarted`/`ThreadAssigned` inside the body; or track a "created emitted" flag on the EventContext and skip terminal emission in the handler when it never ran.

### WR-11: /api/health test asserts strict 200/UP while sibling tests document the same code path flips to 503 under shared-JVM heap pressure

**File:** `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/HealthRoutesTest.kt:129-149`
**Issue:** The file's own doc comment (lines 45-49) explains `/health` verdicts depend on suite ordering and GC timing, and four tests use `assertHealthReachable` accepting 200 or 503. But `GET api health returns 200 with version and components` asserts `HttpStatusCode.OK` and `"UP"` strictly against the identical `respondHealth()` implementation — the exact nondeterminism the other tests were hardened against. Under heap pressure this test fails flakily.
**Fix:** Use the same `assertHealthReachable(response.status)` + verdict-consistency pattern as the `/health` alias test.

### WR-12: VizSession.send is not atomic and its timing callback has a check-then-act race

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/VizSession.kt:88-97`
**Issue:** (1) `store.append` / `applier.apply` / `eventBus.send` run without a common lock, so concurrent senders interleave — this is the root enabler of the WR-02 SSE dedup race and means `store.all()` is append-ordered, not seq-ordered. (2) `onEventProcessed` is read twice: if the callback is assigned between the first null check (`startNanos = ... else 0L`) and the invocation, the recorded duration is `System.nanoTime() - 0` — a garbage multi-year sample that wrecks the `event.processing.duration` histogram. `MetricsWiring` assigns these callbacks after session creation while scenario sends may already be in flight.
**Fix:** Capture once: `val cb = onEventProcessed; val start = if (cb != null) System.nanoTime() else 0L; ...; cb?.invoke(System.nanoTime() - start)`. Synchronize seq allocation + append if strict store ordering is required (see WR-02).

## Info

### IN-01: `sent()` is documented as the "suspending version" but is not suspend

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/VizSession.kt:109-115`
**Issue:** `sent(event)` is a plain function whose KDoc claims it is the suspending variant; `VizScope` mixes `send`/`sent` arbitrarily (e.g., `VizScope.kt:149` vs `:143`).
**Fix:** `@Deprecated("use send")` and migrate call sites.

### IN-02: Duplicate `registerJob` call in vizLaunch

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:135, 141`
**Issue:** `session.snapshot.registerJob(currentJob, coroutineId)` is invoked twice back-to-back.
**Fix:** Remove the second call.

### IN-03: `mergedTimelineFlow(newestFirst)` parameter is dead — both branches return the same flow

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/VizSession.kt:163-176`
**Issue:** `if (newestFirst) flow else flow` does nothing; the parameter silently has no effect for callers.
**Fix:** Drop the parameter and the no-op `let`, or implement the documented sorting.

### IN-04: Stale comment claims children complete before CoroutineBodyCompleted

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:169-171`
**Issue:** Comment says body completion is emitted after "all children have completed (due to coroutineScope above)" — there is no `coroutineScope` wrapper; children may still be running (that is what `checkAndSendJobStateEvent`/`WaitingForChildren` detects).
**Fix:** Correct the comment.

### IN-05: vizAsync terminal path lacks the JobStateChanged-before-terminal parity applied to vizLaunch

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:347-369`
**Issue:** `vizLaunch`'s completion handler emits `JobStateChanged` before `CoroutineFailed`/`CoroutineCancelled`; `vizAsync`'s handler emits only the terminal event. Downstream views see job-state transitions for launched coroutines but not async ones.
**Fix:** Mirror the `jobStateChanged(...)` emission in `vizAsync`'s failure/cancel branches, or document the deliberate omission.

### IN-06: Leftover scaffolding endpoint and unused imports in Serialization.kt / HTTP.kt

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/Serialization.kt:19-23`, `backend/src/main/kotlin/com/jh/proj/coroutineviz/HTTP.kt:5-17`
**Issue:** `GET /json/kotlinx-serialization` is Ktor template scaffolding exposed in production routing. Both files carry unused imports (micrometer/swagger/openapi/sse in Serialization.kt; contentnegotiation/json/micrometer/sse in HTTP.kt).
**Fix:** Delete the demo route and prune imports (verify the detekt baseline is not suppressing these).

### IN-07: Unused `requiredMetrics` list in MetricsWiringTest

**File:** `backend/src/test/kotlin/com/jh/proj/coroutineviz/MetricsWiringTest.kt:93-101`
**Issue:** `requiredMetrics` is declared and never used; the actual assertion uses the separate `metricsToCheck` map.
**Fix:** Delete the dead list.

### IN-08: Duplicate import of kotlin.test.assertTrue in HealthRoutesTest

**File:** `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/HealthRoutesTest.kt:23, 25`
**Issue:** `import kotlin.test.assertTrue` appears twice (compiler warning).
**Fix:** Remove the duplicate line.

### IN-09: Explicit `any` usage in the live-stream hook and api-client despite strict-TS convention

**File:** `frontend/src/hooks/use-event-stream.ts:98`, `frontend/src/lib/api-client.ts:70, 89`
**Issue:** `(event as any).kind = ...` and `fetchJson<any[]>` / `fetchJson<any>` contradict the project convention ("no `any` where avoidable") and hide exactly the contract mismatch class found in CR-02.
**Fix:** Use `(event as { kind?: VizEventKind }).kind = ...` and type the fetches as `unknown[]` flowing through `normalizeEvents`.

### IN-10: Dead legacy warning-card code; unencoded ids in URL paths

**File:** `frontend/src/components/validation/ValidationResultCard.tsx:6-13, 65-111`, `frontend/src/lib/api-client.ts:50-56, 145-147`
**Issue:** `LegacyValidationWarning` and `ValidationWarningCard` are self-described as unused (backend has no Warning variant) — dead code in the production bundle. Separately, `sessionId`/`coroutineId` are interpolated into URL paths without `encodeURIComponent` (other params in the same file are encoded); server-generated ids are currently safe, but ids derived from user-supplied names would break the path.
**Fix:** Delete the legacy card (or move it out of production source); wrap path segments in `encodeURIComponent` for consistency.

---

_Reviewed: 2026-06-12T11:20:32Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
