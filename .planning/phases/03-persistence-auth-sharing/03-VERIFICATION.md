---
phase: 03-persistence-auth-sharing
verified: 2026-06-21T15:30:00Z
status: passed
score: 10/10 must-haves verified
overrides_applied: 0
re_verification:
  previous_status: gaps_found
  previous_score: 7/10
  gaps_closed:
    - "AUTH-04 / CR-01: tenant isolation on session sub-resources (/events, /hierarchy, /threads, /coroutines/{id}/timeline) and the SSE /stream"
    - "AUTH-04 / CR-02: share mint/list/revoke ownership enforcement (created_by predicate + scoped session ownership on mint)"
    - "AUTH-05 coverage: TenantIsolationE2ETest now guards the cross-tenant invariant on every session-bound read path and the share-owner routes"
  gaps_remaining: []
  regressions: []
gaps: []
human_verification: []
---

# Phase 3: Persistence, Auth & Sharing Verification Report (re-verification after gap closure 03-07)

**Phase Goal:** vizcore is safe to deploy for multiple users — sessions can persist across restarts, every non-public route enforces authentication with tenant isolation, and sessions can be shared as read-only links.
**Verified:** 2026-06-21T15:30:00Z
**Status:** passed
**Re-verification:** Yes — after gap closure plan 03-07 (CR-01 / CR-02 / AUTH-05)

## Goal Achievement

The prior verification (2026-06-21, 7/10) found the two non-security pillars
(persistence, authentication) delivered but the **central security invariant —
tenant isolation (AUTH-04)** — broken in two confirmed live-code ways (CR-01:
unscoped session sub-resource + SSE reads; CR-02: unscoped share owner routes),
with the AUTH-05 test coverage unable to catch them.

Gap-closure plan 03-07 has been executed and is verified here against the **live
codebase and a freshly-executed full backend test suite** (261 tests, 0 failures,
0 errors — run by the verifier, not trusted from SUMMARY). All three gaps are
closed. The phase goal is now achieved: vizcore is safe to deploy for multiple
users.

1. **Persistence across restarts** — DELIVERED (unchanged, regression-checked).
   `PersistenceRestartTest` 2/2 pass; Exposed + HikariCP + Flyway wiring intact.
2. **Authentication on non-public routes** — DELIVERED (unchanged,
   regression-checked). `AuthTest` 12/12, `JwtAuthTest` 9/9 pass.
3. **Tenant isolation + read-only sharing** — NOW DELIVERED. Every authenticated
   session-bound read and the share-owner surface route through the tenant filter;
   `TenantIsolationE2ETest` (6/6) asserts a cross-tenant caller gets 404 / no
   replay on all five session-bound paths and on share mint/list/revoke, while the
   owner's access still works (proving the guard is a real isolation filter, not a
   blanket deny).

### Observable Truths

| #   | Truth | Status | Evidence |
| --- | ----- | ------ | -------- |
| 1 | PERS-01: JDBC store behind the seam, selectable via `storage.type=database` | ✓ VERIFIED | Unchanged from prior pass; `DatabaseFactory`/`ExposedSessionStore`/`ExposedEventStore` + Application wiring. Regression-checked: full suite green. |
| 2 | PERS-02: Sessions + events survive restart (Flyway schema, persisted payload) | ✓ VERIFIED | `PersistenceRestartTest` 2/2 pass in this run. (CLOB-not-JSONB literal deviation remains a documented WARNING, not a goal blocker.) |
| 3 | PERS-03: Retention background process, never deletes a session with an active share | ✓ VERIFIED | Unchanged; `DbRetentionPolicy` active-share `NOT EXISTS` guard, launched at startup. |
| 4 | AUTH-01: Non-public routes wrapped in `authenticatedApi()`; public routes stay open | ✓ VERIFIED | `AuthTest` 12/12 pass. |
| 5 | AUTH-02: API keys compared as SHA-256 via constant-time `MessageDigest.isEqual` | ✓ VERIFIED | Unchanged; `ApiKeyStore` hex compare. |
| 6 | AUTH-03: JWT token endpoint + VIEWER/RUNNER/ADMIN, explicit alg (no `none`) | ✓ VERIFIED | `JwtAuthTest` 9/9 pass; `JwtConfig.sign`/`verifier` used by the new e2e test to mint real tenant tokens. |
| 7 | AUTH-04: Sessions tenant-isolated — no cross-tenant reads (CR-01 + CR-02) | ✓ VERIFIED (was FAILED) | `SessionRoutes.kt`: all five handlers (events:164, hierarchy:181, threads:200, timeline:224, SSE:256) now call `call.resolveScopedSession(sessionId)`; SSE resolves PRE-stream (before connected frame / gauge / bus / replay). `grep SessionManager.getSession` returns only the two `store==null` fallback branches (lines 57, 107) + 2 KDoc refs — none on a sub-resource/SSE happy path. `ShareRoutes.kt`: mint (74-84) does a scoped ownership check; list (119)/revoke (142) pass `shareCreatorId()`. `ShareService.kt`: `listForSession(sessionId, createdBy)` (164) and `revoke(sessionId, token, createdBy)` (201) add a parameter-bound `createdBy eq` predicate. `TenantIsolationE2ETest` 6/6 pass asserting bob→404 on every path, no SSE replay, alice→200/replay. |
| 8 | AUTH-05: Route-level auth + tenant isolation covered by e2e tests | ✓ VERIFIED (was PARTIAL) | New `TenantIsolationE2ETest` (398 lines) wires real routes behind a real `authenticate("jwt")` block over H2 `ExposedSessionStore`; 6/6 green in this run. Covers /events, /hierarchy, /threads, /timeline, /stream, share mint, share list, share revoke — the exact paths the old green suite missed. Existing `AuthTest`/`JwtAuthTest`/`TenantIsolationTest` still pass (no regression). |
| 9 | SHAR-01: Create a revocable, expiring share token (1d/7d/30d/never) | ✓ VERIFIED | `ShareService.create` + `ShareRoutes` POST; `ShareRoutesTest` 10/10 pass. Owner-authorization now enforced (CR-02), strengthening this. |
| 10 | SHAR-02: Read-only, rate-limited, revocable shared view via `GET /api/shared/:token`; tokens revocable | ✓ VERIFIED | Public route + `RateLimitTest` 3/3; e2e test confirms a revoked share 404s on the public route and a non-owner's failed revoke leaves it resolvable. |

**Score:** 10/10 truths verified

### Required Artifacts (gap-closure plan 03-07)

| Artifact | Expected | Status | Details |
| -------- | -------- | ------ | ------- |
| `routes/SessionRoutes.kt` | Tenant-scoped resolution on the 4 sub-resources + SSE | ✓ VERIFIED | `resolveScopedSession` helper (52-59); all five handlers wired; SSE pre-stream (256). `call.resolveTenant` present via helper. |
| `share/ShareRoutes.kt` | Ownership-checked mint/list/revoke | ✓ VERIFIED | `call.resolveTenant()` in mint (77); list/revoke pass `shareCreatorId()`. |
| `share/ShareService.kt` | `created_by`-scoped list/revoke overloads | ✓ VERIFIED | `createdBy eq` predicate, parameter-bound, in both overloads (171, 211). Existing 2-arg overloads retained for the public path. |
| `test/.../auth/TenantIsolationE2ETest.kt` | e2e cross-tenant 404 coverage (min 120 lines) | ✓ VERIFIED | 398 lines, 6 tests, all 8 required paths covered; 6/6 green in the verifier's run. |

### Key Link Verification

| From | To | Via | Status | Details |
| ---- | -- | --- | ------ | ------- |
| `SessionRoutes` sub-resource + SSE handlers | `TenantScopedSessionStore.getSession(id, tenant)` | `tenantScopedStore() + resolveTenant()` via `resolveScopedSession` | ✓ WIRED (was NOT_WIRED) | Helper resolves scoped store; cross-tenant id → null → 404 / pre-stream 404. |
| `ShareRoutes` owner routes | session ownership + `created_by` predicate | scoped `getSession` + `ShareService` scoped overloads | ✓ WIRED (was NOT_WIRED) | Mint gated by scoped existence; list/revoke scoped by `created_by`. |
| `TenantIsolationE2ETest` | real routes behind real JWT auth | `authenticate("jwt")` + `JwtConfig.sign` + H2 `ExposedSessionStore` | ✓ WIRED | `resolveTenant()` resolves from the verified JWT `sub`; tests pass. |

### Behavioral Spot-Checks

The behavioral guarantees are exercised by the freshly-run test suite rather than
ad-hoc commands (the relevant behaviors require the route + auth + DB stack).

| Behavior | Command | Result | Status |
| -------- | ------- | ------ | ------ |
| Full backend suite green | `cd backend && ./gradlew test -x detekt` | BUILD SUCCESSFUL; 261 tests, 0 failures, 0 errors, 0 skipped (`:test` executed, not UP-TO-DATE) | ✓ PASS |
| Cross-tenant isolation e2e | `TenantIsolationE2ETest` result XML | tests=6 failures=0 errors=0 (regenerated 15:25 by this run) | ✓ PASS |
| Share owner routes regression | `ShareRoutesTest` result XML | tests=10 failures=0 errors=0 | ✓ PASS |
| Store-level isolation regression | `TenantIsolationTest` result XML | tests=10 failures=0 errors=0 | ✓ PASS |
| SSE regression | `SseStreamTest` result XML | tests=7 failures=0 errors=0 | ✓ PASS |
| Persistence restart | `PersistenceRestartTest` result XML | tests=2 failures=0 errors=0 | ✓ PASS |
| Source audit: no unscoped getSession on hot paths | `grep -n SessionManager.getSession routes/SessionRoutes.kt` | only lines 57, 107 (fallback) + 48, 50 (KDoc) | ✓ PASS |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
| ----------- | ----------- | ----------- | ------ | -------- |
| PERS-01 | 03-01 | JDBC store behind seam, storage.type toggle | ✓ SATISFIED | DatabaseFactory/Exposed stores + Application wiring; suite green. |
| PERS-02 | 03-01 | Survive restart, Flyway schema | ✓ SATISFIED | PersistenceRestartTest 2/2. (CLOB/JSONB literal deviation = WARNING.) |
| PERS-03 | 03-03 | Retention background process | ✓ SATISFIED | DbRetentionPolicy + active-share guard. |
| AUTH-01 | 03-02 | authenticatedApi, public routes open | ✓ SATISFIED | AuthTest 12/12. |
| AUTH-02 | 03-02 | SHA-256 key compare | ✓ SATISFIED | ApiKeyStore MessageDigest.isEqual. |
| AUTH-03 | 03-02, 03-05 | JWT roles + token endpoint + FE | ✓ SATISFIED | JwtAuthTest 9/9 + FE Bearer/login. |
| AUTH-04 | 03-03, **03-07** | Tenant isolation, no cross-tenant reads | ✓ SATISFIED (was BLOCKED) | CR-01 + CR-02 closed in live code; TenantIsolationE2ETest 6/6. |
| AUTH-05 | 03-02, **03-07** | Route-level auth e2e tests | ✓ SATISFIED (was partial) | New e2e test now guards the central isolation invariant on all session-bound + share-owner paths. |
| SHAR-01 | 03-04, 03-06 | Create revocable expiring share token | ✓ SATISFIED | ShareService.create + ShareRoutesTest 10/10; now ownership-enforced. |
| SHAR-02 | 03-04, 03-06 | Read-only, rate-limited, revocable shared view | ✓ SATISFIED | Public route + RateLimitTest 3/3; revoke→404 confirmed e2e. |

All 10 declared requirement IDs (PERS-01/02/03, AUTH-01/02/03/04/05, SHAR-01/02)
are accounted for across the seven plans' frontmatter and matched against
REQUIREMENTS.md (lines 159-168, all marked Phase 3). No orphaned requirements; no
gap remaining on AUTH-04 or AUTH-05.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
| ---- | ---- | ------- | -------- | ------ |
| `routes/SessionRoutes.kt` | 305-307 | SSE live collector `launch{}` without `finally{ liveBuffer.close() }` | ⚠️ Warning (WR-04) | Drain loop relies on client disconnect / bus never completing normally. Not a leak; brittle assumption. Human accept-or-fix. |
| `routes/SessionRoutes.kt` | 256-266 | Cross-tenant SSE returns HTTP 200 + error frame (not 404) | ⚠️ Warning (WR-01) | Status asymmetry vs the 404 sub-resources. No data leak (e2e asserts no replay / no "connected"). Human accept-or-fix; not a goal blocker. |
| `share/ShareRoutes.kt` + `ShareService.kt` | mint 74-98 / list-revoke scope | ADMIN can mint a tenant-invisible, tenant-unrevocable public share | ⚠️ Warning (WR-02) | Only an ADMIN principal (D-03 grants ADMIN the `Op.TRUE` tenant bypass by design). Non-admin tenant isolation holds and is test-guarded. Human accept-or-document. |
| `share/ShareService.kt` | 149-157, 181-193 | Unscoped 2-arg `listForSession`/`revoke` overloads remain | ℹ️ Info (IN-01) | Footgun if a future caller uses them; required today by the public path. |
| `V1__core_schema.sql` | payload/metadata | CLOB instead of JSONB | ⚠️ Warning | Success criterion #1 literal "JSONB"; documented intentional deviation. Human accept-or-fix. |
| `Auth.kt` | fail-open default | Auth off by default even with `storage.type=database` | ⚠️ Warning | Intentional per D-04a/D-04b (open-source default-off + auth-off+persistence = global). Documented decision, not a defect. |

None of the above is a 🛑 BLOCKER. No `TBD`/`FIXME`/`XXX` debt markers in the
gap-closure files. The two prior 🛑 Blocker anti-patterns (CR-01 unscoped
sub-resource/SSE reads; CR-02 unscoped share owner routes) are RESOLVED.

### Human Verification Required

None. The phase goal is verifiable programmatically (route + auth + DB e2e tests
that the verifier executed). The frontend share/auth UX was already verified in the
prior pass and is unchanged by 03-07 (backend-only gap closure). The WARNING items
above are accept-or-fix maintenance decisions, not goal-gating human tests, so they
do not force `human_needed` status.

### Gaps Summary

No gaps. The three confirmed gaps from the prior verification are closed and
verified against live code plus a freshly-executed test suite:

1. **CR-01 (AUTH-04)** — All five authenticated session-bound handlers
   (`/events`, `/hierarchy`, `/threads`, `/coroutines/{id}/timeline`, SSE
   `/stream`) now resolve through `resolveScopedSession` →
   `store.getSession(sessionId, resolveTenant())`. SSE scoping is pre-stream.
   `SessionManager.getSession` survives only in the `store==null` (auth-off /
   memory, D-04b) fallback branches.

2. **CR-02 (AUTH-04)** — Share mint is gated by a scoped session-ownership check;
   list/revoke are scoped by `created_by` via parameter-bound Exposed predicates.
   A non-owner cannot mint, enumerate, or revoke another tenant's shares.

3. **AUTH-05 coverage** — `TenantIsolationE2ETest` exercises the real route + JWT
   auth stack and asserts cross-tenant 404 (no replay, no leaked content) on every
   session-bound read path and on all three share-owner routes, with owner-positive
   assertions proving the guard is a true isolation filter rather than a blanket
   deny. 6/6 green; full suite 261/261 green with no regression.

The remaining items are WARNING/INFO-tier accept-or-fix decisions (WR-01 SSE
status asymmetry, WR-02 admin-mint semantics, WR-04 SSE buffer close, CLOB/JSONB,
fail-open default). None blocks the phase goal, and none is deferred to a later
phase that addresses tenant isolation (Phase 4 = PERF/OTEL/SDK, Phase 5 =
IDE/FETEST — neither touches AUTH-04). They are surfaced for human visibility but
do not change the verdict.

**Verdict:** Phase 3 goal achieved — vizcore is safe to deploy for multiple users.

---

_Verified: 2026-06-21T15:30:00Z_
_Verifier: Claude (gsd-verifier)_
