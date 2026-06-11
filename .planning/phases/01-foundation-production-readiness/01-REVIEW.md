---
phase: 01-foundation-production-readiness
reviewed: 2026-06-11T15:13:20Z
depth: standard
files_reviewed: 40
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
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/Application.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/MetricsWiring.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/Monitoring.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/HealthRoutes.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/ScenarioRunnerRoutes.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/scenarios/ScenarioRunner.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/Serialization.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/VizEventSerializersModule.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt
  - backend/src/main/resources/application.yaml
  - backend/src/main/resources/openapi/documentation.yaml
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/BoundedStoreWiringTest.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/CorsConfigTest.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/events/VizEventSerializersModuleTest.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/ForkDeletionTest.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/MetricsWiringTest.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/BoundedStoreRegressionTest.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/HealthRoutesTest.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/SessionEventsIntegrationTest.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/SseStreamTest.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/scenarios/CancellationScenarioRegressionTest.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/wrappers/VizScopeCompletionHandlerTest.kt
  - frontend/src/components/validation/TimingReportView.tsx
  - frontend/src/components/validation/ValidationPanel.test.tsx
  - frontend/src/components/validation/ValidationPanel.tsx
  - frontend/src/components/validation/ValidationResultCard.tsx
  - frontend/src/hooks/use-validation.ts
  - frontend/src/lib/api-client.ts
  - frontend/src/types/api.ts
findings:
  critical: 3
  warning: 15
  info: 14
  total: 32
status: issues_found
---

# Phase 01: Code Review Report

**Reviewed:** 2026-06-11T15:13:20Z
**Depth:** standard
**Files Reviewed:** 40
**Status:** issues_found

## Summary

Reviewed the foundation/production-readiness phase: de-fork work (FND-01/02/03), serialization fixes (FIX-01/03/04), health endpoints (PROD-01/04), CORS config (PROD-03), logging/metrics (PROD-02/05), and the frontend validation panel.

Key concerns:

1. **The de-fork is incomplete.** The `session/` fork was deleted and guarded by `ForkDeletionTest`, but the entire `wrappers/` package remains forked between `backend/src/main` and `coroutine-viz-core` with identical fully-qualified class names — and the two copies have already diverged (different file sizes for `InstrumentedFlow.kt`, `InstrumentedChannel.kt`, `VizMutex.kt`, etc.). Which copy executes is decided by classpath ordering.
2. **One of the five "wired" ADR-020 metrics is dead.** `viz.sse.clients.active` is registered but nothing ever increments/decrements `sseClientsGauge`; the metric is permanently 0, and `MetricsWiringTest` only asserts the metric *name* exists, so this shipped green.
3. **`VizScope` has no `Job` in its context**, so root coroutines are unparented (GlobalScope-equivalent): `VizScope.cancel()`/`cancelAndJoin()` are silent no-ops and closing a session cannot stop running scenarios.

Verified positives: the `${VAR:default}` env-substitution syntax in `application.yaml` IS supported by Ktor 3.3.2's YamlConfig (confirmed via bytecode inspection of `ktor-server-config-yaml-jvm-3.3.2.jar`); the 66-subclass serializers module matches its completeness test; FIX-01/FIX-03/FIX-04 regression tests genuinely exercise the fixed code paths.

## Critical Issues

### CR-01: Incomplete de-fork — duplicate FQCN `wrappers` classes with divergent implementations

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:1` (entire file; whole `wrappers/` package)
**Issue:** `backend/src/main/.../wrappers/VizScope.kt` is a byte-for-byte duplicate of `backend/coroutine-viz-core/src/main/.../wrappers/VizScope.kt` — same package, same class name. Worse, 11 wrapper classes are duplicated across the two modules and several have **already diverged**: `InstrumentedFlow.kt` (47.7K vs 45.8K), `InstrumentedChannel.kt` (9.8K vs 9.3K), `InstrumentedSharedFlow.kt`, `InstrumentedStateFlow.kt`, `VizMutex.kt`, `VizSemaphore.kt` all differ in size. At compile time the backend's source copies shadow the core dependency; at runtime the fat jar contains both class files and classloader order decides which loads. This is exactly the failure mode FND-01 was meant to eliminate — `ForkDeletionTest` only guards `session/`, so the wrappers fork ships unguarded and is already drifting.
**Fix:**
```
1. Delete backend/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/* for every class
   that exists in coroutine-viz-core (after reconciling divergent content into core).
2. Keep backend-only classes (VizActor.kt, VizSelect.kt) or move them to core.
3. Extend ForkDeletionTest's guard to wrappers/ (and models/ if duplicated):
   private val WRAPPERS_FORK_DIR = File("src/main/kotlin/com/jh/proj/coroutineviz/wrappers")
   // assert no .kt file in this dir shares a name with a core wrappers class
```

### CR-02: `viz.sse.clients.active` metric is permanently 0 — gauge counter never mutated

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/MetricsWiring.kt:15` and `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt:172-228`
**Issue:** `sseClientsGauge` is declared with the comment "Increment on connect, decrement on disconnect", and `wireMetrics` registers a gauge over it — but a repo-wide search shows no `incrementAndGet`/`decrementAndGet` call anywhere. The SSE handler in `SessionRoutes.kt` never touches it. The ADR-020 metric `viz.sse.clients.active` (PROD-05 deliverable) always reports 0. `MetricsWiringTest` line 96 only asserts the metric name appears in `/metrics` output, so the defect is invisible to CI.
**Fix:**
```kotlin
sse("/api/sessions/{id}/stream") {
    ...
    sseClientsGauge.incrementAndGet()
    try {
        // replay + live stream
    } finally {
        sseClientsGauge.decrementAndGet()
        logger.info("SSE stream ended for session: $sessionId")
    }
}
```
Plus a test asserting the gauge value changes while a stream is open.

### CR-03: `VizScope` has no Job — `cancel()`/`cancelAndJoin()` silently no-op, scenario coroutines are unparented

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:54-59, 397-406` (and the backend duplicate)
**Issue:** `coroutineContext = context + CoroutineName(...)` with default `context = EmptyCoroutineContext` contains **no Job**. Consequences:
1. `cancel()` and `cancelAndJoin()` do `coroutineContext[Job]?.cancel()` — with the default context `[Job]` is null, so both are silent no-ops.
2. Every root `vizLaunch`/`vizAsync` creates a coroutine with no parent (GlobalScope-equivalent), violating the CoroutineScope contract and the project's own "never GlobalScope, use structured concurrency" convention.
3. `SessionManager.closeSession()` cancels `session.sessionScope`, but scenario coroutines are not in that scope — orphaned scenarios keep running and keep calling `session.send()` on a closed session.
The class KDoc ("With the default Job (not SupervisorJob), child failures will cancel parent and siblings") describes behavior that does not exist — there is no default Job.
**Fix:**
```kotlin
override val coroutineContext: CoroutineContext =
    session.sessionScope.coroutineContext + context +
        Job(session.sessionScope.coroutineContext[Job]) +
        CoroutineName("VizScope-$scopeId")
```
(or at minimum `context + Job() + CoroutineName(...)`), so root launches are children of the scope and `cancel()` works.

## Warnings

### WR-01: SSE stream can drop events emitted between replay snapshot and live subscription

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt:196-222`
**Issue:** The handler snapshots `store.all()`, replays it, computes `lastReplayedSeq`, then subscribes to `session.bus.stream()`. Any event emitted after the snapshot but before the SharedFlow collection starts is neither in the replay nor delivered live (its `seq > lastReplayedSeq` but it was published before the subscriber existed). Clients watching an actively-running scenario silently miss events.
**Fix:** Subscribe to the bus first (e.g., buffer into a `Channel`/`shareIn` before replay), then replay the store, then drain the live buffer filtering `seq > lastReplayedSeq`. The existing seq filter already handles the duplicate side.

### WR-02: SSE handler catches `Exception`, swallowing `CancellationException` on client disconnect

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt:223-227`
**Issue:** `catch (e: Exception)` traps `CancellationException` thrown when the client disconnects mid-`collect`, logging routine disconnects as errors and breaking cancellation propagation semantics.
**Fix:**
```kotlin
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    logger.error("Error in SSE stream for session $sessionId", e)
}
```

### WR-03: Unknown `sessionId` silently creates a different session instead of failing

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/ScenarioRunnerRoutes.kt:457-460`
**Issue:** `getOrCreateSession` falls back to `createSession("auto-${System.currentTimeMillis()}")` when the supplied `sessionId` does not resolve. A client that passes a stale/expired sessionId gets its scenario executed in a brand-new session it is not subscribed to — its SSE stream on the old id never shows the events. No log distinguishes "created because absent" from "created because not found".
**Fix:** Return 404 when an explicit `sessionId` is provided but not found (or create the session *with the requested id*); only auto-create when no id was supplied.

### WR-04: `depth` query parameter unbounded — resource-exhaustion vector

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/ScenarioRunnerRoutes.kt:78`
**Issue:** `?depth=` is parsed with `toIntOrNull() ?: 5` and passed straight to `runDeepNesting`. `POST /api/scenarios/deep-nesting?depth=1000000` launches a million nested coroutines, each emitting multiple events, with no cap. Combined with WR-03/CR-03 (unparented, uncancellable coroutines) a single request can exhaust the heap.
**Fix:** `val depth = (call.request.queryParameters["depth"]?.toIntOrNull() ?: 5).coerceIn(1, 20)` and respond 400 for out-of-range values.

### WR-05: Stack trace disclosure in custom-scenario 500 response

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/ScenarioRunnerRoutes.kt:293-304`
**Issue:** `errors = listOf(e.stackTraceToString())` returns the full server stack trace (class names, internal paths, library versions) to the HTTP client. Information disclosure; everything needed is already in the server log on line 294.
**Fix:** `errors = listOf(e.message ?: "Internal error")`; keep the stack trace in the log only.

### WR-06: Per-session `events.buffer.size` gauge is never deregistered — meter and memory leak

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/MetricsWiring.kt:59-62`
**Issue:** Each session registers a `Gauge` tagged `sessionId` whose lambda strongly references the `VizSession`. Sessions are deleted via `SessionManager.closeSession()`, but the meter is never removed: the registry accumulates one gauge per sessionId forever (unbounded tag cardinality) and pins every closed session (and its EventStore, up to 10k events each) in memory. `SessionManager.onSessionClosed` exists (SessionManager.kt:30) and is unused.
**Fix:**
```kotlin
SessionManager.onSessionClosed = { sessionId ->
    registry.find("events.buffer.size").tag("sessionId", sessionId).meters()
        .forEach(registry::remove)
}
```

### WR-07: `vizFlow`/`vizWrap` coroutineId detection via `runBlocking` is always null

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:465-470, 513-518` (and the backend duplicate)
**Issue:** `runBlocking { currentCoroutineContext()[VizCoroutineElement] }` can never see the *caller's* coroutine context — `runBlocking` creates a fresh root context, and a non-suspend function has no access to the calling coroutine's context anyway. The result is always null, so every `FlowCreated` event gets `coroutineId = "unknown"`. It also calls `runBlocking` from threads that may be running coroutines (blocking anti-pattern).
**Fix:** Make `vizFlow`/`vizWrap` `suspend` and read `currentCoroutineContext()[VizCoroutineElement]?.coroutineId` directly, or accept an optional `coroutineId` parameter. Delete the `runBlocking` calls.

### WR-08: Completion handler reads `coroutineContext[Job]` from the wrong scope — `childrenCount` is wrong

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:193, 207` (and the backend duplicate)
**Issue:** Inside `job.invokeOnCompletion { cause -> ... }` (a plain lambda, not a suspend block), `coroutineContext` resolves to the enclosing **VizScope's** `coroutineContext` property — not the completed coroutine's context. With the default scope context there is no Job, so `coroutineContext[Job]?.children?.count() ?: 0` always emits `childrenCount = 0`, and if a Job were present it would count the scope's children, not this coroutine's.
**Fix:** Use the captured handle: `childrenCount = job.children.count()` (and `deferred.children.count()` in `vizAsync`).

### WR-09: Six scenario endpoints respond "completed" with partial counts before the scenario finishes; `scenario.duration` timer measures launch time

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/scenarios/ScenarioRunner.kt:18-241` and `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/ScenarioRunnerRoutes.kt:438-455`
**Issue:** `runNestedCoroutines`, `runParallelExecution`, `runCancellationScenario`, `runDeepNesting`, `runMixedScenario`, and `runExceptionScenario` return the root `Job` without joining (the surrounding `coroutineScope` does not wait because, per CR-03, the launched coroutines are not its children). The route then responds `success=true, message="Scenario completed"`, with `coroutineCount`/`eventCount` snapshotted mid-flight — for `nested` the scenario actually runs ~5 more seconds. The channel and realistic scenarios DO `job.join()`, so the API contract is inconsistent across endpoints. Consequently `scenario.duration` (ADR-020 metric, stopped at ScenarioRunnerRoutes.kt:445) records milliseconds of launch overhead for these six scenarios instead of execution time.
**Fix:** Either `job.join()` in all scenario functions before returning (consistent with channel/realistic ones), or change the response wording/fields to `status: "started"` and document that counts are a snapshot. If async-by-design, stop the timer in `job.invokeOnCompletion`.

### WR-10: Quoted defaults in `application.yaml` corrupt CORS method parsing

**File:** `backend/src/main/resources/application.yaml:12-13` (consumer: `backend/src/main/kotlin/com/jh/proj/coroutineviz/HTTP.kt:42-47`)
**Issue:** The `${VAR:default}` defaults embed literal double quotes: `${CORS_ALLOWED_METHODS:"GET,POST,DELETE,OPTIONS"}`. Ktor's YamlConfig (verified in 3.3.2 bytecode) returns the default *including* the quote characters. `configureHTTP` strips quotes for origins (`trim('"')` workaround) but **not** for methods: the parsed allowed-methods list becomes `"GET`, `POST`, `DELETE`, `OPTIONS"`. The corrupted `"GET` entry is masked only because GET/POST are CORS-simple methods; any future non-simple method placed first or last in the default would silently fail preflight.
**Fix:** Remove the embedded quotes from both defaults:
```yaml
cors:
  allowedOrigins: ${CORS_ALLOWED_ORIGINS:http://localhost:3000,http://127.0.0.1:3000}
  allowedMethods: ${CORS_ALLOWED_METHODS:GET,POST,DELETE,OPTIONS}
```
and delete the `trim('"')` workarounds in HTTP.kt.

### WR-11: Frontend `Scenario.category` type omits `'channel'`, which the backend sends

**File:** `frontend/src/types/api.ts:200` (backend source: `ScenarioRunnerRoutes.kt:362, 374, 386`)
**Issue:** `category?: 'realistic' | 'basic'` — but `GET /api/scenarios` returns `category: "channel"` for three scenarios. Any consumer that exhaustively switches or filters on the typed union will silently mishandle channel scenarios; TypeScript cannot catch it because the data arrives via an untyped fetch cast.
**Fix:** `category?: 'realistic' | 'basic' | 'channel'`

### WR-12: Frontend `VizEvent` union cannot discriminate and omits whole event families

**File:** `frontend/src/types/api.ts:323-415`
**Issue:** The `VizEvent` union includes bare `CoroutineEvent`, whose `kind` is the *entire* `VizEventKind` union — so narrowing via `switch (event.kind)` always falls back to `CoroutineEvent` and the discriminated union is ineffective. Additionally, the union excludes all Flow, Channel, Mutex, Semaphore, Actor, and Select events even though `apiClient.getSessionEvents()` returns them (all 66 kinds are serialized by the backend). Consumers handling these events must cast, defeating strict mode.
**Fix:** Remove `CoroutineEvent` from the union (keep it as a base interface only), add per-kind interfaces (or reuse the `@vizcor/api-types` shared union `SharedVizEvent`) so the union covers every kind the backend emits.

### WR-13: `getSessionEvents` sends `sinceStep`/`limit`/`filter` params the backend ignores

**File:** `frontend/src/lib/api-client.ts:58-95` (backend: `SessionRoutes.kt:90-105`)
**Issue:** `GET /api/sessions/{id}/events` on the backend reads no query parameters and always returns the full store (up to 10,000 events). The client's pagination/filter options — and the entire `getSessionEventsPaginated` method with its `nextStep`/`hasMore`/`total` response shape — are fiction: callers believe they are bounding payloads but always receive everything, and `getSessionEventsPaginated` will produce `hasMore: undefined`/`total: undefined` at runtime despite its non-optional types.
**Fix:** Either implement `sinceStep`/`limit` server-side in SessionRoutes, or delete the dead options and `getSessionEventsPaginated` from the client until the backend supports them.

### WR-14: `SseStreamTest` "replays stored events" test never opens the SSE stream

**File:** `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/SseStreamTest.kt:64-131`
**Issue:** The test named `SSE stream replays stored events` only asserts EventStore contents and that events serialize — it never performs a request against `/api/sessions/{id}/stream` with a populated session. The replay path, the seq-dedup filter, and the replay/live handoff (see WR-01) have zero integration coverage; the test name claims coverage that does not exist, which is how WR-01 and CR-02 can regress invisibly.
**Fix:** Use Ktor's SSE test client (`createClient { install(SSE) }` + `client.sse(...)`) to connect to a session with stored events and assert the replayed frames (event kind, id format, count), with a timeout-bounded collect.

### WR-15: `VizScopeCompletionHandlerTest` cancellation test mixes virtual and real time — flake risk

**File:** `backend/src/test/kotlin/com/jh/proj/coroutineviz/wrappers/VizScopeCompletionHandlerTest.kt:91-120`
**Issue:** The test runs in `runTest` (virtual time) but `vizLaunch` executes on `Dispatchers.Default` (real threads, because VizScope's context has no test dispatcher). The `delay(50)` before `job.cancel()` and the `delay(100)` meant to "give the invokeOnCompletion handlers time" are virtual delays that skip instantly — they provide no real-time margin for the Default-dispatcher coroutine or its completion handler. The assertions race the handler; the test passes today only because `join()` happens to resume after the handler fires.
**Fix:** Use `runBlocking` with real delays (as the first test in the file does), or inject `StandardTestDispatcher` into the VizScope context so virtual time governs the launched coroutine.

## Info

### IN-01: `VizSession.onEventDropped` is dead code

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/VizSession.kt:71`
**Issue:** Declared "used by the metrics layer to increment events.dropped", but nothing ever invokes it — MetricsWiring wires `session.store.onEvict` directly (MetricsWiring.kt:56). Misleading API surface.
**Fix:** Remove the property, or have `EventStore` eviction call it and wire metrics through it.

### IN-02: Duplicate `registerJob` call in `vizLaunch`

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:126, 132`
**Issue:** `session.snapshot.registerJob(currentJob, coroutineId)` is called twice back-to-back.
**Fix:** Delete the second call (line 131-132).

### IN-03: `mergedTimelineFlow` parameter is a no-op; `correlatedFlow` semantics questionable

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/VizSession.kt:163-176, 182-198`
**Issue:** Both branches of `if (newestFirst)` return the same flow — the parameter does nothing. `correlatedFlow` uses `combine`, which only pairs the *latest* values of each flow; events arriving in bursts will skip correlations, so the "pairing by jobId" contract holds only by coincidence.
**Fix:** Drop the dead parameter (or implement buffering+sort); document or reimplement `correlatedFlow` (e.g., scan over the merged stream keyed by jobId).

### IN-04: Unused imports and leftover demo route in Serialization.kt

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/Serialization.kt:4-13, 19-23`
**Issue:** `micrometer`, `openapi`, `swagger`, `sse`, `io.ktor.sse`, `io.micrometer.prometheus` imports are unused. `GET /json/kotlinx-serialization` is a Ktor template leftover exposed in production routing.
**Fix:** Remove unused imports and the demo route.

### IN-05: `/metrics` served without Prometheus content type

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/Monitoring.kt:20-22`
**Issue:** `call.respond(scrape())` sends `text/plain` without the `version=0.0.4` content type Prometheus expects; some scrapers warn or fall back.
**Fix:** `call.respondText(appMicrometerRegistry.scrape(), ContentType.parse("text/plain; version=0.0.4; charset=utf-8"))`

### IN-06: `scenarioDurationTimerRef` global mutable var

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/MetricsWiring.kt:74`
**Issue:** Cross-file communication via a top-level `var Timer?` is fragile (test re-wiring, nullability juggling at ScenarioRunnerRoutes.kt:443-445).
**Fix:** Pass the timer via an application attribute or a small Metrics holder injected into route registration.

### IN-07: `/api/ready` variables named `maxMb`/`usedMb` hold bytes

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/HealthRoutes.kt:81-82`
**Issue:** Unlike `respondHealth` (which divides by 1024*1024), `/ready` keeps raw byte values in variables named `maxMb`/`usedMb`. The percent math is internally consistent, but the names invite a future unit bug.
**Fix:** Rename to `maxBytes`/`usedBytes`.

### IN-08: Metric naming inconsistency and misleading wiring log

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/MetricsWiring.kt:19-48, 70`
**Issue:** Two metrics carry the `viz.` prefix, five do not (`events.emitted`, `scenario.duration`, ...) — inconsistent namespace for dashboards. The log line claims "7 ADR-020 metrics registered" but only 6 are registered at wire time (`events.buffer.size` registers lazily per session).
**Fix:** Prefix all custom metrics with `viz.`; adjust the log message.

### IN-09: Dead `ValidationWarningCard` and `LegacyValidationWarning`

**File:** `frontend/src/components/validation/ValidationResultCard.tsx:6-13, 65-111`
**Issue:** The comment itself says "unused — backend has no Warning type". ~50 lines of dead component code with its own legacy type.
**Fix:** Delete `ValidationWarningCard` and `LegacyValidationWarning` (restore from git if a Warning variant ever ships).

### IN-10: `vizLaunch` comment claims a `coroutineScope` that does not exist

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:160-162`
**Issue:** "AND all children have completed (due to coroutineScope above)" — there is no `coroutineScope` wrapper; `CoroutineBodyCompleted` is emitted as soon as `block()` returns, while children may still run. The KDoc (lines 66-68, "Ensures parents wait for all children") is similarly inaccurate.
**Fix:** Correct the comments to describe actual semantics, or wrap `block()` in `coroutineScope { }` if wait-for-children is the intended contract.

### IN-11: `assert()` in test depends on JVM `-ea` flag

**File:** `backend/src/test/kotlin/com/jh/proj/coroutineviz/BoundedStoreWiringTest.kt:100`
**Issue:** Kotlin's `assert()` is a no-op without `-ea`. Gradle's Test task enables assertions by default, but any runner that doesn't silently turns this acceptance check into a pass-through.
**Fix:** Use `assertTrue(storeSize <= testMaxEvents) { ... }` like BoundedStoreRegressionTest does.

### IN-12: Heavy duplication and magic `delay(100)` in scenario routes

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/ScenarioRunnerRoutes.kt:22-248, 446`
**Issue:** Twelve near-identical route blocks (sessionId extraction, log line, completion response) differ only in scenario function and message; the hardcoded `delay(100)` "settling" pause is an unexplained magic number duplicated in the custom-scenario handler (line 265).
**Fix:** Extract a `scenarioRoute(path, message) { session -> ... }` helper; name the constant (`EVENT_SETTLE_DELAY_MS`) or remove it once scenarios join properly (WR-09).

### IN-13: OpenAPI spec gaps — `/metrics` undocumented; security claim diverges from config

**File:** `backend/src/main/resources/openapi/documentation.yaml:17-19` (and missing path entries)
**Issue:** The spec states "All endpoints are currently unauthenticated (Phase 1)" with `security: []`, but `application.yaml:9` wires an optional `auth.apiKey` and `Application.module()` calls `configureAuth()` — a deployment with `API_KEY` set contradicts the spec, and no `securityScheme` exists to express it. The `/metrics` endpoint (PROD-05) and the leftover `/json/kotlinx-serialization` route are absent from the spec.
**Fix:** Add an `ApiKeyAuth` securityScheme (applied conditionally documented), document `/metrics`, remove the demo route (IN-04).

### IN-14: `any` usage in api-client and utils

**File:** `frontend/src/lib/api-client.ts:70, 89` (also `frontend/src/lib/utils.ts:65`)
**Issue:** `fetchJson<any[]>` / `fetchJson<any>` bypass strict typing at the exact boundary where typing matters most (project convention: "no `any` where avoidable").
**Fix:** Type the raw payload as `unknown[]` and let `normalizeEvents` perform the validated narrowing.

---

_Reviewed: 2026-06-11T15:13:20Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
