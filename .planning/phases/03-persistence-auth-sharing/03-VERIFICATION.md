---
phase: 03-persistence-auth-sharing
verified: 2026-06-21T00:00:00Z
status: gaps_found
score: 7/10 must-haves verified
overrides_applied: 0
gaps:
  - truth: "Sessions are tenant-isolated — no cross-tenant reads (AUTH-04, success criterion #2)"
    status: failed
    reason: >-
      Tenant isolation is enforced ONLY on the four top-level CRUD routes. Every
      per-session sub-resource read and the SSE stream call the UNSCOPED
      SessionManager.getSession(sessionId), which resolves with
      TenantContext.Unscoped (Op.TRUE — no filter). Tenant A can read tenant B's
      events, hierarchy, threads, coroutine timeline, and live stream by knowing
      the session id. Confirmed against live code, not just the review.
    artifacts:
      - path: "backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt"
        issue: >-
          Lines 144 (/events), 161 (/hierarchy), 180 (/threads), 204
          (/coroutines/{id}/timeline), 231 (sse /stream) all call
          SessionManager.getSession(sessionId) WITHOUT call.resolveTenant().
          Only lines 42, 63, 75, 115 (POST/GET/GET-one/DELETE /api/sessions)
          route through tenantScopedStore() + call.resolveTenant().
      - path: "backend/src/main/kotlin/com/jh/proj/coroutineviz/persistence/ExposedSessionStore.kt"
        issue: >-
          Line 46: getSession(sessionId) delegates to getSession(sessionId,
          TenantContext.Unscoped). Line 148: tenantPredicate(Unscoped) returns
          Op.TRUE — no tenant filter, so the unscoped fallback serves any
          tenant's session.
    missing:
      - "Route /events, /hierarchy, /threads, /coroutines/{id}/timeline, and the sse /stream handler through tenantScopedStore().getSession(sessionId, call.resolveTenant()); respond 404 (pre-stream for SSE) on null."
      - "Do not call the unscoped SessionManager.getSession from any authenticated session-bound route."
  - truth: "A developer can mint/list/revoke shares only on sessions they own (ownership enforcement for AUTH-04 / success criterion #2)"
    status: failed
    reason: >-
      Share owner routes verify only session EXISTENCE (also unscoped) and never
      ownership. Any authenticated user can mint a public, never-expiring share
      link for another tenant's session (exfiltrating its full event history via
      the public /api/shared/{token} route), and can list/revoke any other
      user's shares. The created_by column is recorded but never enforced.
    artifacts:
      - path: "backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareRoutes.kt"
        issue: >-
          Line 60: POST /api/sessions/{id}/share checks SessionManager.getSession
          (unscoped) for existence, no ownership/tenant check. Line 96:
          listForSession(sessionId) filters on session_id only. Line 118:
          revoke(sessionId, token) — no created_by/principal scoping.
      - path: "backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareService.kt"
        issue: "revoke/listForSession query by session_id/token only; created_by recorded but never used as an authorization predicate."
    missing:
      - "Verify session ownership via the tenant-scoped store (store.getSession(sessionId, call.resolveTenant()) != null) before minting a share; return 404 otherwise."
      - "Scope listForSession and revoke by created_by (or verified session ownership) so a principal can only see/revoke shares it owns."
  - truth: "End-to-end tests cover tenant isolation on all session-bound read paths (AUTH-05 coverage of the isolation promise)"
    status: partial
    reason: >-
      TenantIsolationTest exercises only top-level list/read/delete and the
      principal-to-tenant resolution logic. It does NOT test cross-tenant access
      on /events, /hierarchy, /threads, /timeline, /stream, or the share owner
      routes — which is exactly why the suite is green despite the CR-01/CR-02
      data-leak gaps. AUTH-05 tests exist and pass, but the coverage does not
      guard the central tenant-isolation invariant of the phase.
    artifacts:
      - path: "backend/src/test/kotlin/com/jh/proj/coroutineviz/auth/TenantIsolationTest.kt"
        issue: "No test for cross-tenant access of session sub-resources or share owner routes; sub-resource isolation is unverified."
    missing:
      - "Add e2e tests asserting tenant B gets 404 on tenant A's /events, /hierarchy, /threads, /coroutines/{id}/timeline, /stream."
      - "Add e2e tests asserting a non-owner cannot mint/list/revoke shares on another tenant's session."
human_verification: []
---

# Phase 3: Persistence, Auth & Sharing Verification Report

**Phase Goal:** vizcore is safe to deploy for multiple users — sessions can persist across restarts, every non-public route enforces authentication with tenant isolation, and sessions can be shared as read-only links.
**Verified:** 2026-06-21
**Status:** gaps_found
**Re-verification:** No — initial verification

## Goal Achievement

The goal has three pillars. Two are delivered; the third — the central security
promise, **tenant isolation** — is NOT enforced on most read paths, so the phase
is NOT safe to deploy for multiple users.

1. **Persistence across restarts** — DELIVERED. Exposed + HikariCP + Flyway,
   selectable via `storage.type=database`, events persisted and read back through
   the same `PolymorphicSerializer(VizEvent::class)`, retention policy runs in the
   background with an active-share guard. (One sub-criterion deviation: events are
   stored as CLOB/TEXT, not JSONB — see WARNING below.)
2. **Authentication on non-public routes** — DELIVERED. SHA-256 constant-time key
   compare, Argon2id password verify, JWT with VIEWER/RUNNER/ADMIN roles, explicit
   algorithm (no `none`), `/health` `/openapi.json` `/api/auth/token` stay public,
   covered by e2e tests.
3. **Tenant isolation + read-only sharing** — PARTIALLY DELIVERED. Sharing UX,
   token lifecycle, rate-limiting, and public read view work. But tenant isolation
   leaks on every session sub-resource and the entire share-owner surface
   (CR-01/CR-02, confirmed in live code). Success criterion #2's "sessions are
   tenant-isolated" is **not** met.

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | PERS-01: JDBC store (Exposed+HikariCP, H2/PG) behind the Session/Event store seam, selectable via `storage.type=database` | ✓ VERIFIED | `DatabaseFactory.kt` (Hikari+Flyway+Database.connect); `ExposedSessionStore`/`ExposedEventStore` implement the interfaces; `Application.kt:93-96` storage.type branch wires it. |
| 2 | PERS-02: Sessions + events survive a backend restart (Flyway schema, persisted payload) | ✓ VERIFIED | `V1__core_schema.sql` sessions/events tables; `ExposedEventStore` round-trips via `appJson`+`PolymorphicSerializer`; `PersistenceRestartTest.kt` exists. NOTE: payload is CLOB/TEXT not JSONB (WARNING). |
| 3 | PERS-03: Retention (max-age TTL + max-events trim) runs as a background process, never deletes a session with an active share | ✓ VERIFIED | `DbRetentionPolicy.kt` max-age delete with `NOT EXISTS` active-share subquery + per-session event trim; launched from `Application.kt:121-135`. (CR-04 correctness edge on duplicate `seq` noted as INFO — schema lacks unique `(session_id, seq)`.) |
| 4 | AUTH-01: Non-public routes wrapped in `authenticatedApi()`; with API key set, no/wrong X-API-Key → 401; `/health` `/openapi.json` `/api/auth/token` stay open | ✓ VERIFIED | `Auth.kt` `authenticatedApi {}`; `Routing.kt:59` wraps protected routes; public routes registered outside; `AuthTest.kt` exists. |
| 5 | AUTH-02: API keys compared as SHA-256 hashes via constant-time `MessageDigest.isEqual`, never plaintext | ✓ VERIFIED | `ApiKeyStore.kt` SHA-256 hex compare via `MessageDigest.isEqual`. (WR-05 timing-claim nuance is INFO, not a functional fail.) |
| 6 | AUTH-03: JWT (`/api/auth/token`, HMAC dev/RS256 prod) issues a `UserPrincipal` with VIEWER/RUNNER/ADMIN | ✓ VERIFIED | `JwtConfig.kt` HMAC256/RS256, explicit alg (no `none`); `Principals.kt` role enum; `AuthRoutes.kt:38-53` token endpoint + Argon2id verify; `JwtAuthTest.kt` exists. Frontend: Bearer injection, 401→/login, SSE `?token=`. |
| 7 | AUTH-04: Sessions filtered by authenticated user — no cross-tenant reads | ✗ FAILED | Tenant filter only on 4 top-level routes. `SessionRoutes.kt:144,161,180,204,231` call unscoped `SessionManager.getSession` → `ExposedSessionStore.kt:46`→`tenantPredicate(Unscoped)=Op.TRUE`. Cross-tenant read of events/hierarchy/threads/timeline/stream. ShareRoutes owner paths also unscoped (no ownership). |
| 8 | AUTH-05: Route-level auth enforcement covered by e2e tests (reject-without-key, allow-with-key) | ✓ VERIFIED (with gap) | `AuthTest.kt`, `JwtAuthTest.kt`, `TenantIsolationTest.kt` exist and pass. BUT isolation coverage is top-level-only — does not guard the sub-resource/share leak (see partial-gap truth). |
| 9 | SHAR-01: Create a revocable, expiring share token (1d/7d/30d/never) via `POST /api/sessions/:id/share` | ✓ VERIFIED | `ShareService.create` (UUID token, expiry), `ShareRoutes.kt:52-87` returns `{token,url,expiresAt}`; frontend `ShareDialog.tsx` expiry picker + copy-link. (Owner-authorization gap is the CR-02 failed truth above, not a SHAR-01 functional miss.) |
| 10 | SHAR-02: Anyone with a valid token opens a read-only, rate-limited shared view via `GET /api/shared/:token`; tokens revocable | ✓ VERIFIED | `ShareRoutes.kt:132-159` public route (Valid/Expired-410/NotFound-404), access_count + last_accessed tracking; `Routing.kt` rateLimit wrap; `RateLimitTest.kt`; frontend `shared.$token.tsx` reuses `SessionDetails readOnly`. |

**Score:** 7/10 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `persistence/DatabaseFactory.kt` | Hikari+Exposed+Flyway | ✓ VERIFIED | Flyway.migrate + HikariDataSource + Database.connect present. |
| `persistence/ExposedSessionStore.kt` | SessionStoreInterface + tenant scope | ⚠️ HOLLOW (scope unused on read paths) | Implements TenantScopedSessionStore correctly, but unscoped `getSession(sessionId)` fallback (line 46) is the path sub-resource routes actually call. |
| `persistence/ExposedEventStore.kt` | EventStoreInterface, PolymorphicSerializer | ✓ VERIFIED | Reuses `appJson`+`PolymorphicSerializer(VizEvent::class)`. |
| `db/migration/common/V1__core_schema.sql` | sessions/events/shares tables | ⚠️ DEVIATION | Tables present; `payload`/`metadata` are CLOB not JSONB; no unique `(session_id,seq)`. |
| `Auth.kt` | authenticatedApi + dual provider | ✓ VERIFIED | `authenticatedApi {}` + fail-open `authDisabled`. |
| `auth/ApiKeyStore.kt` | SHA-256 store | ✓ VERIFIED | `MessageDigest.isEqual`. |
| `auth/Tenancy.kt` | principal→tenant + scoped store contract | ✓ VERIFIED | Resolution correct; but contract only wired into 4 routes. |
| `routes/AuthRoutes.kt` | POST /api/auth/token | ✓ VERIFIED | Argon2id `Password.check`. |
| `share/ShareService.kt` | token lifecycle | ⚠️ HOLLOW (ownership) | Lifecycle correct; `created_by` recorded but never enforced. |
| `share/ShareRoutes.kt` | 4 share endpoints | ⚠️ HOLLOW (ownership) | Endpoints present; owner routes lack tenant/ownership check (CR-02). |
| `routes/SessionRoutes.kt` | session CRUD + sub-resources | ✗ STUB (isolation) | Sub-resource + SSE handlers bypass tenant scope (CR-01). |
| frontend `routes/shared.$token.tsx`, `share/ShareDialog.tsx`, `share/ManageShares.tsx`, `lib/auth-store.ts`, `routes/login.tsx` | share/auth UI | ✓ VERIFIED | readOnly reuse, expiry picker+copy, revoke confirm, Bearer/401/token=. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| `Application.kt` | `DatabaseFactory`+`ExposedSessionStore` | storage.type branch | ✓ WIRED | Lines 93-96. |
| `ExposedEventStore` | `appJson`+PolymorphicSerializer | shared SSE serializer | ✓ WIRED | encode/decode via `serializer`. |
| `Routing.kt` | `authenticatedApi {}` | protected wrap | ✓ WIRED | Line 59. |
| `AuthRoutes` | password4j `Password.check` | KDF verify | ✓ WIRED | Argon2id. |
| `SessionRoutes` sub-resources | `Tenancy.resolveTenant` + scoped store | tenant filter on reads | ✗ NOT_WIRED | Sub-resources call unscoped `SessionManager.getSession` (CR-01). |
| `ShareRoutes` owner routes | session ownership / created_by | authorization | ✗ NOT_WIRED | Existence-only check; created_by unused (CR-02). |
| `DbRetentionPolicy` delete | shares table | NOT EXISTS active-share guard | ✓ WIRED | active-share subquery present. |
| `ShareRoutes /api/shared/{token}` | RateLimit per-IP | rateLimit scope | ✓ WIRED | `Routing.kt` rateLimit wrap. |
| frontend `api-client` | auth-store getToken + 401→/login | Bearer + interception | ✓ WIRED | Lines 48-71. |
| frontend `createEventSource` | `?token=` query param | SSE JWT | ✓ WIRED | Line 144. |
| frontend `shared.$token` | `SessionDetails readOnly` | reuse not fork | ✓ WIRED | Line 106. |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|-------------|-------------|--------|----------|
| PERS-01 | 03-01 | JDBC store behind seam, storage.type toggle | ✓ SATISFIED | DatabaseFactory/ExposedSessionStore/ExposedEventStore + Application wiring. |
| PERS-02 | 03-01 | Survive restart, Flyway schema | ✓ SATISFIED | V1 schema + serializer round-trip + PersistenceRestartTest. JSONB literal deviation (WARNING). |
| PERS-03 | 03-03 | Retention background process | ✓ SATISFIED | DbRetentionPolicy + active-share guard, launched at startup. |
| AUTH-01 | 03-02 | authenticatedApi, public routes open | ✓ SATISFIED | Auth.kt/Routing.kt. |
| AUTH-02 | 03-02 | SHA-256 key compare | ✓ SATISFIED | ApiKeyStore MessageDigest.isEqual. |
| AUTH-03 | 03-02, 03-05 | JWT roles + token endpoint + FE | ✓ SATISFIED | JwtConfig/AuthRoutes + FE Bearer/login. |
| AUTH-04 | 03-03 | Tenant isolation, no cross-tenant reads | ✗ BLOCKED | Sub-resource + share read/owner paths unscoped (CR-01/CR-02). |
| AUTH-05 | 03-02 | Route-level auth e2e tests | ✓ SATISFIED (partial) | AuthTest/JwtAuthTest/TenantIsolationTest exist; isolation coverage top-level only. |
| SHAR-01 | 03-04, 03-06 | Create revocable expiring share token | ✓ SATISFIED | ShareService.create + ShareDialog. Ownership gap tracked under AUTH-04. |
| SHAR-02 | 03-04, 03-06 | Read-only, rate-limited, revocable shared view | ✓ SATISFIED | Public route + rate limit + readOnly view. |

All 10 declared requirement IDs (PERS-01/02/03, AUTH-01/02/03/04/05, SHAR-01/02)
are accounted for across the six plans' frontmatter and matched against
REQUIREMENTS.md. No orphaned requirements. AUTH-04 is BLOCKED.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| `routes/SessionRoutes.kt` | 144,161,180,204,231 | Unscoped `SessionManager.getSession` on authenticated session-bound routes | 🛑 Blocker | Cross-tenant data leak (CR-01) — nullifies AUTH-04. |
| `share/ShareRoutes.kt` | 60,96,118 | Existence-only / unscoped owner authorization | 🛑 Blocker | Cross-tenant share mint/list/revoke (CR-02). |
| `persistence/ExposedSessionStore.kt` | 59-61 | `System.currentTimeMillis()` session ids (low entropy) | ⚠️ Warning | Guessable ids amplify CR-01/CR-02 (CR-03). |
| `persistence/DbRetentionPolicy.kt` | 132-152 | Event-trim assumes unique `seq`; schema has only an index | ⚠️ Warning | Cap not reliably enforced on duplicate seq (CR-04). |
| `V1__core_schema.sql` | payload/metadata | CLOB instead of JSONB | ⚠️ Warning | Success criterion #1 says "JSONB events"; literal column type deviates (documented as intentional in schema comment). |
| `Auth.kt` | 58-75 | Auth fails open by default even with `storage.type=database` | ⚠️ Warning | Persisted multi-tenant data can silently go public on misconfig (WR-01). |

### Gaps Summary

The phase goal states vizcore must be "safe to deploy for multiple users" with
"tenant isolation". It is not. Persistence (PERS-01/02/03) and authentication
(AUTH-01/02/03/05) are genuinely delivered and well-built. Sharing (SHAR-01/02)
works functionally. But the central multi-tenant security invariant is broken in
two confirmed, live-code ways:

1. **CR-01 (AUTH-04):** Five authenticated session-bound handlers — `/events`,
   `/hierarchy`, `/threads`, `/coroutines/{id}/timeline`, and the SSE `/stream` —
   resolve the session through the UNSCOPED `SessionManager.getSession`, which
   maps to `tenantPredicate(Unscoped) = Op.TRUE`. Any authenticated tenant can
   read another tenant's full session content. The top-level `GET /{id}` is
   correctly scoped, masking the leak at the entry point while the heavy payloads
   leak underneath.

2. **CR-02 (AUTH-04):** The share-owner routes check session existence (also
   unscoped) but never ownership, so any authenticated user can mint a public,
   never-expiring share for another tenant's session and list/revoke others'
   shares. `created_by` is stored but never enforced.

3. **AUTH-05 coverage gap:** `TenantIsolationTest` only covers top-level CRUD and
   resolution logic, which is precisely why the green suite did not catch CR-01/
   CR-02. New tests must guard the sub-resource and share-owner paths.

These gaps are NOT deferred to a later phase — Phase 4 (PERF/OTEL/SDK) and Phase 5
(IDE/FETEST) do not address tenant isolation. They block the phase goal and must
be closed before proceeding.

Recommended next step: `/gsd-plan-phase --gaps` to scope a focused fix plan that
(a) routes every session-bound read through the tenant-scoped store, (b) enforces
session ownership on share mint/list/revoke, and (c) extends TenantIsolationTest
to cover those paths. The JSONB/CLOB and fail-open warnings should be reviewed by
a human as accept-or-fix decisions but are not, on their own, phase-goal blockers.

---

_Verified: 2026-06-21_
_Verifier: Claude (gsd-verifier)_
