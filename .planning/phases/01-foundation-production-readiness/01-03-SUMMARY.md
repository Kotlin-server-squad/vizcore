---
phase: 01-foundation-production-readiness
plan: "03"
subsystem: backend-session-defork
tags: [kotlin, session, eventsore, bounded, coroutine-viz-core, defork, regression-test]

dependency_graph:
  requires:
    - phase: 01-01
      provides: "FIX wave complete — VizScope + ScenarioRunner fixed; backend tests green at 183"
  provides:
    - "FND-01: session fork deleted, backend resolves against coroutine-viz-core only"
    - "FND-02: bounded EventStore wired via session.maxEvents yaml key + SessionManager.configure()"
    - "FND-03: regression test proving store.all().size <= maxEvents; ForkDeletionTest CI guard"
  affects:
    - "01-04 (health endpoints, metrics) — SessionManager.configure() is now the authoritative startup hook"
    - "01-05 (ADR-020 metrics) — onSessionCreated/onEvict callbacks already on core SessionManager/EventStore"

tech-stack:
  added: []
  patterns:
    - "application.yaml session.maxEvents key with env-override pattern (${SESSION_MAX_EVENTS:10000})"
    - "SessionManager.configure() called in Application.module() before configureRouting()"
    - "ForkDeletionTest static guard pattern: filesystem check in JUnit test for CI enforcement"

key-files:
  created:
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/BoundedStoreWiringTest.kt
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/ForkDeletionTest.kt
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/BoundedStoreRegressionTest.kt
  modified:
    - backend/src/main/resources/application.yaml
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/Application.kt
  deleted:
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/session/EventApplier.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/session/EventBus.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/session/EventContext.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/session/EventStore.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/session/FlowEventContext.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/session/ChannelEventContext.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/session/JobStatusMonitor.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/session/ProjectionService.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/session/SessionManager.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/session/VizSession.kt

key-decisions:
  - "D-01 big-bang delete executed: all 10 fork files removed in one commit; no import statement changes needed (same package name com.jh.proj.coroutineviz.session)"
  - "ForkDeletionTest uses filesystem check from the backend test working directory (src/main/.../session/) rather than a classpath reflection scan — simpler and not falsified by classpath ordering"
  - "BoundedStoreRegressionTest re-configures SessionManager.configure() with a small cap (20) inside the testApplication lambda to keep the test fast while proving eviction through VizSession.send()"

requirements-completed: [FND-01, FND-02, FND-03]

duration: ~6min
completed: "2026-06-11"
---

# Phase 01 Plan 03: Session Fork Deletion + Bounded EventStore Wiring Summary

**Deleted the 10-file session fork from backend/src/main, wired bounded EventStore (ArrayDeque + maxEvents=10000) via application.yaml + SessionManager.configure(), and added three tests: wiring smoke, eviction regression, and CI fork-deletion guard.**

## Performance

- **Duration:** ~6 min
- **Started:** 2026-06-11T13:35:00Z
- **Completed:** 2026-06-11T13:40:16Z
- **Tasks:** 3
- **Files modified/deleted/created:** 15 (10 deleted, 2 modified, 3 created)

## Accomplishments

- Deleted all 10 forked session classes from `backend/src/main/.../session/`; the backend now resolves `SessionManager`, `VizSession`, `EventStore`, `EventBus`, `EventApplier`, `ProjectionService`, `JobStatusMonitor`, `EventContext`, `FlowEventContext`, and `ChannelEventContext` from `coroutine-viz-core` exclusively
- Added `session.maxEvents: ${SESSION_MAX_EVENTS:10000}` to `application.yaml` and called `SessionManager.configure(maxEventsPerSession = maxEvents)` in `Application.module()` before `configureRouting()`, with `toIntOrNull() ?: 10_000` safe-default for malformed env input (ASVS V5)
- Full backend test suite: 187 tests, 0 failures (4 new tests added; all previously green tests continue to pass)

## Task Commits

1. **Task 1: Big-bang delete session fork** - `46221e6` (feat)
2. **Task 2: Wire bounded EventStore via application.yaml + SessionManager.configure()** - `2dc25ea` (feat)
3. **Task 3: Bounded-store eviction regression test + fork-deletion static guard** - `5a60e6a` (feat)

## Files Created/Modified

- `backend/src/main/resources/application.yaml` — added `session:` block with `maxEvents: ${SESSION_MAX_EVENTS:10000}`
- `backend/src/main/kotlin/com/jh/proj/coroutineviz/Application.kt` — added `SessionManager.configure(maxEventsPerSession = maxEvents)` before `configureRouting()`
- `backend/src/test/.../BoundedStoreWiringTest.kt` — boots module, creates session via API, asserts store is bounded core EventStore; also asserts cap is enforced behaviorally
- `backend/src/test/.../ForkDeletionTest.kt` — static CI guard: fails if any of the 10 deleted fork files reappears under `backend/src/main/.../session/`
- `backend/src/test/.../routes/BoundedStoreRegressionTest.kt` — emits > maxEvents events via `VizSession.send()` through testApplication wiring and asserts `store.all().size <= maxEvents` (FND-03)
- 10 deleted: entire `backend/src/main/kotlin/com/jh/proj/coroutineviz/session/` subtree

## Decisions Made

- **D-01 big-bang delete**: all 10 fork files removed in one `git rm` commit. No import statement changes required — all 23 consuming files import `com.jh.proj.coroutineviz.session.*` (same package name); after deletion they resolve identically to core.
- **BoundedStoreRegressionTest cap approach**: re-calls `SessionManager.configure(maxEventsPerSession = 20)` inside the test to keep the test fast (20 events) while proving eviction through the real `VizSession.send()` path. The `testApplication` block ensures module() ran first (and set cap to 10000).
- **ForkDeletionTest filesystem approach**: uses a `File("src/main/.../session")` directory check relative to the backend test working dir, not a classpath reflection scan. This is simpler, unambiguous, and will not be falsified by classpath ordering.

## Deviations from Plan

None — plan executed exactly as written.

## Issues Encountered

None. `./gradlew compileKotlin` succeeded immediately after deletion (no unresolved references — core declares identical extension functions in the same package). `./gradlew test` remained green through all three tasks.

## Known Stubs

None — all new code paths are fully wired and tested.

## Threat Flags

None — no new network endpoints or auth paths introduced. This plan only removes code (fork deletion), adds a yaml config key, and adds tests.

## Self-Check: PASSED

- `find backend/src/main/.../session -name '*.kt' | wc -l` == 0: CONFIRMED
- `./gradlew compileKotlin`: BUILD SUCCESSFUL
- `./gradlew test`: BUILD SUCCESSFUL (187 tests, 0 failures)
- `application.yaml` contains `maxEvents` key: CONFIRMED (line 16)
- `Application.kt` calls `SessionManager.configure`: CONFIRMED (line 20)
- `BoundedStoreWiringTest.kt`: EXISTS
- `ForkDeletionTest.kt`: EXISTS
- `BoundedStoreRegressionTest.kt`: EXISTS
- Commits 46221e6, 2dc25ea, 5a60e6a: ALL EXIST

*Phase: 01-foundation-production-readiness*
*Completed: 2026-06-11*
