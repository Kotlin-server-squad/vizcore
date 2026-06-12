---
phase: 01-foundation-production-readiness
plan: 06
subsystem: observability
tags: [micrometer, prometheus, sse, ktor, metrics]

# Dependency graph
requires:
  - phase: 01-foundation-production-readiness (plan 05)
    provides: MetricsWiring.kt with viz.sse.clients.active gauge registered against sseClientsGauge AtomicInteger
provides:
  - sseClientsGauge incremented/decremented in the SSE stream lifecycle (SessionRoutes.kt)
  - Value-asserting regression test proving the gauge tracks open/closed SSE connections
affects: [observability, metrics, sse]

# Tech tracking
tech-stack:
  added: []
  patterns: [value-asserting metric tests (parse the scrape number, never name-presence only)]

key-files:
  created: []
  modified:
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/MetricsWiringTest.kt

key-decisions:
  - "No ktor-client-sse dependency added — the artifact does not exist; the client SSE plugin ships in ktor-client-core (already on the test classpath via ktor-server-test-host)"
  - "Gauge poll loops run on Dispatchers.Default inside testApplication so delay/withTimeout use real wall-clock time"
  - "After client disconnect the test publishes wake events so the server-side bus.stream().collect attempts a send, notices the dead connection, and runs the finally-block decrement"

patterns-established:
  - "Metric regression tests must assert parsed numeric values from the Prometheus scrape, not metric-name presence"

requirements-completed: [PROD-05]

# Metrics
duration: ~2h (split across an executor agent + orchestrator inline completion)
completed: 2026-06-12
---

# Plan 01-06: SSE Clients Gauge Wiring Summary

**viz.sse.clients.active now tracks real SSE connections — incremented after the session guard, decremented in finally on every exit path, proven by a lifecycle test that parses scrape values 0 → ≥1 → 0**

## Performance

- **Duration:** ~2h wall clock (executor agent died mid-task-2 on an API socket error; orchestrator completed inline)
- **Completed:** 2026-06-12
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments
- CR-02 / Verification Gap 1 closed: the gauge backing `viz.sse.clients.active` is mutated by the SSE handler instead of staying permanently 0
- Rejected (404) SSE connections do not increment the gauge; disconnect, completion, and exception paths all decrement it
- CI now catches a dead gauge: the new test asserts the parsed scrape value is >= 1.0 while a stream is open and 0.0 after disconnect

## Task Commits

1. **Task 1: Wire sseClientsGauge into the SSE stream lifecycle** - `b4062d7` (feat)
2. **Task 2: Value-asserting gauge lifecycle test** - `00bd64b` (test)

## Files Created/Modified
- `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt` - incrementAndGet after session-not-found guard, decrementAndGet first in finally
- `backend/src/test/kotlin/com/jh/proj/coroutineviz/MetricsWiringTest.kt` - new lifecycle test `viz sse clients active gauge increments while a stream is open and returns to zero after disconnect`

## Decisions Made
- Dropped the plan's `testImplementation("io.ktor:ktor-client-sse")` instruction — that Maven artifact does not exist; `io.ktor.client.plugins.sse.SSE` lives in ktor-client-core which is already on the test classpath. build.gradle.kts is unchanged.
- Poll loops wrapped in `withContext(Dispatchers.Default)` for real-time delays inside `testApplication`.
- Disconnect detection: Ktor's test engine does not propagate client-side SSE cancellation to the server handler parked in `bus.stream().collect`; the test publishes wake events so the server's `send` fails against the closed connection and the finally-block decrement runs. This mirrors production behavior (a dead client is detected at the next write).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Removed non-existent ktor-client-sse dependency**
- **Found during:** Task 2 (test dependency setup)
- **Issue:** Plan instructed adding `io.ktor:ktor-client-sse`, but no such artifact exists in the Ktor BOM — Gradle failed resolution ("Could not find io.ktor:ktor-client-sse:")
- **Fix:** Removed the line; the SSE client plugin is provided by ktor-client-core transitively
- **Files modified:** backend/build.gradle.kts (net zero diff vs HEAD)
- **Verification:** compileTestKotlin and the full MetricsWiringTest class pass
- **Committed in:** 00bd64b (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (blocking)
**Impact on plan:** Dependency assumption was wrong in the plan; no scope change.

## Issues Encountered
- First executor agent died on an API socket error mid-Task-2; a second died before making progress. The orchestrator completed Task 2 inline per the workflow's stall-recovery fallback.
- Initial test drafts hit three real issues in sequence: deprecated scope-less `launch` (fixed with `coroutineScope`), virtual-vs-real time assumptions (fixed with `Dispatchers.Default`), and the server handler not observing client disconnect until the next send (fixed with wake events).

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- ADR-020 metric set is now fully live-valued; `/metrics` reflects actual SSE client counts
- No blockers for plans 01-07/01-08

---
*Phase: 01-foundation-production-readiness*
*Completed: 2026-06-12*
