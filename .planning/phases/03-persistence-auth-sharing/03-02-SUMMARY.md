---
phase: 03-persistence-auth-sharing
plan: 02
subsystem: auth
tags: [ktor-auth, jwt, api-key, sha-256, argon2, password4j, fail-open, sse, rbac]

# Dependency graph
requires:
  - phase: 03-01
    provides: "V1 Flyway schema (shares, tenant_id front-loaded); SessionStore/EventStore seams"
  - phase: 01-foundation
    provides: "Routing.kt route registry; SessionRoutes SSE stream handler"
provides:
  - "Dual-provider auth: SHA-256 multi-key (AUTH-02) + config-seeded-user JWT (AUTH-03)"
  - "Fail-open authenticatedApi() — pure pass-through when fully unconfigured (D-04a)"
  - "Either-credential enforcement on protected routes (X-API-Key OR Bearer/JWT, D-08)"
  - "SSE ?token= query-param JWT extraction (locked cross-plan contract for Plan 05)"
  - "POST /api/auth/token (Argon2id verify, uniform 401, no enumeration)"
  - "currentPrincipal() helper resolving ApiKeyPrincipal|UserPrincipal for Plan 03 tenancy"
  - "auth/ package: Role, ApiKeyPrincipal, UserPrincipal, ApiKeyStore, UserStore, JwtConfig"
affects: [03-03-tenancy-retention, 03-04-sharing, 05-frontend-auth]

# Tech tracking
tech-stack:
  added:
    - "io.ktor:ktor-server-auth-jwt (Ktor BOM, no explicit version)"
    - "com.password4j:password4j:1.8.2 (Argon2id verify)"
  patterns:
    - "authDisabled computed ONCE at startup = (no keys AND no usable jwt) → single fail-open branch"
    - "jwt provider authHeader { } falls back to ?token= query param when no Bearer header (Pitfall 2)"
    - "Constant-time SHA-256 compare via MessageDigest.isEqual over hex bytes (never plaintext)"
    - "Uniform 'Invalid credentials' for unknown-user AND bad-password (no enumeration, T-03-07)"
    - "configListOrEmpty() treats an absent config list as empty (MapApplicationConfig vs YAML parity)"

key-files:
  created:
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/auth/Principals.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/auth/ApiKeyStore.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/auth/UserStore.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/auth/JwtConfig.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/AuthRoutes.kt
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/JwtAuthTest.kt
  modified:
    - backend/build.gradle.kts
    - backend/src/main/resources/application.yaml
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/Auth.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/Routing.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/AuthTest.kt

key-decisions:
  - "SSE auth = jwt provider reads the `?token=<jwt>` query param (exact name `token`, SSE_TOKEN_QUERY_PARAM) via authHeader{} fallback; this is the LOCKED cross-plan contract Plan 05 binds to"
  - "currentPrincipal(call): Principal? returns ApiKeyPrincipal|UserPrincipal — Plan 03 tenancy resolves tenantId from UserPrincipal.userId / ApiKeyPrincipal.name"
  - "Refresh tokens DEFERRED this phase (only short-lived access tokens issued); server-side refresh map/persist is a later plan's concern (RESEARCH Open Q1)"
  - "JWT algorithm: RS256 when PEM paths set, else HMAC256 when secret set, else INERT (no sign/verify); explicit Algorithm, never alg=none (T-03-05)"
  - "Legacy auth.apiKey (raw key) kept for back-compat: hashed at load, added as a RUNNER KeyEntry"
  - "authenticatedApi() pass-through branch avoids any auth plugin in the path when disabled (cleaner than anonymous-principal trick; D-04a, Pitfall 1)"

patterns-established:
  - "configListOrEmpty(config, path) helper: absent list = empty list, not a startup crash"
  - "All auth surface lives in :backend; no auth/DB deps leak into coroutine-viz-core SDK"

requirements-completed: [AUTH-01, AUTH-02, AUTH-03, AUTH-05]

# Metrics
duration: ~13min
completed: 2026-06-21
---

# Phase 3 Plan 02: Auth Layer Summary

**A dual-provider auth layer — SHA-256 multi-key (AUTH-02) plus config-seeded-user JWT (AUTH-03) — wrapped so non-public routes accept EITHER credential (D-08) and FAIL OPEN to fully-public when nothing is configured (D-04a), with the authenticated SSE stream authenticating via a `?token=<jwt>` query param because browser EventSource cannot set a Bearer header.**

## Performance
- **Duration:** ~13 min
- **Started:** 2026-06-21T07:59:48Z
- **Completed:** 2026-06-21
- **Tasks:** 2
- **Files modified:** 12 (6 created, 6 modified)

## Accomplishments
- New `com.jh.proj.coroutineviz.auth` package: `Role` (VIEWER/RUNNER/ADMIN), `ApiKeyPrincipal(name, role)`, `UserPrincipal(userId, role)`, `ApiKeyStore` (constant-time SHA-256 compare, multi-key rotation), `UserStore` (config-seeded, no CRUD), `JwtConfig` (HMAC dev / RS256 prod, inert when unconfigured).
- `POST /api/auth/token` (always public): Argon2id verify via password4j, uniform `{"error":"Invalid credentials"}` for unknown-user AND bad-password (no enumeration, T-03-07), issues `{token, expiresAt}` with claims `sub`/`role`/`iat`/`exp`.
- Rewrote `configureAuth()` as a dual-provider install; `authDisabled` computed once → `authenticatedApi()` is a pure pass-through when nothing is configured (D-04a), else `authenticate("api-key", "jwt")` so EITHER credential satisfies the request (D-08).
- `Routing.kt` wraps the 9 non-public route registrations in a single `authenticatedApi { }`; root/health/`POST /api/auth/token` stay public; a Plan 04 seam comment marks where the public `GET /api/shared/{token}` route registers.
- SSE auth: the jwt provider's `authHeader { }` falls back to the `?token=<jwt>` query param (param name `token`) so the protected SSE stream authenticates without a Bearer header (Pitfall 2).
- Tests: new `JwtAuthTest` (9 — token issue/reject, Bearer accept/reject, ApiKeyStore units) + extended `AuthTest` (12 — fail-open pass-through, public allowlist, SHA-256 key matrix, either-credential, SSE `?token=` accept + missing/garbage 401). AUTH-05 E2E matrix green.

## Task Commits
1. **Task 1: auth/ package — ApiKeyStore, UserStore, JwtConfig, Principals, token endpoint** - `ff500ed` (feat)
2. **Task 2: dual-provider fail-open configureAuth/authenticatedApi, route wrap, SSE ?token=, AuthTest** - `72daa10` (feat)
3. _docs: deferred-items residual ktlint note_ - `8e6a7d6` (docs)

## Files Created/Modified
- `backend/build.gradle.kts` - ktor-server-auth-jwt (BOM) + password4j 1.8.2 (:backend only)
- `backend/src/main/resources/application.yaml` - `auth.keys[]`, `auth.users[]`, `auth.jwt.{...}` blocks (env-interpolated; legacy `auth.apiKey` retained)
- `backend/.../auth/Principals.kt` - Role enum + ApiKeyPrincipal/UserPrincipal
- `backend/.../auth/ApiKeyStore.kt` - SHA-256 constant-time store + `configListOrEmpty` helper
- `backend/.../auth/UserStore.kt` - config-seeded user store (no CRUD)
- `backend/.../auth/JwtConfig.kt` - HMAC/RS256 sign + verifier; inert when unconfigured
- `backend/.../routes/AuthRoutes.kt` - `POST /api/auth/token` (Argon2id, uniform 401)
- `backend/.../Auth.kt` - dual-provider `configureAuth()`, fail-open `authenticatedApi()`, `currentPrincipal()`, `SSE_TOKEN_QUERY_PARAM`
- `backend/.../Routing.kt` - non-public routes wrapped; token endpoint + Plan 04 share seam public
- `backend/.../routes/SessionRoutes.kt` - documented the SSE `?token=` auth contract on the stream handler
- `backend/.../JwtAuthTest.kt`, `AuthTest.kt` - AUTH-03/AUTH-05 coverage

## Decisions Made
- **SSE token query param (locked cross-plan contract):** the jwt provider reads the JWT from the `token` query parameter (`?token=<jwt>`) on the protected SSE stream route `/api/sessions/{id}/stream`, via a custom `authHeader { }` that prefers the `Authorization: Bearer` header and falls back to the query param. The exact param name `token` is exported as `SSE_TOKEN_QUERY_PARAM` and is the contract **Plan 05's frontend EventSource URL must bind to** — renaming it breaks the authenticated live stream.
- **Principal-resolution helper (Plan 03 dependency):** `fun ApplicationCall.currentPrincipal(): Principal?` returns the active `ApiKeyPrincipal` or `UserPrincipal`. Plan 03 tenancy resolves `tenantId` from `UserPrincipal.userId` (JWT `sub`) or `ApiKeyPrincipal.name`; ADMIN bypasses the filter. When auth is off, `currentPrincipal()` is null and tenancy is global (D-04b).
- **Refresh-token deferral:** only short-lived access tokens are issued this phase (RESEARCH Open Q1). No refresh endpoint/store yet; a server-side (in-memory or persisted) refresh map is a later plan's concern. Documented so Plan 05 does not assume a refresh flow.
- **JWT algorithm resolution:** RS256 when `auth.jwt.privateKeyPath`+`publicKeyPath` are set (prod), else HMAC256 when `auth.jwt.secret` is set (dev), else INERT — no signing/verifying material means the jwt provider contributes nothing to the fail-open toggle. Explicit `Algorithm` always (never `none`) is the T-03-05 alg-confusion mitigation.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `configList` crashes on an absent list under MapApplicationConfig**
- **Found during:** Task 2 (running extended AuthTest)
- **Issue:** `ApplicationConfig.configList("auth.keys")` throws `Property auth.keys.size not found` when the list key was never populated. The YAML provider exposes `auth.keys: []` natively, but `MapApplicationConfig` (and any minimal config) does not — so a fresh/minimal config would crash auth startup instead of meaning "no entries" (breaking D-04a fail-open).
- **Fix:** Added `internal fun configListOrEmpty(config, path)` that catches `ApplicationConfigurationException` and returns an empty list; `ApiKeyStore`/`UserStore` use it. `JwtConfig.fromConfig` likewise guards `config.config("auth.jwt")`.
- **Files modified:** ApiKeyStore.kt, UserStore.kt, JwtConfig.kt
- **Verification:** fail-open pass-through + all configured cases green
- **Committed in:** ff500ed / 72daa10

**2. [Process] ktlintFormat reformatted touched files (incl. pre-existing SessionRoutes violations)**
- **Reason:** `./gradlew ktlintFormat` auto-corrected multiline-expression-wrapping in the auth files AND incidentally fixed the pre-existing `routes/SessionRoutes.kt` main-source violations (logged deferred by 03-01) because Plan 02 edited that file. No behavior change; all tests re-verified green after formatting.

## Authentication Gates
None — no external auth/login was required during execution.

## Issues Encountered
- **detekt** still cannot run locally (`Invalid value (24) passed to --jvm-target`; detekt 1.23.7 max JVM 22 vs local JVM 24) — pre-existing/environmental, logged in `deferred-items.md` (03-01).
- **ktlint (test, residual):** two inline-comment violations in untouched test files (`MetricsWiringTest.kt:100`, `VizScopeCompletionHandlerTest.kt:41`) are flagged `cannot be auto-corrected` and remain — out of scope, logged in `deferred-items.md`. All Plan 02 auth files (main + test) are ktlint-clean.
- **Parallel test-worker flakiness:** a single `CompressionTest` 503 appeared under parallel workers (concurrent `module()` startup), identical to the documented Phase-2 pre-existing/environmental flake. Full suite is green with `--max-workers=1`; the failing test passes in isolation. Not auth-related.

## Known Stubs
None. The token endpoint, both stores, the dual provider, and the SSE query-param path are fully wired and test-covered. Refresh tokens are an explicit deferral (documented above), not a stub — no placeholder code path exists for them.

## Threat Flags
None — all new surface (api-key validation, JWT verification, token endpoint, SSE token-in-query-param) was enumerated in the plan `<threat_model>` (T-03-04/05/06/07/08/SC) and dispositioned. Mitigations applied: constant-time SHA-256 (T-03-04), explicit JWT Algorithm never `none` (T-03-05), deny-by-default-when-configured + proven fail-open-when-unconfigured (T-03-06), uniform credentials error (T-03-07); SSE token-in-query-param is the accepted/documented tradeoff (T-03-08).

## Next Phase Readiness
- **Plan 03 (tenancy/retention):** `currentPrincipal()` is the documented seam to resolve `tenantId`; the `tenant_id` column from 03-01 is ready to populate.
- **Plan 04 (sharing):** the public `GET /api/shared/{token}` route registers OUTSIDE `authenticatedApi { }` — seam comment in place in `Routing.kt`.
- **Plan 05 (frontend auth):** the SSE stream URL binds to `?token=<jwt>` (param name `token`); the api-client attaches `Authorization: Bearer` for normal fetches and omits credentials when auth is off (D-07/D-08). Refresh-token flow is deferred — Plan 05 must not assume one.

## Self-Check: PASSED

All created files exist on disk; both feature commits (`ff500ed`, `72daa10`) present in git history. (Verified below.)

---
*Phase: 03-persistence-auth-sharing*
*Completed: 2026-06-21*
