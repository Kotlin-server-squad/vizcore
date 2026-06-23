# Deferred Items — Phase 02

Out-of-scope discoveries logged during execution. NOT fixed here.

## Pre-existing test flakiness under parallel Gradle test workers

- **Found during:** Plan 02-02, Task 1 (post-de-fork full-suite regression check)
- **Symptom:** `./gradlew test` (default parallel workers) intermittently reports a
  single failing test, but a *different* unrelated test on each run — observed:
  `VizDispatchersIntegrationTest > testStressTestWithManyCoroutines` (50-coroutine
  timing stress), `HealthRoutesTest > GET api ready returns 200`, and a transient
  `:coroutine-viz-core:test` task failure with no individual assertion recorded.
- **Root cause (not the de-fork):** timing/scheduler contention across parallel JVM
  test workers on this machine. With `--max-workers=1` the **entire backend suite
  (206 backend tests + all core tests) passes deterministically**. None of the
  flaky tests touch `events/` or `checksystem/` logic; the de-fork changed no
  behavior (fork files were byte-identical-or-better twins of core).
- **Disposition:** Pre-existing, environmental, out of scope for 02-02. Candidate
  follow-up: mark the dispatcher stress + health-readiness tests `@Isolated`/
  non-parallel, or add retry, or pin a serial test executor for timing-sensitive
  integration tests.

## Pre-existing compiler warning (unrelated to this plan)

- `backend/src/main/.../routes/ScenarioRunnerRoutes.kt:451` — `Timer?` vs `Timer`
  Java type-mismatch warning. Predates 02-02; not in the events/checksystem scope.
