---
phase: 03-persistence-auth-sharing
plan: 04
subsystem: sharing
tags: [share-tokens, exposed, ktor-rate-limit, adr-019, read-only, uuid, public-route]

# Dependency graph
requires:
  - phase: 03-01
    provides: "V1 shares table (token/created_by/access_count/last_accessed_at/nullable expiry) + SharesTable Exposed object + Database handle"
  - phase: 03-02
    provides: "authenticatedApi { } wrapper + Plan-04 share seam in Routing.kt; currentPrincipal() for createdBy"
  - phase: 03-03
    provides: "retention active-share guard semantics (expires_at IS NULL = never-expires = always active) the ShareService must keep accurate"
provides:
  - "DB-backed ShareService (create/resolve/listForSession/revoke) against SharesTable"
  - "ShareResolution sealed type (Valid|Expired|NotFound) driving the 200/410/404 status matrix"
  - "Four share endpoints: POST /api/sessions/{id}/share, GET /api/sessions/{id}/shares, DELETE .../shares/{token} (auth), GET /api/shared/{token} (public)"
  - "Per-IP RateLimit (RateLimitName(shared)) on the public read → 429 over the bucket (D-12)"
  - "Config keys: app.publicBaseUrl, share.rateLimit.{enabled,requestsPerMinute}"
  - "Database handle exposed via DatabaseKey application attribute"
affects: [05-frontend-auth]

# Tech tracking
tech-stack:
  added:
    - "io.ktor:ktor-server-rate-limit (Ktor BOM, no explicit version)"
  patterns:
    - "ShareResolution sealed interface maps DB state → HTTP status (Valid=200, Expired=410, NotFound=404 covering unknown AND revoked)"
    - "Access tracking: access_count increment + last_accessed_at stamp happen in the SAME resolve() transaction as the read (no lost-update window)"
    - "Sharing is persistence-gated (ADR-019): share routes register ONLY when storage.type=database; absent in memory mode"
    - "RateLimit install is config-gated; route wrapping mirrors the install (disabled = no install AND no rateLimit{} wrap, so no MissingPlugin crash)"
    - "Public read is OUTSIDE authenticatedApi (token = credential) and INSIDE rateLimit(shared)"

key-files:
  created:
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareService.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareDtos.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareRoutes.kt
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/share/ShareRoutesTest.kt
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/share/RateLimitTest.kt
  modified:
    - backend/build.gradle.kts
    - backend/src/main/resources/application.yaml
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/Application.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/Routing.kt
  removed:
    - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/ShareTokenService.kt
    - backend/coroutine-viz-core/src/test/kotlin/com/jh/proj/coroutineviz/session/ShareTokenServiceTest.kt

key-decisions:
  - "ShareResolution sealed interface (Valid(share)|Expired|NotFound) is the single source for the 200/410/404 matrix; NotFound covers BOTH unknown and revoked (indistinguishable by design — revoke deletes the row)"
  - "resolve() does access-tracking (count++ + last_accessed_at) ATOMICALLY in the same transaction as the row read so concurrent reads cannot lose an increment"
  - "ShareService(db, clock) takes an injectable clock so expiry is deterministic in tests (mirrors DbRetentionPolicy)"
  - "Sharing requires persistence — the Database handle is published via DatabaseKey in configureStorage(); configureRouting() builds ShareService only when present, so share routes are simply absent in memory mode (ADR-019)"
  - "Shared URL base = configured app.publicBaseUrl when set, else derived from the request origin (scheme+host[:port], default-port-aware)"
  - "RateLimit per-IP key = call.request.local.remoteHost; install + route-wrap are both config-gated by share.rateLimit.enabled so disabling skips install AND the rateLimit{} wrap (no MissingApplicationPlugin)"

patterns-established:
  - "share/ package is backend-only; the in-memory prototype lived in coroutine-viz-core and was removed to keep the SDK DB-free"
  - "Public-but-rate-limited route registered via a small registerSharedRoute() helper that conditionally wraps in rateLimit{}"

requirements-completed: [SHAR-01, SHAR-02]

# Metrics
duration: ~30min
completed: 2026-06-21
---

# Phase 3 Plan 04: Session Sharing Summary

**Revocable, expiring, per-IP-rate-limited share tokens for read-only session access (SHAR-01/SHAR-02, ADR-019): the in-memory `ShareTokenService` prototype is replaced by a DB-backed `ShareService` over the V1 `shares` table, exposing four endpoints — owner mint/list/revoke inside `authenticatedApi`, and a PUBLIC `GET /api/shared/{token}` (the token IS the credential) wrapped in a per-IP `RateLimit` scope that returns 429 over the bucket. A `ShareResolution` sealed type drives the ADR-019 200/410/404 status matrix, never-expiry maps to a null `expires_at` (keeping the Plan 03 retention active-share guard correct), and access tracking (count + last-accessed) is stamped atomically in the read transaction.**

## Performance
- **Duration:** ~30 min
- **Started:** 2026-06-21T08:17Z (approx)
- **Completed:** 2026-06-21
- **Tasks:** 2
- **Files modified:** 11 (5 created, 4 modified, 2 removed)

## Accomplishments
- `share/ShareService.kt` — DB-backed lifecycle over `SharesTable`: `create(sessionId, createdBy, expiry)` (UUID v4 token, `READ_ONLY` permission, `access_count=0`, expiry → `now + N days` or null for `never`), `resolve(token): ShareResolution` (Valid/Expired/NotFound, with atomic access tracking), `listForSession`, `revoke(sessionId, token)` scoped to both ids (T-03-16).
- `share/ShareDtos.kt` — `ShareExpiry` enum (`1d`/`7d`/`30d`/`never`, D-11) with server-side validation, `CreateShareRequest/Response`, `ShareSummary`, `SharedSessionResponse{session, events}`. Invalid `expiresIn` → 400.
- `share/ShareRoutes.kt` — owner routes (`POST /api/sessions/{id}/share` 201 / 404-on-unknown-session, `GET .../shares` 200, `DELETE .../shares/{token}` 204/404) and the public `GET /api/shared/{token}` (Valid→200 `{session, events}` reusing the SSE serializer; Expired→410; unknown/revoked→404).
- `Routing.kt` — owner routes registered INSIDE `authenticatedApi { }`, public read OUTSIDE and wrapped in `rateLimit(RateLimitName("shared"))`; both present only when persistence is on.
- `Application.kt` — `DatabaseKey` publishes the Exposed handle; `configureRateLimit()` installs the per-IP `RateLimit` scope (default 60/min, config-gated) before routing.
- Prototype `ShareTokenService` (in-memory, in `coroutine-viz-core`) and its test removed — the SDK stays DB-free.
- Tests: `ShareRoutesTest` (10, full status matrix + access tracking + never-expiry) and `RateLimitTest` (3, 429 over bucket / normal under / scoped to shared route). Full `:test` + `:coroutine-viz-core:test` suites green; all new files ktlint-clean.

## Task Commits
1. **Task 1: DB-backed ShareService + four share endpoints (ADR-019 status matrix)** — `b13d571` (feat)
2. **Task 2: per-IP RateLimit on the public shared read (429, D-12)** — `588e7dc` (feat)

_TDD note: per the Plan-01 precedent, each task's impl and its test were authored together because the test references the impl under test; the RED→GREEN cycle ran within each task (tests fail to compile/resolve without the impl, then pass once wired)._

## Files Created/Modified
- `backend/.../share/ShareService.kt` — DB-backed `ShareToken` lifecycle + `ShareResolution` (created)
- `backend/.../share/ShareDtos.kt` — `ShareExpiry` enum + request/response/summary DTOs (created)
- `backend/.../share/ShareRoutes.kt` — owner + public share routes (created)
- `backend/.../share/ShareRoutesTest.kt`, `RateLimitTest.kt` — SHAR-01/02 + D-12 coverage (created)
- `backend/build.gradle.kts` — `ktor-server-rate-limit` (BOM)
- `backend/src/main/resources/application.yaml` — `app.publicBaseUrl`, `share.rateLimit.{enabled,requestsPerMinute}`
- `backend/.../Application.kt` — `DatabaseKey`, `SHARED_RATE_LIMIT_NAME`, `SharedRateLimitEnabledKey`, `configureRateLimit()`
- `backend/.../Routing.kt` — share-route registration (owner inside auth, public outside + rate-limited)
- removed: `coroutine-viz-core/.../session/ShareTokenService.kt` + `ShareTokenServiceTest.kt`

## Decisions Made
- **ShareResolution sealed type → status matrix:** `resolve()` returns `Valid(share)` (200, after access tracking), `Expired` (410), or `NotFound` (404). `NotFound` covers BOTH an unknown token AND a revoked one — revoke deletes the row, so the two are indistinguishable by design (no oracle for "this token used to exist").
- **Atomic access tracking:** the `access_count` increment and `last_accessed_at` stamp run in the SAME Exposed transaction as the row read inside `resolve()`. Reading then updating in one transaction closes the lost-update window two concurrent reads would otherwise have.
- **Shared-URL base config:** the link is built from `app.publicBaseUrl` when configured; otherwise it is derived from the request origin (`scheme://host[:port]`, omitting the port for the scheme's default). Recorded as `{base}/shared/{token}`.
- **Persistence-gated sharing:** `configureStorage()` publishes the `Database` via `DatabaseKey`; `configureRouting()` builds `ShareService` only when that attribute is present. In memory mode the four share routes simply do not register (ADR-019 requires the `shares` table). This avoids a half-wired share surface backed by no store.
- **Config-gated RateLimit (install + wrap together):** `configureRateLimit()` reads `share.rateLimit.enabled`; when false it skips `install(RateLimit)` AND records the flag so `Routing.kt` skips the `rateLimit{}` wrap — otherwise wrapping a route in an uninstalled scope throws `MissingApplicationPlugin`. When enabled, the per-IP bucket keys on `call.request.local.remoteHost` and exceeding it returns 429 + `Retry-After` automatically.

## Deviations from Plan
None — plan executed as written. The impl-with-test sequencing within each task follows the documented Plan-01 precedent (the task's test references the task's impl), not a scope change.

## Authentication Gates
None — no external auth/login was required during execution.

## Issues Encountered
- **`--tests` filter across subprojects:** running `./gradlew test --tests "*ShareRoutesTest"` fails in `:coroutine-viz-core` ("No tests found for given includes"). Scoping to the backend project (`./gradlew :test --tests ...`) is the correct invocation. Not a code issue.
- **detekt** still cannot run locally (JVM-target incompatibility, detekt 1.23.7 max JVM 22 vs local JVM 24) — pre-existing/environmental, logged in `deferred-items.md` (03-01). ktlint is clean on all new/modified files.
- **Pre-existing warnings** (`ScenarioRunnerRoutes.kt:451` Timer nullability; `AuthTest.kt` Windows-filename `?`) are untouched and out of scope.

## Reverse-proxy deploy caveat (XForwardedHeaders)
The per-IP RateLimit bucket keys on `call.request.local.remoteHost`. **Behind a reverse proxy / load balancer, install Ktor's `XForwardedHeaders` plugin** so `remoteHost` resolves to the real client IP from `X-Forwarded-For` — otherwise every viewer shares one bucket (the proxy's IP) and a single busy viewer throttles everyone. This is a DEPLOYMENT configuration concern and is intentionally NOT wired here (documented in `configureRateLimit()`); it should be added when fronting the app with a proxy.

## Known Stubs
None. All four endpoints are wired against the DB store and test-covered; the public read returns the real `{session, events}` snapshot, and rate limiting is live (config-gated, default 60/min).

## Threat Flags
None — all new surface was enumerated in the plan `<threat_model>` (T-03-13..17, T-03-SC) and dispositioned: UUID v4 tokens + per-IP RateLimit → 429 (T-03-13), Exposed-DSL parameterized share queries (T-03-14), revocable+expiring tokens (T-03-15 accept), owner routes inside `authenticatedApi` with `createdBy` from principal and revoke scoped to {sessionId, token} (T-03-16), retention active-share guard preserved via accurate `expires_at` (T-03-17, Plan 03), and `ktor-server-rate-limit` is JetBrains first-party from the Ktor BOM (T-03-SC).

## Next Phase Readiness
- **Plan 05 (frontend auth/sharing UI):** the four endpoints match ADR-019 exactly — the Share modal POSTs `{expiresIn}` and renders the returned `{token, url, expiresAt}`; the read-only view fetches `GET /api/shared/{token}` (no credential) and renders `{session, events}`; the share list/revoke bind to `GET/DELETE /api/sessions/{id}/shares[/{token}]`. `app.publicBaseUrl` should be set in prod so the returned `url` is absolute and correct.
- **Retention coupling:** the ShareService keeps `expires_at` accurate (null = never), so the Plan 03 `DbRetentionPolicy` active-share guard remains correct — a session with a live share is never age-deleted.

## Self-Check: PASSED

All created files exist on disk (ShareService.kt, ShareDtos.kt, ShareRoutes.kt, ShareRoutesTest.kt, RateLimitTest.kt); both removed prototype files are gone; both task commits (`b13d571`, `588e7dc`) are present in git history. Full backend + core test suites green; ktlint clean on all new/modified files.

---
*Phase: 03-persistence-auth-sharing*
*Completed: 2026-06-21*
