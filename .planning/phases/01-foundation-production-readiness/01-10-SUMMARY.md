---
phase: 01-foundation-production-readiness
plan: 10
subsystem: backend/checksystem
tags: [timing, unit-conversion, bug-fix, tdd, gap-closure]
dependency_graph:
  requires: []
  provides: [ms-scale-timing-at-validation-boundary]
  affects: [backend/routes/ValidationRoutes.kt]
tech_stack:
  added: []
  patterns: [NANOS_PER_MILLI private const for unit conversion]
key_files:
  created: []
  modified:
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/checksystem/TimingAnalyzer.kt
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/checksystem/TimingAnalyzerTest.kt
decisions:
  - "Divide ns deltas by NANOS_PER_MILLI = 1_000_000L at compute time; keep TimingReport field types unchanged (Map<String,Long>, Long)"
  - "Frontend BackendTimingReport and TimingReportView.formatMs unchanged; backend is the wrong side"
  - "detekt --jvm-target 24 incompatibility is pre-existing; code compiles and tests pass"
metrics:
  duration: ~17min
  completed: "2026-06-12T09:30:55Z"
  tasks: 2
  files_modified: 2
---

# Phase 01 Plan 10: Timing Unit Conversion (ns to ms) Summary

Fix the `TimingAnalyzer` unit bug: durations were returned in nanoseconds while the frontend `BackendTimingReport` contract documents and renders them as milliseconds, causing a ~5s scenario to display as ~109,172s.

## What Was Built

The backend `checksystem/TimingAnalyzer` (consumed by `ValidationRoutes POST /api/validate/session/{id}`) now converts all duration deltas from nanoseconds to milliseconds before storing them in `TimingReport`. A private constant `NANOS_PER_MILLI = 1_000_000L` guards every conversion site. KDoc on both `TimingReport` and `analyze()` now states the unit is **milliseconds**. No frontend files were changed.

## Tasks Completed

| # | Name | Commit | Files |
|---|------|--------|-------|
| 1 (RED) | Failing test for ms-scale conversion | 6f348b8 | TimingAnalyzerTest.kt |
| 1 (GREEN) | Convert TimingAnalyzer ns → ms | c643280 | TimingAnalyzer.kt, TimingAnalyzerTest.kt |
| 2 | Magnitude-sanity test | f8defad | TimingAnalyzerTest.kt |

## Verification

```
cd backend && ./gradlew :test --tests 'com.jh.proj.coroutineviz.checksystem.TimingAnalyzerTest'
BUILD SUCCESSFUL
```

6 tests pass:
- `duration calculation correct` — updated to ms-scale tsNanos
- `suspension durations tracked` — updated to ms-scale tsNanos
- `report includes all coroutines` — updated to ms-scale tsNanos
- `empty events produce empty report` — unchanged (0L regardless of unit)
- `durations are reported in milliseconds not nanoseconds` — ms contract behavioral assertion
- `magnitude sanity - 5s scenario and sub-ms events map to expected ms values` — explicit guard against ns-range regression

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Existing test assertions used ns-range magnitudes**
- **Found during:** Task 1 GREEN phase
- **Issue:** Three pre-existing tests used tsNanos like 1000/2000/5000 ns and asserted equal raw ns values (4000L, 7000L etc.), which would all become 0 after integer ns/1_000_000 division.
- **Fix:** Updated all three tests to use ms-scale tsNanos (e.g. `1_000_000_000L` = 1s, `1_000_000L` = 1ms) and adjusted expected values to match ms output. This is required by the plan spec: "Update any existing assertions in this file that previously expected nanosecond magnitudes so the suite reflects the ms contract."
- **Files modified:** `TimingAnalyzerTest.kt`
- **Commit:** c643280

### Pre-existing Environment Issue (out-of-scope)

`./gradlew detekt` fails with "Invalid value (24) passed to --jvm-target" — detekt's current version does not support JVM 24. This is a pre-existing configuration mismatch unrelated to this plan's changes. The main code (`./gradlew :compileKotlin`) and tests (`./gradlew :test`) succeed without issue.

## Known Stubs

None. The fix is a pure arithmetic conversion; no placeholder values or wired-but-empty data paths.

## Threat Surface Scan

No new network endpoints, auth paths, file access patterns, or schema changes introduced. The `TimingReport` field types (`Map<String,Long>`, `Long`) are unchanged — only the values change from ns to ms. No new threats.

## TDD Gate Compliance

1. RED commit: `6f348b8` — `test(01-10): add failing test for ms-scale timing conversion` (test asserted ms values, failed against ns implementation)
2. GREEN commit: `c643280` — `feat(01-10): convert TimingAnalyzer durations from ns to ms at boundary` (implementation + updated pre-existing tests pass)
3. Task 2 commit: `f8defad` — `test(01-10): add magnitude-sanity test asserting ms-scale durations`

## Self-Check: PASSED

- `/Users/jirihermann/Documents/workspace-vizcore/vizcore/backend/src/main/kotlin/com/jh/proj/coroutineviz/checksystem/TimingAnalyzer.kt` — modified, contains `NANOS_PER_MILLI`, `/ NANOS_PER_MILLI` conversions, KDoc states milliseconds
- `/Users/jirihermann/Documents/workspace-vizcore/vizcore/backend/src/test/kotlin/com/jh/proj/coroutineviz/checksystem/TimingAnalyzerTest.kt` — modified, contains `magnitude sanity` test, all assertions in ms range
- Commits 6f348b8, c643280, f8defad exist in git log
- `./gradlew :test --tests 'com.jh.proj.coroutineviz.checksystem.TimingAnalyzerTest'` → BUILD SUCCESSFUL
