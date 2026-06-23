---
phase: 03-persistence-auth-sharing
plan: 07
subsystem: backend-auth
tags: [tenant-isolation, auth, sharing, security, AUTH-04, AUTH-05, CR-01, CR-02]
gap_closure: true
requires:
  - TenantScopedSessionStore (auth/Tenancy.kt)
  - ExposedSessionStore tenant filter (03-03)
  - JWT/api-key principal resolution (03-02)
provides:
  - Tenant-scoped session sub-resource reads (/events,/hierarchy,/threads,/timeline)
  - Pre-stream tenant-scoped SSE /stream resolution
  - Ownership-enforced share mint/list/revoke (created_by predicate)
  - e2e tenant-isolation guard over the real route + JWT-auth stack
affects:
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareRoutes.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareService.kt
tech-stack:
  added: []
  patterns:
    - "ApplicationCall.resolveScopedSession helper: store!=null → getSession(id, resolveTenant()); else SessionManager.getSession (D-04b fallback)"
    - "created_by-scoped Exposed overloads (params bound, never concatenated)"
    - "PRE-stream SSE scoping (resolve before connected frame / gauge / bus subscribe / replay)"
key-files:
  created:
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/auth/TenantIsolationE2ETest.kt
  modified:
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareRoutes.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareService.kt
decisions:
  - "Owner-positive 200 assertions use /events + SSE replay (not timeline) because a DB-rebuilt session's projection is not replayed from stored events (pre-existing, deferred)"
  - "Cross-tenant SSE reuses the existing not-found error-event + return@sse shape (a cross-tenant id is indistinguishable from a missing id — no existence leak)"
metrics:
  duration: ~25 min
  tasks: 3
  files: 4
  completed: 2026-06-21
---

# Phase 3 Plan 7: Tenant Isolation Gap-Closure (CR-01 / CR-02 / AUTH-05) Summary

Closed the three confirmed Phase 3 verification gaps that broke the multi-tenant
security invariant: every authenticated session-bound read path and the
share-owner routes now resolve through the tenant filter, and an e2e test guards
the isolation invariant against the real route + JWT-auth stack.

## What was built

**Task 1 — CR-01 (SessionRoutes.kt, commit d663cab):** Added a private
`ApplicationCall.resolveScopedSession(sessionId)` helper that mirrors the
top-level `GET /api/sessions/{id}` scoping — when the tenant-scoped store is
active it resolves via `store.getSession(sessionId, resolveTenant())` (cross-tenant
id → null → 404), else falls back to the unscoped `SessionManager.getSession`
(D-04b auth-off / memory mode). The five previously-unscoped handlers
(`/events`, `/hierarchy`, `/threads`, `/coroutines/{id}/timeline`, SSE `/stream`)
now route through it. For SSE the scoped resolution happens PRE-stream — before
the `connected` comment frame, the `sseClientsGauge` increment, the bus
subscription, and the replay loop — so a cross-tenant caller never opens a stream
nor triggers a replay of the owner's events.

**Task 2 — CR-02 (ShareRoutes.kt + ShareService.kt, commit 1971ffd):** Added two
`created_by`-scoped Exposed overloads alongside the existing ones (existing
signatures untouched — the public read path + ShareRoutesTest still use them):
`listForSession(sessionId, createdBy)` and `revoke(sessionId, token, createdBy)`,
each adding `(SharesTable.createdBy eq createdBy)` (bound as a parameter). The
mint route replaced the unscoped existence check with a tenant-scoped ownership
check (`tenantScopedStore().getSession(id, resolveTenant()) != null`, 404
otherwise); list/revoke now pass `call.shareCreatorId()`. A non-owner cannot mint
a public share for, enumerate, or revoke another tenant's session/shares.

**Task 3 — AUTH-05 (TenantIsolationE2ETest.kt, commit 0402faa):** New e2e test
that wires `registerSessionRoutes()` + `registerShareOwnerRoutes()` behind a real
`authenticate("jwt")` block (jwt provider mirrors Auth.kt, incl. the `?token=`
SSE authHeader fallback) against an H2-backed `ExposedSessionStore`, so
`resolveTenant()` resolves the tenant from the verified JWT `sub`. Mints alice/bob
JWTs via `JwtConfig.sign`. Asserts: cross-tenant 404 on all four sub-resources;
SSE pre-stream 404 with NO `connected` comment and NO replay of alice's event;
owner-200 + owner SSE replay (proving the guard is not a blanket 404); share
non-owner mint=404, list excludes others' shares, revoke=404 (share still
resolves publicly), owner mint=201/list/revoke=204.

## Verification

- `cd backend && ./gradlew :test --tests "...TenantIsolationE2ETest" -x detekt` — 6/6 green.
- `cd backend && ./gradlew test -x detekt` — full backend suite green (no regression in TenantIsolationTest / ShareRoutesTest / SseStreamTest / SessionRoutesTest).
- Source audit: `grep -n "SessionManager.getSession" .../routes/SessionRoutes.kt` shows it ONLY in the two `store==null` fallback branches (helper line 57, top-level GET line 107) plus two doc-comment references — never on a sub-resource/SSE happy path.
- ktlint: the four files in this plan are ktlint-clean (the `ktlintCheck` build failure is the pre-existing, unrelated set already tracked in deferred-items.md).

## Deviations from Plan

### Auto-fixed Issues

None affecting plan logic.

### Scope adjustments

**[Scope boundary] Timeline owner-200 assertion narrowed.** The plan's
owner-positive criterion expected `GET /coroutines/{id}/timeline → 200` for the
owner. A DB-rebuilt `VizSession` does NOT replay its stored events into the
`projectionService`, so timeline returns 404 even for the owner (and hierarchy/
threads return 200 but empty). This is a pre-existing persistence-layer
limitation unrelated to the CR-01/CR-02 tenancy gap. The test therefore asserts
owner-200 on `/events`, `/hierarchy`, `/threads` (which return 200) and proves
owner data visibility via `/events` content + SSE replay; the cross-tenant
timeline 404 still guards isolation. Logged to
`.planning/phases/03-persistence-auth-sharing/deferred-items.md` (03-07 row).

## Known Stubs

None. The `created_by` column already existed (03-03); this plan only began USING
it as an authorization predicate. No new routes, response shapes, or DB columns.

## Self-Check: PASSED

- FOUND: backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt
- FOUND: backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareRoutes.kt
- FOUND: backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareService.kt
- FOUND: backend/src/test/kotlin/com/jh/proj/coroutineviz/auth/TenantIsolationE2ETest.kt
- FOUND commit d663cab (Task 1, CR-01)
- FOUND commit 1971ffd (Task 2, CR-02)
- FOUND commit 0402faa (Task 3, AUTH-05)
