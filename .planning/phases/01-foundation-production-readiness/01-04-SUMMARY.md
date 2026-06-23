---
phase: 01-foundation-production-readiness
plan: "04"
subsystem: backend-health-observability
tags: [kotlin, ktor, health-probes, cors, openapi, observability, prod-readiness]

dependency_graph:
  requires:
    - phase: 01-03
      provides: "Session fork deleted — HealthRoutes.kt reads SessionManager from core only"
  provides:
    - "PROD-01: /api/health, /api/live, /api/ready + /health alias; HealthStatus with version + components"
    - "PROD-03: CORS config-read regression test (CorsConfigTest) — proves origins from config, not literals"
    - "PROD-04: OpenAPI spec documents touched endpoints and passes redocly lint (0 errors)"
  affects:
    - "01-05 (ADR-020 metrics) — HealthStatus.version constant available for metrics labels"

tech-stack:
  added:
    - "redocly CLI (npm global, dev-time-only OpenAPI linter)"
  patterns:
    - "ApplicationCall extension suspend fun respondHealth() — shared helper to avoid route duplication"
    - "global security: [] in OpenAPI spec — explicit no-auth declaration for Phase 1 (ADR-016 planned for Phase 3)"
    - "testApplication with Origin header assertion pattern for CORS regression testing"

key-files:
  created:
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/CorsConfigTest.kt
  modified:
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/HealthRoutes.kt
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/HealthRoutesTest.kt
    - backend/src/main/resources/openapi/documentation.yaml

key-decisions:
  - "APP_VERSION constant '0.0.1' hardcoded from build.gradle.kts version string — simplest non-empty version per CONTEXT D-list (processResources token would be heavier; constant is correct for Phase 1)"
  - "OpenAPI security: [] global declaration silences the security-defined rule without adding real auth (ADR-016 is Phase 3); the comment in the spec documents the intent"
  - "redocly lint installed globally (npm install -g @redocly/cli@latest) — dev-time only, not a runtime dep"
  - "/api/sessions/{id}/events was already in the spec (lines 241-271) — no change needed for that path"
  - "ValidationResult and TimingReport schemas were already correct — no corrections needed for PROD-04"

requirements-completed: [PROD-01, PROD-03, PROD-04]

duration: ~10min
completed: "2026-06-11"
---

# Phase 01 Plan 04: Health Endpoints + CORS Regression Test + OpenAPI Spec Summary

**Extended HealthRoutes.kt with /api/health, /api/live, /api/ready (+ /health alias), added CorsConfigTest proving CORS origins come from config, and updated documentation.yaml to document all touched endpoints while passing redocly lint with 0 errors.**

## Performance

- **Duration:** ~10 min
- **Started:** 2026-06-11T14:00:00Z
- **Completed:** 2026-06-11T14:10:00Z
- **Tasks:** 3
- **Files modified/created:** 4 (2 modified source, 1 modified spec, 1 test created)

## Accomplishments

- Extended `HealthStatus` with `version: String` and `components: Map<String, String>` fields; extracted shared `respondHealth()` suspend helper; added `GET /api/health` (full health), `GET /api/live` (liveness probe, always 200 UP), `GET /api/ready` (readiness probe, 503 on memory >= 95%); kept `GET /health` as backwards-compatible alias (PROD-01)
- Created `CorsConfigTest.kt`: boots `module()`, sends request with `Origin: http://localhost:3000` (configured default) and asserts `Access-Control-Allow-Origin` is echoed back; second test asserts `http://evil.example.com` (not in config) does NOT get the header — proving config-driven CORS cannot silently regress to a wildcard (PROD-03)
- Updated `documentation.yaml`: added `/api/health`, `/api/live`, `/api/ready` path entries, adjusted `/health` as alias, updated `HealthStatus` schema with `version` + `components`, added `security: []` global declaration; redocly lint exits 0 — 0 errors (PROD-04)
- Full backend test suite: BUILD SUCCESSFUL (0 failures; all previously-green tests continue to pass)

## Task Commits

1. **Task 1: Health routes + tests** - `65dd9b0` (feat)
2. **Task 2: CORS regression test** - `b6167b4` (feat)
3. **Task 3: OpenAPI spec update** - `840ffa4` (feat)

## Files Created/Modified

- `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/HealthRoutes.kt` — extended HealthStatus, added respondHealth() helper, added /api/health|live|ready routes, kept /health alias
- `backend/src/test/kotlin/com/jh/proj/coroutineviz/routes/HealthRoutesTest.kt` — added 4 new test cases covering /api/health (version + components), /api/live (200 UP), /api/ready (200 UP), /health alias (200 with version + components)
- `backend/src/test/kotlin/com/jh/proj/coroutineviz/CorsConfigTest.kt` — new; two tests: configured origin gets ACAO header; non-configured origin does not
- `backend/src/main/resources/openapi/documentation.yaml` — added /api/health, /api/live, /api/ready paths; updated /health description; updated HealthStatus schema; added global security: []

## Decisions Made

- **APP_VERSION constant**: hardcoded `"0.0.1"` from `build.gradle.kts` version field — simplest acceptable non-empty version. `processResources` token injection would be heavier and Phase 1 scope doesn't require it.
- **OpenAPI security: [] global**: Adds `security: []` at the spec root so the `security-defined` lint rule is satisfied. The comment explicitly calls out that auth (ADR-016) is Phase 3. This is NOT adding actual auth — it is an explicit declaration that the API is intentionally unauthenticated in Phase 1.
- **/api/sessions/{id}/events already documented**: The spec had the events path since initial authoring. No change needed.
- **ValidationResult/TimingReport schemas already correct**: The existing spec schemas match the backend `ValidationResult` sealed class (Pass/Fail discriminator) and `TimingReport` data class exactly. No corrections needed.

## Deviations from Plan

None — plan executed exactly as written. The `/api/sessions/{id}/events` note in the plan was accurate (already documented). `ValidationResult`/`TimingReport` spec schemas were already correct.

## OpenAPI Validation

**Validator command:** `redocly lint backend/src/main/resources/openapi/documentation.yaml`
**Result:** `Your API description is valid.` — exits 0
**Warnings:** 48 pre-existing `operation-4xx-response` warnings on scenario endpoints that don't declare error responses (intentional — these are fire-and-forget scenario triggers, not added in this plan)
**Errors:** 0

## Known Stubs

None — all new code paths are fully wired:
- `APP_VERSION = "0.0.1"` is a real value from `build.gradle.kts`, not a placeholder
- `components` map includes real runtime values (`sessionManager: UP`, `memory: UP/DEGRADED`)
- CORS test uses the actual configured default origin from `application.yaml`

## Threat Flags

None — the new endpoints (`/api/health`, `/api/live`, `/api/ready`) are in the plan's threat model as T-01-09 (accepted: no secrets/PII exposed, acceptable for local/dev deployment). T-01-10 (CORS regression) is mitigated by CorsConfigTest. No new trust boundaries introduced.

## Self-Check: PASSED

- `HealthRoutes.kt`: respondHealth() helper — CONFIRMED
- `HealthRoutes.kt`: /health alias — CONFIRMED (line 70)
- `HealthRoutes.kt`: /api/health, /api/live, /api/ready — CONFIRMED (lines 72-89)
- `HealthStatus.version` and `HealthStatus.components` fields — CONFIRMED
- `HealthRoutesTest.kt`: 7 tests (3 original + 4 new) — CONFIRMED
- `CorsConfigTest.kt`: EXISTS — CONFIRMED
- `documentation.yaml`: /api/health, /api/live, /api/ready paths — CONFIRMED
- `documentation.yaml`: HealthStatus schema with version + components — CONFIRMED
- `documentation.yaml`: security: [] — CONFIRMED
- `redocly lint` exits 0 — CONFIRMED
- Backend test suite: BUILD SUCCESSFUL — CONFIRMED
- Commits 65dd9b0, b6167b4, 840ffa4: ALL EXIST

*Phase: 01-foundation-production-readiness*
*Completed: 2026-06-11*
