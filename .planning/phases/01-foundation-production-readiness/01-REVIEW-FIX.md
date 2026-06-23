---
phase: 01-foundation-production-readiness
fixed_at: 2026-06-12T07:35:00Z
review_path: .planning/phases/01-foundation-production-readiness/01-REVIEW.md
iteration: 1
findings_in_scope: 7
fixed: 7
skipped: 0
status: all_fixed
---

# Phase 01: Code Review Fix Report

**Fixed at:** 2026-06-12T07:35:00Z
**Source review:** .planning/phases/01-foundation-production-readiness/01-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 7 (fix_scope = critical_warning: 0 Critical, 7 Warnings; 8 Info findings excluded)
- Fixed: 7
- Skipped: 0

All fixes verified per-finding by incremental Gradle compilation plus targeted test classes
(VizScopeCancellationTest, InstrumentedFlowTest, ForkDeletionTest, HealthRoutesTest), and the
full backend suite (`./gradlew test`, both modules) passed after the final fix.

## Fixed Issues

### WR-01: SSE handler catches `CancellationException`, logging ERROR on every normal disconnect

**Files modified:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt`
**Commit:** 2bbf51c
**Applied fix:** Added a dedicated `catch (e: CancellationException)` branch before the generic
`catch (e: Exception)` that logs at DEBUG and rethrows, honoring cooperative cancellation. The
`finally` block still decrements `sseClientsGauge` on all paths. Added the
`kotlinx.coroutines.CancellationException` import.

### WR-02: Replay-to-live handoff race can permanently drop events for a connecting SSE client

**Files modified:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt`
**Commit:** 9a6ad8a
**Applied fix:** Restructured the SSE stream body with the subscribe-first pattern inside
`coroutineScope`: a `Channel<VizEvent>(UNLIMITED)` buffer is fed by a `launch`-ed
`session.bus.stream().collect { ... }` subscription created BEFORE `session.store.all()` is
snapshotted; stored events are then replayed, and the buffer is drained with the
`seq > lastReplayedSeq` filter for deduplication. Extracted a private `VizEvent.toSse()` helper
to remove the duplicated `ServerSentEvent` construction. Removed the now-unused
`kotlinx.coroutines.flow.filter` import; added `channels.Channel`, `coroutineScope`, `launch`.

### WR-03: `childrenCount` in completion handler reads VizScope's own Job (regression from CR-03 context fix)

**Files modified:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt`
**Commit:** 9ecdd17
**Applied fix:** Both `jobStateChanged` emissions in `vizLaunch`'s `invokeOnCompletion` handler
(failure and cancellation branches) now use `job.children.count()` — the completed coroutine's
own captured job — instead of `coroutineContext[Job]?.children?.count() ?: 0`, which resolved to
the scope's Job after CR-03 and counted unrelated still-active coroutines. Verified with
`VizScopeCancellationTest` (green).

### WR-04: Class KDoc still tells callers to pass `SupervisorJob`, which the new context composition silently discards

**Files modified:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt`
**Commit:** eb3776b
**Applied fix:** Took the review's simpler option consistent with the stated design: removed the
"Use SupervisorJob if you want children to fail independently" guidance and documented that the
scope always uses a plain Job parented to the session scope's SupervisorJob, and that any Job
element in the caller-supplied `context` is deliberately overridden. The `@param context` doc
now states the override explicitly.

### WR-05: `vizFlow`/`vizWrap` coroutineId extraction via `runBlocking` can never succeed

**Files modified:** `backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/wrappers/VizScope.kt`
**Commit:** 5cd4ae7
**Applied fix:** Applied the review's primary suggestion: made both builders `suspend` and read
`currentCoroutineContext()[VizCoroutineElement]?.coroutineId` directly, deleting the dead
`runBlocking` lookup (always null) and its dispatcher-blocking/deadlock hazard. Signature-change
impact verified: the only call site (`InstrumentedFlowTest.kt:156`) is already inside `runTest`,
and `vizWrap` has no call sites. `InstrumentedFlowTest` passes.

### WR-06: ForkDeletionTest guard fails open when the working directory is not `backend/`

**Files modified:** `backend/src/test/kotlin/com/jh/proj/coroutineviz/ForkDeletionTest.kt`
**Commit:** ab6301e
**Applied fix:** Added a `@BeforeEach` working-directory sanity anchor that asserts
`File("src/main/kotlin/com/jh/proj/coroutineviz").isDirectory`, with a failure message that
includes the absolute cwd. A wrong working directory now fails both guard tests loudly instead
of letting them pass vacuously. Existing assertions unchanged; `ForkDeletionTest` passes.

### WR-07: Strict 200 assertions on `/health` are flaky under documented heap-degradation behavior

**Files modified:** `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/HealthRoutesTest.kt`
**Commit:** 3565185
**Applied fix:** Extracted the shared `assertHealthReachable(status)` helper (accepts
`OK || ServiceUnavailable`) and applied it to all three `/health` tests. The
`GET health returns UP status` test, whose subject IS the verdict, now asserts verdict
consistency (`UP` for 200, `DEGRADED` for 503) instead of a flaky strict `UP`. The
session-count test's inline tolerance was replaced by the helper; the alias test's strict
`assertEquals(OK, ...)` was replaced by the helper. The `/api/health`, `/api/live`, and
`/api/ready` tests were not flagged and remain strict. `HealthRoutesTest` passes.

## Skipped Issues

None — all 7 in-scope findings were fixed. The 8 Info findings (IN-01 through IN-08) were out
of scope (`fix_scope: critical_warning`).

---

_Fixed: 2026-06-12T07:35:00Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
