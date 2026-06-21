---
phase: 03-persistence-auth-sharing
plan: 03
subsystem: tenancy-retention
tags: [tenancy, multi-tenant, rbac, exposed, retention, h2, share-guard, structured-concurrency]

# Dependency graph
requires:
  - phase: 03-01
    provides: "ExposedSessionStore + V1 schema (sessions.tenant_id, shares.expires_at front-loaded); SessionManager.useStore façade"
  - phase: 03-02
    provides: "currentPrincipal() helper; ApiKeyPrincipal/UserPrincipal/Role; fail-open auth contract (D-04b)"
provides:
  - "auth/Tenancy.kt: TenantContext (Scoped|Admin|Unscoped) + resolve(principal) (D-03)"
  - "TenantScopedSessionStore seam; ExposedSessionStore tenant filter on every read/delete path (AUTH-04)"
  - "tenant_id stamped on scoped session creation (no global mutable — Pitfall 3)"
  - "SessionManager.backingStore() accessor for route-layer store narrowing"
  - "SessionRoutes thread the resolved tenant into create/list/get/delete"
  - "persistence/DbRetentionPolicy.kt: max-age delete + event-trim with NOT-EXISTS active-share guard (PERS-03, ADR-019)"
  - "Application.module() starts DbRetentionPolicy when storage.type=database; stops on ApplicationStopping"
affects: [03-04-sharing, 05-frontend-auth]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Tenant filter lives in the STORE layer, applied uniformly on list/get/delete (Architectural Responsibility Map; T-03-09)"
    - "TenantContext threaded EXPLICITLY into createSession — no global mutable principal state (T-03-10)"
    - "Op.TRUE always-true predicate for Admin/Unscoped (filter bypass) vs tenant_id eq for Scoped"
    - "NOT EXISTS correlated subquery (Exposed NotExists) as the active-share retention guard (T-03-11)"
    - "Per-session event trim by seq watermark: keep newest cap, delete seq < keepCutoffSeq"

key-files:
  created:
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/auth/Tenancy.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/persistence/DbRetentionPolicy.kt
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/auth/TenantIsolationTest.kt
    - backend/src/test/kotlin/com/jh/proj/coroutineviz/persistence/DbRetentionPolicyTest.kt
  modified:
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/persistence/ExposedSessionStore.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt
    - backend/src/main/kotlin/com/jh/proj/coroutineviz/Application.kt
    - backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/session/SessionManager.kt

key-decisions:
  - "TenantContext is a sealed interface (Scoped|Admin|Unscoped); resolve(principal) maps UserPrincipal→userId, ApiKeyPrincipal→name, ADMIN→Admin (bypass), null→Unscoped (auth-off global, D-04b)"
  - "Tenancy threaded via a NEW TenantScopedSessionStore interface implemented by ExposedSessionStore; the SessionStoreInterface (core SDK) is untouched, so SessionManager/in-memory mode is unaffected and no auth/backend types leak into coroutine-viz-core"
  - "Routes narrow SessionManager.backingStore() to TenantScopedSessionStore; when null (memory mode) they fall back to the unscoped SessionManager calls (global, D-04b)"
  - "Unscoped creation stamps tenant_id = null (global); only Scoped callers stamp ownership — a global/unscoped session is therefore NOT visible to a scoped tenant"
  - "DbRetentionPolicy is a NEW sibling to the in-memory RetentionPolicy (ADR-015 defaults 30d/100000/60min), not a rewrite; max-age delete relies on the FK cascade to remove events/shares"
  - "Active-share guard treats expires_at IS NULL as never-expiring = always active (preserved forever until the share is revoked)"

patterns-established:
  - "Store-layer tenant predicate via Op<Boolean> helper (tenantPredicate) keeps the filter in one place across all read paths"
  - "Event-trim computed in-app from a seq watermark (no native window functions — portable across H2/PG)"

requirements-completed: [AUTH-04, PERS-03]

# Metrics
duration: ~14min
completed: 2026-06-21
---

# Phase 3 Plan 03: Tenancy & Retention Summary

**Stored sessions are now tenant-isolated on the persistent store (AUTH-04, D-03) — the tenant filter lives in `ExposedSessionStore` and is applied on every read/delete path, with ADMIN bypass and api-key-name fallback — while a DB-aware `DbRetentionPolicy` (PERS-03) bounds DB growth with a `NOT EXISTS` active-share guard that never deletes a session backing a live share link (ADR-019). Tenancy engages ONLY when auth is configured; auth-off keeps the global-shared behavior (D-04b).**

## Performance
- **Duration:** ~14 min
- **Started:** 2026-06-21T08:16:55Z
- **Completed:** 2026-06-21
- **Tasks:** 2
- **Files modified:** 8 (4 created, 4 modified)

## Accomplishments
- `auth/Tenancy.kt`: `TenantContext` sealed interface (`Scoped(tenantId)` | `Admin` | `Unscoped`) + `resolve(principal)` (D-03) + the `ApplicationCall.resolveTenant()` helper wrapping Plan 02's `currentPrincipal()`.
- `TenantScopedSessionStore` seam implemented by `ExposedSessionStore`: a single `tenantPredicate(tenant): Op<Boolean>` (`tenant_id = ?` for Scoped; `Op.TRUE` for Admin/Unscoped) applied uniformly on `listSessions` / `getSession` / `deleteSession`; `createSession` stamps `tenant_id` for Scoped callers only. The core `SessionStoreInterface` overloads delegate to the tenant-aware ones with `Unscoped`.
- `SessionRoutes` resolve the tenant from the principal and thread it into create/list/get/delete via the narrowed `SessionManager.backingStore()`; memory mode falls back to the unscoped `SessionManager` calls (global, D-04b).
- `DbRetentionPolicy` (sibling to the in-memory `RetentionPolicy`, ADR-015 defaults 30d/100000/60min): `start(scope)` / `stop()` / `cleanup(): Int`. `cleanup()` runs a max-age delete guarded by a `NOT EXISTS` active-share subquery (FK cascade removes events/shares) plus a per-session event trim by seq watermark — all via the Exposed DSL (parameterized; T-03-12).
- Wired in `Application.module()` ONLY when `storage.type=database`; launches in the application coroutine scope (no global scope; CLAUDE.md) and stops on `ApplicationStopping`. Memory mode is unchanged (no double-wiring).
- Tests green: `TenantIsolationTest` (AUTH-04 — JWT isolation, ADMIN bypass, api-key-name fallback, auth-off global) and `DbRetentionPolicyTest` (PERS-03 — age delete, event trim, active/never-expiring/expired-share guard). Full `:test` suite passes.

## Task Commits
1. **Task 1: Tenancy resolution + tenant-scoped session reads/creation (AUTH-04, D-03)** — `4b5d4fa` (feat)
2. **Task 2: DbRetentionPolicy with active-share guard, wired on persistence (PERS-03)** — `be642e2` (feat)

## Files Created/Modified
- `backend/.../auth/Tenancy.kt` — `TenantContext`, `resolve`, `TenantScopedSessionStore`, `resolveTenant()` (created)
- `backend/.../persistence/DbRetentionPolicy.kt` — DB retention + active-share guard (created)
- `backend/.../persistence/ExposedSessionStore.kt` — implements `TenantScopedSessionStore`; tenant filter + tenant_id stamping (modified)
- `backend/.../routes/SessionRoutes.kt` — thread tenant on create/list/get/delete (modified)
- `backend/.../Application.kt` — `startDbRetention()` wired on database storage; lifecycle stop (modified)
- `backend/.../session/SessionManager.kt` — `backingStore()` accessor (modified)
- `backend/.../auth/TenantIsolationTest.kt`, `.../persistence/DbRetentionPolicyTest.kt` — AUTH-04 / PERS-03 coverage (created)

## Decisions Made
- **createSession tenancy-threading seam:** added a NEW `TenantScopedSessionStore` interface (in `auth/`, backend-only) with tenant-aware `createSession/listSessions/getSession/deleteSession`. `ExposedSessionStore` implements BOTH it and the core `SessionStoreInterface`; the core overloads simply delegate with `TenantContext.Unscoped`. This keeps tenancy threaded EXPLICITLY (no global mutable — Pitfall 3 / T-03-10) and keeps `coroutine-viz-core` free of any auth/backend type (the SDK's `SessionStoreInterface` is untouched, so the in-memory `SessionManager` is unaffected).
- **Unscoped/Admin marker design:** `TenantContext` is a sealed interface with `Scoped(tenantId)`, the `Admin` bypass marker (D-03 — ADMIN sees all), and the `Unscoped` marker (auth-off global, D-04b). The store turns it into a `tenantPredicate(tenant): Op<Boolean>` — `SessionsTable.tenantId eq id` for `Scoped`, `Op.TRUE` (no filter) for `Admin`/`Unscoped`. A globally-created (`Unscoped`) session has `tenant_id = null`, so it is visible to Admin/Unscoped reads but NOT attributed to (or visible to) any scoped tenant.
- **Event-trim SQL shape:** rather than a native window function (not portable across H2/PG), the trim groups stored `(session_id, seq)` pairs in-app, and for any session over the cap computes `keepCutoffSeq = seqsDescending[cap - 1]` and issues a single `DELETE FROM events WHERE session_id = ? AND seq < keepCutoffSeq` — keeping the newest `cap` events. The max-age delete is a single guarded `DELETE FROM sessions WHERE created_at < cutoff AND NOT EXISTS (active-share)`.
- **Active-share guard:** a share is "active" when `expires_at IS NULL` (never expires = always active) OR `expires_at > now`. The `NOT EXISTS` correlated subquery excludes any session with an active share from the age delete; once the share expires/is revoked, the next `cleanup()` deletes the session (ADR-019 / Pitfall 6).

## Deviations from Plan
None — plan executed as written. The store overloads were added via a backend-only `TenantScopedSessionStore` interface (rather than mutating the core `SessionStoreInterface`), which is exactly the plan's guidance ("if a tenantId overload is added to the interface, default it so SessionManager is unaffected") realized without touching the SDK contract.

## Authentication Gates
None — no external auth/login was required during execution.

## Issues Encountered
- **ktlint (pre-existing, deferred):** `MetricsWiring.kt` and `VizEventSerializersModule.kt` still carry the pre-existing `multiline-expression-wrapping` violations logged by 03-01 (both untouched by this plan). All NEW/modified Plan 03-03 files (Tenancy, ExposedSessionStore, SessionRoutes, DbRetentionPolicy, Application.kt) are ktlint-clean — the one pre-existing violation on the touched `Application.kt:24` was incidentally fixed since the file was edited.
- **detekt** still cannot run locally (JVM-target incompatibility) — environmental, logged in `deferred-items.md` (03-01).
- **Exposed `.select(col)`** is not exposed by this jdbc API surface; the event-trim reads via `selectAll()` and projects in-app (functionally equivalent on H2/PG). Noted, not a blocker.

## Known Stubs
None. Tenancy is wired end-to-end on the DB store (create/list/get/delete) and retention is wired into the application lifecycle. The in-memory path remains intentionally global (memory mode = unscoped use case, D-04b).

## Threat Flags
None — all new surface was enumerated in the plan `<threat_model>` (T-03-09/10/11/12) and mitigated: store-layer tenant filter on every read path (T-03-09), tenant threaded explicitly with no global mutable (T-03-10), NOT-EXISTS active-share guard on the retention delete (T-03-11), and Exposed-DSL parameterized deletes with no string SQL (T-03-12).

## Next Phase Readiness
- **Plan 03-04 (sharing):** the `shares` table active-share semantics (`expires_at IS NULL` = never expires) are now relied upon by retention; the DB-backed ShareService must keep `expires_at` accurate so retention's guard stays correct. Tenancy provides `TenantContext` if share creation needs to scope to the owner.
- **Plan 05 (frontend auth):** unchanged from 03-02; tenancy is server-side and transparent to the client (the client just sees its own sessions when auth is on).

## Self-Check: PASSED

All created files exist on disk (Tenancy.kt, DbRetentionPolicy.kt, TenantIsolationTest.kt, DbRetentionPolicyTest.kt). Both task commits (`4b5d4fa`, `be642e2`) present in git history.

---
*Phase: 03-persistence-auth-sharing*
*Completed: 2026-06-21*
