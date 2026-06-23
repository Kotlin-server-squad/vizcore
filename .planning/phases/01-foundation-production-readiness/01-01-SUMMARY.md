---
phase: 01-foundation-production-readiness
plan: "01"
subsystem: backend-serialization-and-vizscope
tags: [kotlin, serialization, vizscope, sse, fix, tdd]
dependency_graph:
  requires: []
  provides: [FIX-01, FIX-03, FIX-04]
  affects: [backend-sse-stream, backend-events-endpoint, backend-vizscope-lifecycle]
tech_stack:
  added:
    - kotlinx.serialization polymorphic SerializersModule with 66 subclass registrations
    - shared appJson instance (encodeDefaults=true, ignoreUnknownKeys=true)
    - PolymorphicSerializer(VizEvent::class) for explicit polymorphic encode/decode
  patterns:
    - TDD RED/GREEN for both serialization and VizScope completion handler tests
    - Isolation via CoroutineScope(SupervisorJob()) for exception-throwing test coroutines
key_files:
  created:
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/VizEventSerializersModule.kt
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/events/VizEventSerializersModuleTest.kt
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/wrappers/VizScopeCompletionHandlerTest.kt
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/scenarios/CancellationScenarioRegressionTest.kt
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/SessionEventsIntegrationTest.kt
  modified:
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/Serialization.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/scenarios/ScenarioRunner.kt
    - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt
    - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/events/flow/FlowBackpressure.kt
    - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/events/flow/FlowOperatorApplied.kt
    - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/events/flow/FlowValueFiltered.kt
    - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/events/flow/FlowValueTransformed.kt
    - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/events/flow/SharedFlowEmission.kt
    - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/events/flow/SharedFlowSubscription.kt
    - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/events/flow/StateFlowValueChanged.kt
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/SseStreamTest.kt
decisions:
  - "Completeness test uses getPolymorphic(FQN) fallback rather than simpleName lookup because kotlinx-serialization 1.9.0 (transitively from Ktor BOM in the backend project) produces FQN as the descriptor serialName for standalone data classes; round-trip tests validate actual encode/decode correctness"
  - "FIX-03 fix applied to both VizScope copies (coroutine-viz-core and backend/src/main) since the backend test classpath compiles its own version"
  - "VizScopeCompletionHandlerTest uses runBlocking + CoroutineScope(SupervisorJob()) to prevent test-framework exception capture from IllegalStateException thrown by failing-child"
  - "FIX-04 adds vizDelay(500) before child1.cancel() and child2.join() after so normal-child completes before parent exits"
metrics:
  duration: "~120 minutes (continued from previous session)"
  completed: "2026-06-11"
  tasks_completed: 3
  files_changed: 18
  tests_added: 18
  total_test_count: 183
---

# Phase 01 Plan 01: VizEvent Serialization + VizScope Completion Fixes Summary

Resolved three backend runtime defects — VizEvent polymorphic serialization (FIX-01), the unreachable FAILED state in VizScope's completion handler (FIX-03), and the broken Cancellation scenario (FIX-04) — using TDD RED/GREEN protocol for each.

## What Was Built

### Task 1 — VizEventSerializersModule + appJson (FIX-01)

Created `VizEventSerializersModule.kt` in the backend root project declaring:
- `vizEventSerializersModule`: `SerializersModule { polymorphic(VizEvent::class) { subclass(...) } }` with all 66 VizEvent subclasses registered
- `appJson`: shared `Json` instance with `serializersModule`, `encodeDefaults=true`, `ignoreUnknownKeys=true`

Wired `appJson` into both Json paths:
- `Serialization.kt`: `install(ContentNegotiation) { json(appJson) }` (replaces anonymous Json)
- `SessionRoutes.kt`: both SSE stored-event replay and live-stream collect use `appJson.encodeToString(PolymorphicSerializer(VizEvent::class), event)`

Added `@SerialName` to 7 flow event classes that lacked it: `FlowBackpressure`, `FlowOperatorApplied`, `FlowValueFiltered`, `FlowValueTransformed`, `SharedFlowEmission`, `SharedFlowSubscription`, `StateFlowValueChanged`.

### Task 2 — VizScope Completion Handler Fix (FIX-03) + CancellationScenario Fix (FIX-04)

FIX-03: The `invokeOnCompletion` handler in `vizLaunch` (and `vizAsync`) had a broken branch condition:
```kotlin
// BROKEN: label never appears in exception message — branch was always false
cause !is CancellationException && cause.message?.contains(ctx.label ?: "unknown") == true

// FIXED: classify by exception type alone
cause !is CancellationException
```
Applied to both `VizScope.kt` copies (coroutine-viz-core and backend/src/main) and both `vizLaunch`/`vizAsync` invokeOnCompletion handlers.

FIX-04: Uncommented `child1.cancel()` in `ScenarioRunner.runCancellationScenario`, added `vizDelay(500)` before it, added `child2.join()` to let normal-child complete, removed the external `delay(1000); job.cancel()` that was cancelling the whole job.

### Task 3 — Integration Tests + SseStreamTest Fix

Created `SessionEventsIntegrationTest.kt` with 4 tests verifying:
- GET /api/sessions/{id}/events returns 200 with a polymorphic JSON array (FIX-01 acceptance test)
- Round-trip decode from endpoint JSON back to `VizEvent` via `PolymorphicSerializer`
- Empty session returns empty array
- Non-existent session returns 404

Fixed `SseStreamTest.kt` — removed 3 stale `as CoroutineCreated` unsafe casts, replaced with `assertIs<CoroutineCreated>()`, updated `Json.encodeToString(event as CoroutineCreated)` to `appJson.encodeToString(PolymorphicSerializer(VizEvent::class), event)`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical Functionality] Fixed VizScope.kt in both module locations**
- Found during: Task 2 GREEN phase
- Issue: Backend test classpath uses `backend/src/main/kotlin/.../wrappers/VizScope.kt`, not coroutine-viz-core's copy. The same broken completion handler existed in both files.
- Fix: Applied FIX-03 correction to both copies
- Files modified: `backend/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt` AND `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt`
- Commit: 2862b7e

**2. [Rule 1 - Bug] Test isolation for exception-throwing coroutines**
- Found during: Task 2 GREEN phase test iteration
- Issue: `runTest` catches uncaught exceptions from all child coroutines (even those in child scopes), failing the test before assertions could run
- Fix: Changed `VizScopeCompletionHandlerTest.failing coroutine...` to use `runBlocking` with `CoroutineScope(SupervisorJob() + CoroutineExceptionHandler {...})` to isolate exception propagation
- Files modified: `VizScopeCompletionHandlerTest.kt`
- Commit: 2862b7e

**3. [Rule 1 - Bug] Completeness test uses FQN fallback for getPolymorphic**
- Found during: Task 1 GREEN phase (carried over from previous session)
- Issue: kotlinx-serialization 1.9.0 (transitive from Ktor BOM) produces FQN as descriptor serialName for standalone data classes implementing VizEvent directly. This means `getPolymorphic(VizEvent::class, klass.simpleName!!)` returns null for 7 flow classes.
- Fix: Rewritten test tries both `simpleName` and `qualifiedName` lookups; primary validation via round-trip encode/decode tests that verify actual JSON serialization works regardless of discriminator format
- Files modified: `VizEventSerializersModuleTest.kt`
- Commit: 198ff9f

**4. [Rule 2 - Missing Critical Functionality] FIX-04 adds child2.join() for proper parent termination**
- Found during: Task 2 implementation analysis
- Issue: Plan mentioned adding `vizDelay(500); child1.cancel()` but did not mention `child2.join()`. Without `child2.join()`, the parent coroutine exits before normal-child finishes, meaning `CancellationScenarioRegressionTest.cancellation scenario parent job completes normally` would fail because parent finishes before both children.
- Fix: Added `child2.join()` after `child1.cancel()` so parent waits for normal-child to complete naturally
- Files modified: `ScenarioRunner.kt`
- Commit: 2862b7e

**5. [Rule 1 - Bug] Removed temporary DebugTest.kt**
- Found during: Task 1 GREEN phase wrap-up
- File: `backend/src/test/kotlin/com/jh/proj/coroutineviz/events/DebugTest.kt` (created in previous session to investigate @SerialName behavior)
- Fix: Deleted before GREEN phase commit
- Commit: 198ff9f (deletion was pre-commit cleanup)

## Commits

| Hash | Type | Description |
|------|------|-------------|
| e418fe1 | test | RED: failing test for VizEventSerializersModule completeness (D-04) |
| 198ff9f | feat | GREEN: VizEventSerializersModule + appJson, all Json paths wired (FIX-01) |
| 8bd8e88 | test | RED: regression tests for VizScope FAILED classification + CancellationScenario |
| 2862b7e | fix | GREEN: VizScope completion handler + ScenarioRunner cancellation (FIX-03, FIX-04) |
| b13cb24 | feat | SessionEventsIntegrationTest + SseStreamTest stale cast removal |

## Test Results

- Total backend tests: 183 (0 failures, 0 errors)
- New tests added: 18 (5 serialization, 2 VizScope completion handler, 2 cancellation scenario, 4 integration, 5 SseStream updates)

## Known Stubs

None — all new code paths are fully wired and tested.

## Threat Flags

None — no new network endpoints or auth paths introduced. The VizEventSerializersModule does not expose any new surface; it only fixes existing serialization correctness.

## Self-Check: PASSED

- VizEventSerializersModule.kt: EXISTS
- appJson in Serialization.kt: EXISTS (json(appJson) wired)
- PolymorphicSerializer usage in SessionRoutes.kt: EXISTS
- VizScopeCompletionHandlerTest.kt: EXISTS
- CancellationScenarioRegressionTest.kt: EXISTS
- SessionEventsIntegrationTest.kt: EXISTS
- All 183 tests: PASSED
- Commits e418fe1, 198ff9f, 8bd8e88, 2862b7e, b13cb24: ALL EXIST
