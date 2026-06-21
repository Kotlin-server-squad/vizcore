---
phase: 03-persistence-auth-sharing
reviewed: 2026-06-21T00:00:00Z
depth: standard
files_reviewed: 4
files_reviewed_list:
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareRoutes.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareService.kt
  - backend/src/test/kotlin/com/jh/proj/coroutineviz/auth/TenantIsolationE2ETest.kt
findings:
  critical: 0
  warning: 4
  info: 3
  total: 7
status: issues_found
---

# Phase 3: Code Review Report (gap-closure re-review, plan 03-07)

**Reviewed:** 2026-06-21
**Depth:** standard (with cross-file tracing into Tenancy.kt, ExposedSessionStore.kt, Auth.kt, Routing.kt)
**Files Reviewed:** 4
**Status:** issues_found

## Summary

This gap-closure review verifies the CR-01/CR-02/AUTH-05 fixes against the actual
code. I traced every session-bound read path, the SSE pre-stream check, and all
three Exposed predicates against the store/auth substrate (`Tenancy.kt`,
`ExposedSessionStore.kt`, `Auth.kt`, `Routing.kt`).

The three primary security claims hold:

1. **CR-01 (sub-resource + SSE scoping):** Every authenticated session-bound read
   (`/events`, `/hierarchy`, `/threads`, `/coroutines/{id}/timeline`) routes
   through `resolveScopedSession` → `store.getSession(sessionId, resolveTenant())`.
   The SSE handler performs the scoped resolution **before** the `connected`
   comment, the gauge increment, and any bus subscription/replay
   (`SessionRoutes.kt:256`). Cross-tenant SSE emits a `not found` error event and
   returns with no replay. The e2e test asserts this directly.

2. **CR-02 (share ownership predicate):** `created_by` is bound through the Exposed
   DSL (`SharesTable.createdBy eq createdBy`) in both `listForSession/2` and
   `revoke/3` — parameter-bound, no string concatenation, not injectable
   (`ShareService.kt:171, 211`). Mint is gated by a scoped `getSession` existence
   check (`ShareRoutes.kt:74-84`).

3. **D-04b / D-03 bypass preservation:** `tenantScopedStore()` returns null in
   memory/auth-off mode (unscoped `SessionManager` fallback); `tenantPredicate`
   returns `Op.TRUE` for both `Admin` and `Unscoped` (`ExposedSessionStore.kt:148`).
   Correct.

No BLOCKER-tier defects were proven. Four WARNING-level concerns and three INFO
items remain. The most material are WR-01 (cross-tenant SSE returns 200+error
frame rather than 404 — an existence-signal asymmetry) and WR-02 (an ADMIN can
mint a durable, tenant-invisible, tenant-unrevocable public share on any tenant's
session).

## Warnings

### WR-01: Cross-tenant SSE returns HTTP 200 with an error frame, not 404 — status asymmetry / existence signal

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt:256-266`
**Issue:** The non-SSE sub-resource routes return a real `404 Not Found` for a
cross-tenant id, making it indistinguishable from a missing session. The SSE path
instead returns **HTTP 200** (the SSE plugin commits the status when streaming
begins) and then `send`s an `error` event with `{"error": "Session not found"}`
before returning. Data is not leaked (no replay, no `connected`) and the e2e test
asserts the absence of leaked content, so this is not a BLOCKER. But the
status-code asymmetry (404 elsewhere vs 200+error-frame here) is a behavioral
inconsistency a client must special-case, and the non-leak property relies on the
SSE plugin emitting an identical response shape for "missing" and "cross-tenant".
**Fix:** Document the intentional 200+error-frame contract in the handler KDoc and
add a test assertion that the cross-tenant SSE response status is exactly 200, so
a future plugin upgrade that changes this is caught:
```kotlin
val resp = client.get("/api/sessions/$aId/stream?token=$bob")
assertEquals(HttpStatusCode.OK, resp.status) // SSE always 200; isolation is in the frame
```

### WR-02: ADMIN (or any caller with session visibility) can mint a share the session owner cannot list or revoke

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareRoutes.kt:74-98, 110-147`
**Issue:** Mint authorization is "can the caller *see* the session" (scoped
`getSession`), but share *ownership* is recorded as `created_by = shareCreatorId()`
(the minter), and list/revoke are scoped to `created_by`. For a
`TenantContext.Admin` principal, `getSession` returns any session via the
`Op.TRUE` bypass (`ExposedSessionStore.kt:148`), so an ADMIN can mint a share on
ANY tenant's session. That share is owned by the admin's `userId`, so the actual
session owner cannot see it in `GET /api/sessions/{id}/shares` (filtered by their
own `created_by`), cannot revoke it (`revoke/3` requires `created_by` match), and
the admin-minted share stays publicly resolvable at `/api/shared/{token}` until
expiry — invisible to the tenant. A privileged actor can create a durable,
tenant-invisible public link with no tenant visibility or revocation path. Not
covered by any cited decision record.
**Fix:** Either (a) scope share listing/revocation by **session ownership** (so a
session owner can manage all shares on their session), or (b) explicitly document
that admin-minted shares are intentionally invisible/unrevocable to the tenant and
add a test asserting the chosen semantics. Recommended (a):
```kotlin
// list/revoke should match shares the caller owns OR shares on a session the
// caller owns — push the session-ownership predicate into ShareService.
```

### WR-03: Public share read path relies entirely on FK cascade for revocation correctness (unverified in scope)

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareRoutes.kt:166-173`
**Issue:** `registerSharedPublicRoute` resolves the share, then calls
`SessionManager.getSession(resolution.share.sessionId)` — the **unscoped** getter.
This is correct by design (token is the credential, no tenant context on the
public path). But correctness now depends entirely on the invariant that a share
row never outlives its session. The comment at line 168-169 asserts "FK cascade +
retention guard," but the migration/DDL is outside this review's file scope and so
the cascade is unverified here. If a session were ever deleted without cascading
its shares (a future direct-SQL cleanup, a non-cascading migration, or an
id-reuse where `SessionManager.getSession` resolves a *recreated* session with the
same id), the token could resolve to an unrelated session.
**Fix:** Confirm `ON DELETE CASCADE` on `shares.session_id` in the migration and
add a regression test: delete a session, assert its shares are gone and the public
read 404s. The defensive null-check at line 167 is good and should stay, but it
catches only the missing-session case, not id-reuse.

### WR-04: SSE live-event collector launched without a finally-close — drain loop depends solely on client disconnect

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt:305-307`
**Issue:** `launch { session.bus.stream().collect { liveBuffer.send(it) } }` never
closes `liveBuffer`. If `bus.stream()` completes *normally* (rather than erroring
or being cancelled), the channel is never closed and the
`for (event in liveBuffer)` drain loop at line 324 suspends forever on an open,
empty channel until the client disconnects. This relies on the unstated
assumption that the bus stream never completes normally. For an unbounded live
source this holds today, but the assumption is implicit and brittle.
**Fix:** Close the buffer when the collector ends so the drain loop terminates if
the bus ever completes:
```kotlin
launch {
    try { session.bus.stream().collect { liveBuffer.send(it) } }
    finally { liveBuffer.close() }
}
```

## Info

### IN-01: Unused unscoped overloads `listForSession(sessionId)` and `revoke(sessionId, token)` remain after CR-02 ownership-scoped overloads

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareService.kt:149-157, 181-193`
**Issue:** The CR-02 fix added `listForSession(sessionId, createdBy)` and
`revoke(sessionId, token, createdBy)`. The original 2-arg overloads (which do NOT
scope by `created_by`) are still present. If any caller inadvertently uses them,
the ownership check is bypassed — a latent footgun.
**Fix:** Delete the unscoped overloads unless a documented caller needs the
unscoped view, in which case rename them (e.g. `listAllForSessionUnscoped`) to
make the bypass explicit and grep-able.

### IN-02: `shareCreatorId()` collapses all auth-off mints to one shared "anonymous" owner

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareRoutes.kt:32-37`
**Issue:** In auth-off mode every minted share is `created_by = "anonymous"`. Since
list/revoke scope by `created_by`, all anonymous callers share one ownership
bucket — any can list/revoke another's shares. Consistent with D-04b (auth-off =
global visibility), so not a defect, but it differs from the per-tenant isolation
the rest of the feature provides and should be documented.
**Fix:** Add a line to the `shareCreatorId` KDoc: "in auth-off mode all mints
share the 'anonymous' owner, so share management is global — consistent with
D-04b."

### IN-03: E2E test acknowledges but does not cover the timeline owner-200 path

**File:** `backend/src/test/kotlin/com/jh/proj/coroutineviz/auth/TenantIsolationE2ETest.kt:206-223`
**Issue:** The test deliberately excludes the timeline route from owner-200
assertions (comment lines 207-210: DB-rebuilt projection not replayed). The
cross-tenant 404 for timeline IS asserted, so isolation is covered — but the
positive path (owner gets a populated timeline) is untested here and deferred.
Documented gap, recorded for traceability.
**Fix:** Track the deferred timeline-projection-rebuild item; once projections are
rebuilt from stored events, extend this test to assert alice's timeline returns
200 with `c1`.

---

_Reviewed: 2026-06-21_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
