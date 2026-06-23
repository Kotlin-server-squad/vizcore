---
phase: 02-user-value-visualization
plan: 02
subsystem: backend
tags: [de-fork, fnd-01, timing-analyzer, ns-to-ms, fork-deletion-test, strict-404, wr-07, d-12]
requires:
  - "coroutine-viz-core events/ + checksystem/ packages (authoritative twins)"
  - "01-03 FND-01 big-bang delete pattern (session/ de-fork)"
provides:
  - "ns→ms conversion authoritative in core TimingAnalyzer (NANOS_PER_MILLI)"
  - "ForkDeletionTest guards events/ + checksystem/ (zero .kt)"
  - "D-12 strict-404 read-path audit (WR-07 closed backend-wide)"
affects:
  - "backend/src/main events/ + checksystem/ (deleted, resolve against core)"
  - "core TimingAnalyzer numerical contract (now milliseconds)"
tech-stack:
  added: []
  patterns:
    - "FND-01 big-bang fork delete: identical package names → zero import changes"
    - "Static fork guard test with recursive walkTopDown for nested subpackages"
key-files:
  created:
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/checksystem/TimingAnalyzerCoreTest.kt
  modified:
    - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/checksystem/TimingAnalyzer.kt
    - backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/checksystem/TimingAnalyzerTest.kt
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/ForkDeletionTest.kt
  deleted:
    - "54 .kt files under backend/src/main/.../events/** + .../checksystem/**"
decisions:
  - "Core is the keeper for all twins: flow events have @SerialName the forks lacked; validators have explicit imports the forks used wildcards for — deleting forks resolves to the equal-or-better core version."
  - "ns→ms fix ported by copying the fixed fork TimingAnalyzer over the core file (byte-identical to the intended core result); core's own TimingAnalyzerTest updated to ms expectations (it pinned stale ns values)."
  - "Parallel-worker test flakiness (dispatcher stress, health readiness, transient :coroutine-viz-core:test) is pre-existing and environmental — full suite is deterministically green with --max-workers=1; logged to deferred-items.md, not fixed here."
metrics:
  duration: ~12 min
  completed: 2026-06-14
  tasks: 2
  files: 4 changed + 54 deleted
---

# Phase 2 Plan 2: events/ + checksystem/ De-Fork & ns→ms-into-Core Summary

Reconciled the `events/` and `checksystem/` FQCN forks under `backend/src/main/` into `coroutine-viz-core` (same FND-01 hazard class eliminated for `session/`/`wrappers/`), landed the `TimingAnalyzer` ns→ms conversion authoritatively in core with a proving test, extended `ForkDeletionTest` to guard both packages, and completed the D-12 strict-404 read-path audit (WR-07).

## What Was Built

### Task 1 — ns→ms fix in core, big-bang delete events/ + checksystem/ forks
- **RED:** Updated core's `TimingAnalyzerTest.kt` to millisecond expectations and added backend `TimingAnalyzerCoreTest.kt` (magnitude-sanity: 2_000_000_000 ns → 2000 ms; 5s span → 5000 ms, asserted < ns range). Confirmed 3 core tests fail against the unfixed ns-core. Commit `d8c8803`.
- **GREEN:** Ported `NANOS_PER_MILLI = 1_000_000L` and every `/ NANOS_PER_MILLI` division into the **core** `TimingAnalyzer.kt` (the conversion previously existed ONLY in the backend fork — threat T-02-03). Core test now green.
- **Delete:** Removed all 54 `.kt` files under `backend/src/main/.../events/**` (45) and `.../checksystem/**` (9). Identical package names → no import changes (FND-01 pattern). Backend main + test compile against core-only classes. Commit `2107a74`.

**Fork-vs-core divergence audit (file-by-file):** Every fork file had a core twin (core is a superset: it additionally has ActorEvents, AntiPatternEvents, SelectEvents, AntiPatternDetector). The only logic-bearing divergence was `TimingAnalyzer` (the ns→ms fix), now in core. Other "diffs" were core being equal-or-better: 7 flow event files where **core has `@SerialName` annotations the forks lacked** (correct serialized discriminators), and 3 validators where core uses explicit imports vs. fork wildcards / a cosmetic string-wrap. No backend-only orphan class existed — nothing was left behind.

### Task 2 — ForkDeletionTest guards + strict-404 audit
- Added `EVENTS_FORK_DIR` / `CHECKSYSTEM_FORK_DIR` companion fields and a `countKtFilesRecursive` helper; two new `@Test` guards assert zero `.kt` under each, using `walkTopDown()` (events/ has nested channel/coroutine/deferred/dispatcher/flow/job subpackages). Existing session/ + wrappers/ guards and the working-directory sanity anchor kept intact. All 4 guard cases green. Commit `d017218`.
- **D-12 strict-404 audit (WR-07):** see below.

## Strict-404 Read-Path Audit (D-12 / WR-07)

All 22 `getOrCreateSession` callers reside in three scenario/pattern route files, every one a create-path POST:

| File | Lines | Route kind |
|------|-------|-----------|
| `PatternRoutes.kt` | 40,51,62,73,84 | `POST /api/scenarios/patterns/*` (create) |
| `FlowScenarioRoutes.kt` | 15,22,29,36 | `POST /api/scenarios/flow/*` (create) |
| `ScenarioRunnerRoutes.kt` | 25–260 | `POST /api/scenarios/*` incl. `/custom` (run) |

- No `getOrCreateSession` caller exists outside these files.
- The list routes `GET /api/scenarios/patterns`, `GET /api/scenarios/flow`, `GET /api/scenarios` do NOT call it.
- Every read/SSE/compare route uses `SessionManager.getSession(...)` → strict 404: `SessionRoutes.kt` reads (:60,109,126,145,169) and the SSE stream `sse(/api/sessions/{id}/stream)` (:191→404 at :200); `ComparisonRoutes.kt` compare (:23/:26, :32/:35, 404 for both `a` and `b`).

**Result:** No read/SSE/compare route silently creates sessions. WR-07/T-02-04 closed backend-wide, complementing the 02-01 compare-route 404.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Updated core's own TimingAnalyzerTest.kt to ms expectations**
- **Found during:** Task 1 GREEN. The core module's existing `TimingAnalyzerTest.kt` pinned the old nanosecond values (e.g. `assertEquals(4000L, ...)` for a 4000 ns span, `"...2000ns"`). Landing the ns→ms conversion in core would break these.
- **Fix:** Rescaled the three numeric core test cases to ms-magnitude tsNanos with ms expectations (mirrors the backend test's ms scaling).
- **Files modified:** `backend/coroutine-viz-core/src/test/kotlin/.../TimingAnalyzerTest.kt`
- **Commits:** `d8c8803` (RED rescale), GREEN proven in `2107a74`.

**2. [Rule 3 - Blocking] Adjusted verify command for ktlint task-name ambiguity**
- **Found during:** Task 1/2 verification. The plan's `-x ktlintMain` exclusion is ambiguous in this Gradle setup (per-sourceset task names `ktlintMainSourceSetCheck`/`Format`), aborting the build before tests ran.
- **Fix:** Ran the test tasks without that exclusion (and scoped `--tests` to the backend `:test` task so the core module's filterless run did not error with "No tests found"). No source change.

## Deferred Issues (out of scope)

Pre-existing parallel-worker test flakiness — logged to `deferred-items.md`. Under default parallel Gradle workers, `./gradlew test` intermittently failed a single, **different** unrelated test per run (`VizDispatchersIntegrationTest` 50-coroutine stress, `HealthRoutesTest` readiness, a transient `:coroutine-viz-core:test` task with no recorded assertion failure). With `--max-workers=1` the **entire backend suite (206 backend + all core tests) passes deterministically**. None touch events/checksystem logic; the de-fork changed no behavior. Also noted: pre-existing `ScenarioRunnerRoutes.kt:451` `Timer?` nullability warning, unrelated.

## TDD Gate Compliance

- RED gate: `d8c8803` `test(02-02): add failing core ns->ms timing proof` (3 core tests fail against ns-core, verified).
- GREEN gate: `2107a74` `feat(02-02): land ns->ms fix in core, delete events/+checksystem/ forks`.
- Task 2 guard added as `d017218` `test(02-02): ...` (static-invariant guards; forks already deleted in GREEN so they pass on add, matching the session/wrappers guard shape).

## Verification

- `find backend/src/main/.../events backend/src/main/.../checksystem -name '*.kt' | wc -l` → **0**.
- core `TimingAnalyzer.kt` contains `NANOS_PER_MILLI` (5 occurrences).
- `./gradlew :test --tests "*TimingAnalyzer*"` → green; `./gradlew :test --tests "*ForkDeletionTest*"` → 4/4 green (session, wrappers, events, checksystem).
- `./gradlew test --max-workers=1` (full suite) → **BUILD SUCCESSFUL**.

## Self-Check: PASSED

All created/modified files present on disk; all three task commits (`d8c8803`, `2107a74`, `d017218`) present in git history.
