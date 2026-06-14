---
phase: 02-user-value-visualization
plan: 01
subsystem: api
tags: [kotlin, ktor, openapi, openapi-typescript, comparison, threads, react, typescript]

# Dependency graph
requires:
  - phase: 01-foundation
    provides: ComparisonService + /api/compare route, ThreadAssigned events, shared/api-types generation pipeline
provides:
  - "GET /api/sessions/compare?a=&b= returning coroutine/event/duration/thread-utilization deltas"
  - "SessionComparison.distinctThreadsDiff thread-utilization metric (backend + OpenAPI + generated TS + hand-written TS type)"
  - "Ktor integration test covering 200+thread-delta / 404 unknown id / 400 missing param"
  - "Synced shared/api-types (openapi.json + generated.ts) for the renamed compare contract"
affects: [session-comparison-ui, replay, export, api-types-consumers]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Thread-utilization derived from distinct ThreadAssigned.threadId values via session.store.all()"
    - "Compare read uses strict 404 (SessionManager.getSession == null) — never getOrCreate (D-12)"
    - "Static path segment /api/sessions/compare coexists with /api/sessions/{id} (Ktor prioritizes constant segments)"

key-files:
  created:
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/ComparisonRoutesTest.kt
  modified:
    - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/ComparisonService.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/ComparisonRoutes.kt
    - backend/src/main/resources/openapi/documentation.yaml
    - shared/api-types/openapi.json
    - shared/api-types/generated.ts
    - frontend/src/lib/api-client.ts
    - frontend/src/types/api.ts
    - frontend/src/components/comparison/ComparisonView.test.tsx

key-decisions:
  - "distinctThreads(session) counts unique ThreadAssigned.threadId from store.all() (event-derived, not snapshot)"
  - "compareSessions() params renamed sessionA/sessionB -> a/b (positional callers unaffected)"
  - "openapi.json synced surgically (compare path + SessionComparison/CoroutineComparison/ErrorResponse schemas) rather than full YAML->JSON regen to keep diff scoped"
  - "Committed shared/api-types/pnpm-lock.yaml to pin openapi-typescript@7.13.0 for reproducible generation"

patterns-established:
  - "Thread-utilization metric pattern: filterIsInstance<ThreadAssigned>().map { threadId }.distinct().size"
  - "Renaming a hand-maintained OpenAPI path + regenerating generated.ts from a surgically-synced openapi.json"

requirements-completed: [CMPR-01]

# Metrics
duration: ~18min
completed: 2026-06-14
---

# Phase 02 Plan 01: Compare Endpoint Rename + Thread-Utilization Delta Summary

**Renamed the session-compare endpoint to `GET /api/sessions/compare?a=&b=` (D-09) and added the CMPR-01 thread-utilization delta (`distinctThreadsDiff`) end-to-end across Kotlin service/route, OpenAPI spec, regenerated api-types, and the frontend client/type.**

## Performance

- **Duration:** ~18 min
- **Started:** 2026-06-14
- **Completed:** 2026-06-14
- **Tasks:** 2 (TDD)
- **Files modified:** 8 (1 created, 7 modified)

## Accomplishments
- `SessionComparison` gained `distinctThreadsDiff: Int`, computed as distinct-threads(B) − distinct-threads(A) from `ThreadAssigned` events.
- Compare route moved from `/api/compare?sessionA=&sessionB=` to `/api/sessions/compare?a=&b=` with strict 404 on unknown id and 400 on missing param.
- OpenAPI YAML, `shared/api-types/openapi.json`, and regenerated `shared/api-types/generated.ts` all reflect the new path + field.
- Frontend `compareSessions()` calls the new path; both the generated and hand-written `SessionComparison` TS types carry the thread metric.
- New Ktor integration test asserts 200 + thread delta, 404 unknown id, 400 missing param.

## Task Commits

1. **Task 1 (RED): failing tests for distinctThreadsDiff** - `088d21e` (test)
2. **Task 1 (GREEN): thread delta + route rename** - `5d4fccc` (feat)
3. **Task 2: integration test + OpenAPI + api-types regen + frontend** - `f24f531` (feat)

**Plan metadata:** _(final docs commit)_

_Note: TDD tasks committed test → feat per the RED/GREEN gate._

## Files Created/Modified
- `backend/coroutine-viz-core/.../session/ComparisonService.kt` - Added `distinctThreadsDiff` field + `distinctThreads()` helper.
- `backend/src/.../routes/ComparisonRoutes.kt` - Path `/api/sessions/compare`, params `a`/`b`, error messages updated.
- `backend/coroutine-viz-core/.../session/ComparisonServiceTest.kt` - 3 new thread-delta unit tests.
- `backend/src/test/.../routes/ComparisonRoutesTest.kt` - New Ktor integration test (200/404/400).
- `backend/src/main/resources/openapi/documentation.yaml` - Renamed path + `a`/`b` params + `distinctThreadsDiff` schema property.
- `shared/api-types/openapi.json` - Synced compare path + SessionComparison/CoroutineComparison/ErrorResponse schemas.
- `shared/api-types/generated.ts` - Regenerated from openapi.json (now includes the path/field).
- `shared/api-types/pnpm-lock.yaml` - New; pins generation toolchain.
- `frontend/src/lib/api-client.ts` - `compareSessions(a, b)` → `/sessions/compare?a=&b=`.
- `frontend/src/types/api.ts` - `SessionComparison.distinctThreadsDiff: number`.
- `frontend/src/components/comparison/ComparisonView.test.tsx` - Fixture updated with `distinctThreadsDiff`.

## Decisions Made
- Derived thread utilization from `ThreadAssigned` events (already populated in the event store) rather than snapshot thread data — simpler and directly unit-testable.
- Kept `compareSessions` callers untouched by renaming only the parameter names to `a`/`b` (callers pass positionally via `use-comparison.ts`).
- Synced `openapi.json` surgically instead of a full YAML→JSON regen: the JSON copy is significantly stale (22 paths vs the YAML's 55), and a wholesale regen would have produced a large out-of-scope diff.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Dropped ambiguous `-x ktlintMain` from the verify command**
- **Found during:** Task 1 (running the unit-test verify command)
- **Issue:** `./gradlew ... -x ktlintMain` failed: `Task 'ktlintMain' is ambiguous` (candidates `ktlintMainSourceSetCheck`/`...Format`) in this Gradle 9.1 / multi-module setup.
- **Fix:** Ran the test tasks with only `-x detekt`; scoped the test filter to the correct module task (`:test` for the Ktor app, `:coroutine-viz-core:test` for the core unit tests) to avoid "No tests found".
- **Files modified:** none (command-only)
- **Verification:** Both `:coroutine-viz-core:test --tests "*ComparisonServiceTest*"` and `:test --tests "*ComparisonRoutesTest*"` green.
- **Committed in:** n/a (no source change)

**2. [Rule 3 - Blocking] Installed pinned api-types devDependencies to run the generate script**
- **Found during:** Task 2 (regenerating generated.ts)
- **Issue:** `pnpm generate` failed — `openapi-typescript: command not found`; `shared/api-types/node_modules` was missing.
- **Fix:** `pnpm install` in `shared/api-types` (installs the already-pinned `openapi-typescript@^7` / `typescript@^5.7` devDependencies — existing locked deps, not new/unknown packages, so within Rule 3 scope). Committed the resulting `pnpm-lock.yaml`.
- **Files modified:** shared/api-types/pnpm-lock.yaml (node_modules gitignored)
- **Verification:** `pnpm generate` succeeded; generated.ts contains the new path/field.
- **Committed in:** f24f531 (Task 2 commit)

**3. [Rule 3 - Blocking] Updated ComparisonView.test.tsx fixture for the new required field**
- **Found during:** Task 2 (`pnpm tsc --noEmit`)
- **Issue:** TS2741 — `mockComparison` fixture missing the now-required `distinctThreadsDiff`.
- **Fix:** Added `distinctThreadsDiff: 1` to the fixture.
- **Files modified:** frontend/src/components/comparison/ComparisonView.test.tsx
- **Verification:** `pnpm tsc --noEmit` clean.
- **Committed in:** f24f531 (Task 2 commit)

---

**Total deviations:** 3 auto-fixed (all Rule 3 - blocking)
**Impact on plan:** All three were mechanical unblocks (build command syntax, missing local deps, contract-driven fixture). No scope creep; plan intent fully realized.

## Issues Encountered
- Potential route collision between `/api/sessions/compare` and `/api/sessions/{id}` — resolved by Ktor's routing precedence (constant segments beat parameterized segments). Confirmed empirically by the passing integration test that hits the static path while `{id}` is registered first.

## User Setup Required
None - no external service configuration required.

## Next Phase Readiness
- CMPR-01 contract is live and tested; the side-by-side comparison UI work can consume `distinctThreadsDiff` from the typed client.
- Note for future api-types work: `shared/api-types/openapi.json` remains a partial copy of `documentation.yaml` (22 vs 55 paths). A full coordinated YAML→JSON regen is still pending (existing todo `shared-api-types-regeneration.md`); this plan only synced the compare slice.

## Self-Check: PASSED

- All listed created/modified files exist on disk.
- All task commits (`088d21e`, `5d4fccc`, `f24f531`) present in git history.
- Source assertions confirmed: `distinctThreadsDiff` in ComparisonService.kt, `/api/sessions/compare` in ComparisonRoutes.kt, `sessions/compare?a=` in api-client.ts.

---
*Phase: 02-user-value-visualization*
*Completed: 2026-06-14*
