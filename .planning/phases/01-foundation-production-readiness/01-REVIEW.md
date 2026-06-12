---
phase: 01-foundation-production-readiness
reviewed: 2026-06-12T06:49:46Z
depth: standard
files_reviewed: 6
files_reviewed_list:
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/MetricsWiringTest.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/ForkDeletionTest.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/HealthRoutesTest.kt
  - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt
  - backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/wrappers/VizScopeCancellationTest.kt
findings:
  critical: 0
  warning: 7
  info: 8
  total: 15
status: issues_found
---

# Phase 01: Code Review Report (Gap-Closure Plans 01-06 / 01-07 / 01-08)

**Reviewed:** 2026-06-12T06:49:46Z
**Depth:** standard
**Files Reviewed:** 6
**Status:** issues_found

> Supersedes the previous 01-REVIEW.md (CR-01/CR-02/CR-03 review of 2026-06-11).

## Summary

This review covers the files changed by phase 01's gap-closure plans: SSE clients gauge wiring (01-06), wrappers fork deletion + ForkDeletionTest extension (01-07), and the VizScope Job-in-context cancellation fix (01-08).

The three original findings are confirmed fixed:

- **CR-01 (sse clients gauge):** `sseClientsGauge.incrementAndGet()` is placed immediately before the `try` and decremented in `finally` (SessionRoutes.kt:193, 228) — balanced on all paths that incremented. Verified against `MetricsWiring.kt` (`AtomicInteger` registered as `viz.sse.clients.active`).
- **CR-02 (wrappers fork):** ForkDeletionTest now guards both `session/` and `wrappers/` directories; the 11 wrapper class names match the deleted fork files, and `backend/src/main/.../wrappers/` contains no `.kt` files.
- **CR-03 (VizScope Job):** `VizScope.coroutineContext` now carries a `Job` parented to `session.sessionScope`'s `SupervisorJob` (verified at VizSession.kt:43), so `cancel()`/`cancelAndJoin()` are functional and root coroutines are no longer unparented. VizScopeCancellationTest covers both the context shape and real cancellation behavior with real time per WR-15.

However, the CR-03 fix introduces one behavioral regression (WR-03), leaves the class KDoc contradicting the new Job-override behavior (WR-04), and the in-scope files carry several latent defects: the SSE handler swallows `CancellationException` and has a replay-to-live handoff race, the fork guard fails open if the test working directory changes, and `vizFlow`/`vizWrap` coroutineId extraction can never succeed. No Critical findings.

## Warnings

### WR-01: SSE handler catches `CancellationException`, logging ERROR on every normal disconnect

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt:225-226`
**Issue:** The `bus.stream().collect { send(...) }` loop is infinite; the only way it ends is via an exception — typically a `CancellationException` (or cancellation-wrapping channel exception) when the client disconnects and Ktor cancels the handler. `catch (e: Exception)` swallows `CancellationException`, which (a) logs `"Error in SSE stream"` at ERROR level on every routine client disconnect — alert noise in the production logging this phase set up — and (b) violates cooperative cancellation (CLAUDE.md mandates structured concurrency; swallowing CE is the canonical anti-pattern).
**Fix:**
```kotlin
} catch (e: kotlinx.coroutines.CancellationException) {
    logger.debug("SSE stream cancelled for session: {}", sessionId)
    throw e // finally still runs and decrements the gauge
} catch (e: Exception) {
    logger.error("Error in SSE stream for session $sessionId", e)
} finally {
    sseClientsGauge.decrementAndGet()
    logger.info("SSE stream ended for session: $sessionId")
}
```

### WR-02: Replay-to-live handoff race can permanently drop events for a connecting SSE client

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt:197-215`
**Issue:** The handler snapshots `session.store.all()` (line 197), replays it (a loop with one `send` per event — arbitrarily long under load), and only *then* subscribes to `session.bus.stream()` (line 214). `EventBus` is a `MutableSharedFlow` with `replay = 0` (EventBus.kt:36-40), so any event emitted between the store snapshot and the `collect` subscription is never delivered to this client. The `filter { it.seq > lastReplayedSeq }` only deduplicates; it cannot recover the gap. For a real-time visualizer this manifests as missing nodes/transitions for clients that connect while a scenario is running — exactly the demo path.
**Fix:** Subscribe before snapshotting, buffer live events during replay, then drain with the seq filter:
```kotlin
coroutineScope {
    val liveBuffer = Channel<VizEvent>(Channel.UNLIMITED)
    val subscription = launch {
        session.bus.stream().collect { liveBuffer.send(it) }
    }
    val storedEvents = session.store.all() // snapshot AFTER subscribing
    for (event in storedEvents) send(event.toSse())
    val lastReplayedSeq = storedEvents.maxOfOrNull { it.seq } ?: 0L
    for (event in liveBuffer) {
        if (event.seq > lastReplayedSeq) send(event.toSse())
    }
}
```
(Alternatively give the bus a replay/`shareIn` window sized to bridge the gap, but the subscribe-first pattern is deterministic.)

### WR-03: `childrenCount` in completion handler now reads VizScope's own Job — regression introduced by the CR-03 context fix

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:199,213`
**Issue:** Inside `job.invokeOnCompletion { cause -> ... }` (a non-suspend lambda), the bare `coroutineContext` cannot resolve to the suspend intrinsic — it resolves to `this@VizScope.coroutineContext` (VizScope implements `CoroutineScope`). Before the CR-03 fix the scope context had no Job, so `coroutineContext[Job]?.children?.count() ?: 0` was always `0`. Now the scope context *does* carry a Job, so `jobStateChanged` events for a completed/failed coroutine report the number of *other coroutines still active in the entire VizScope* — semantically wrong telemetry that changed silently with this fix. The completed coroutine's own job is available as the captured `job`.
**Fix:**
```kotlin
ctx.jobStateChanged(
    isActive = false,
    isCompleted = false,
    isCancelled = true,
    childrenCount = job.children.count(), // the completed coroutine's job, not the scope's
)
```

### WR-04: Class KDoc still tells callers to pass `SupervisorJob`, which the new context composition silently discards

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:46-48,63-65`
**Issue:** The KDoc says "Use SupervisorJob if you want children to fail independently" — i.e., pass one via `context`. But the new composition `... + context + Job(sessionScopeJob) + ...` deliberately overrides any caller-supplied Job (per the inline comment at lines 59-62). A caller following the class's own documentation gets a plain `Job` and sibling-cancellation semantics with no error or warning. No current call site passes a Job (all call sites pass dispatchers or nothing), so this is latent, but the API contract and implementation now contradict each other.
**Fix:** Either honor a caller-supplied supervisor by choosing the Job kind from `context[Job]` (e.g., `if (context[Job] is CompletableJob && context[Job].isSupervisor...)` is not introspectable — so practically: add a `supervisor: Boolean = false` constructor parameter that picks `SupervisorJob(parent)` vs `Job(parent)`), or — simpler and consistent with the stated design — delete/replace the "Use SupervisorJob" sentence in the KDoc and document that a caller-supplied Job is ignored.

### WR-05: `vizFlow`/`vizWrap` coroutineId extraction via `runBlocking` can never succeed

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:471-476,519-524`
**Issue:** `runBlocking { currentCoroutineContext()[VizCoroutineElement] }` starts a *fresh* coroutine; `currentCoroutineContext()` inside it returns the `runBlocking` context, which never contains the caller's `VizCoroutineElement`. The result is always `null`, so `FlowCreated.coroutineId` is always `"unknown"` — the lookup is dead logic. Additionally, calling `runBlocking` from a non-suspend function routinely invoked on coroutine dispatcher threads (scenario code) blocks a `Dispatchers.Default` worker and is a deadlock-hazard pattern.
**Fix:** Make the builders `suspend` and read the context directly:
```kotlin
suspend fun <T> vizFlow(label: String? = null, block: suspend FlowCollector<T>.() -> Unit): InstrumentedFlow<T> {
    val coroutineId = currentCoroutineContext()[VizCoroutineElement]?.coroutineId
    ...
}
```
If signature changes are out of scope, drop the `runBlocking` block and pass `"unknown"` explicitly — same behavior, no dead code, no blocking hazard.

### WR-06: ForkDeletionTest guard fails open when the working directory is not `backend/`

**File:** `backend/src/test/kotlin/com/jh/proj/coroutineviz/ForkDeletionTest.kt:45,69`
**Issue:** Both guard directories are resolved relative to the test JVM's working directory (`File("src/main/kotlin/...")`). If the working directory ever differs from `backend/` (Gradle `workingDir` change, IDE runner from repo root, future build refactor), `exists()` returns false, both tests pass vacuously, and the FND-01 guard silently stops guarding — the exact failure mode this test exists to prevent. There is no sanity anchor proving path resolution is correct.
**Fix:** Assert a directory that must exist when resolution is correct, so a wrong working directory fails loudly instead of passing:
```kotlin
@BeforeEach
fun `working directory sanity anchor`() {
    val anchor = File("src/main/kotlin/com/jh/proj/coroutineviz")
    assertTrue(
        anchor.isDirectory,
        "ForkDeletionTest path anchor missing — test working directory is not backend/; " +
            "the fork guard would pass vacuously. cwd=${File("").absolutePath}",
    )
}
```

### WR-07: Strict 200 assertions on `/health` are flaky under the heap-degradation behavior the suite itself documents

**File:** `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/HealthRoutesTest.kt:52,107`
**Issue:** The test at lines 61-82 explicitly tolerates `503 ServiceUnavailable` because "/health flips to 503/DEGRADED when shared-JVM heap usage crosses 90%, which depends on suite ordering and GC timing." Yet the tests at lines 46-59 and 101-117 hit the same `/health` endpoint and assert a strict `HttpStatusCode.OK` (and line 55 asserts `status == "UP"`). Under the same heap pressure those tests fail intermittently — a known-flaky pattern acknowledged in one test and ignored in two others.
**Fix:** Apply the same tolerance consistently: assert only the fields under test (sessions/version/components) and accept `OK || ServiceUnavailable` for status, or extract a shared `assertHealthReachable(response)` helper used by all `/health` tests.

## Info

### IN-01: Dead `requiredMetrics` list

**File:** `backend/src/test/kotlin/com/jh/proj/coroutineviz/MetricsWiringTest.kt:93-101`
**Issue:** `requiredMetrics` is built and never read; the actual assertions iterate `metricsToCheck`. Dead code that can drift from the real check list.
**Fix:** Delete `requiredMetrics` (the ADR-020 names are already documented by `metricsToCheck` keys).

### IN-02: Scenario POST response is unchecked

**File:** `backend/src/test/kotlin/com/jh/proj/coroutineviz/MetricsWiringTest.kt:86`
**Issue:** `jsonClient.post("/api/scenarios/nested")` status is not asserted. If the route 404s or errors, `scenario_duration`/`event_processing_duration` never appear and the failure surfaces as a confusing "metric missing" assertion instead of "scenario failed to run".
**Fix:** Assert the POST status before scraping `/metrics`.

### IN-03: Duplicate `registerJob` call

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:132,138`
**Issue:** `session.snapshot.registerJob(currentJob, coroutineId)` is called twice in `vizLaunch`. Harmless today (it is a map put — RuntimeSnapshot.kt:22-27) but it is copy-paste residue that invites divergence.
**Fix:** Delete lines 137-138.

### IN-04: Mixed `session.send(...)` / `session.sent(...)` usage

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:146,168,281,302,307,385`
**Issue:** `VizSession.sent` is a misnamed alias of `send` (its KDoc claims "Suspending version" but it is not suspend — VizSession.kt:109-115). VizScope mixes both within the same functions, suggesting two APIs where there is one.
**Fix:** Use `session.send(...)` everywhere in VizScope; deprecate `sent` in VizSession (`@Deprecated("Use send", ReplaceWith("send(event)"))`).

### IN-05: Unreachable `Missing job` guard with wrong exception type

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:130,271`
**Issue:** Inside a launched/async coroutine, `coroutineContext[Job]` is never null, so the `?: throw IllegalArgumentException("Missing job")` branch is dead; if it could fire, `IllegalStateException` would be the correct type (no argument is involved).
**Fix:** `val currentJob = coroutineContext.job` (kotlinx extension, already imported at line 34).

### IN-06: Duplicate import

**File:** `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/HealthRoutesTest.kt:23,25`
**Issue:** `import kotlin.test.assertTrue` appears twice; ktlint/detekt (project convention per CLAUDE.md) flags redundant imports.
**Fix:** Remove line 25.

### IN-07: "alias" test exercises the same path as the primary `/health` test

**File:** `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/HealthRoutesTest.kt:101-117`
**Issue:** `GET health alias returns 200 with version and components` requests `/health`, identical to the test at line 46 — if `/health` is the alias of `/api/health` the name is defensible, but it duplicates coverage; if a distinct alias route was intended, that route is untested.
**Fix:** Either rename to describe `/health` field coverage or point it at the intended alias route.

### IN-08: `vizAsync` failure branch lacks the `jobStateChanged` event that `vizLaunch` emits

**File:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt:338-347`
**Issue:** On failure/cancellation, `vizLaunch`'s completion handler emits `coroutineFailed`/`coroutineCancelled` *plus* `jobStateChanged`, while `vizAsync`'s emits only the terminal coroutine event. Consumers projecting job state see async failures differently from launch failures.
**Fix:** Mirror the `jobStateChanged` emission in `vizAsync`'s handler (using the captured `deferred`'s children per WR-03), or document why deferred state is intentionally surfaced elsewhere.

---

_Reviewed: 2026-06-12T06:49:46Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
