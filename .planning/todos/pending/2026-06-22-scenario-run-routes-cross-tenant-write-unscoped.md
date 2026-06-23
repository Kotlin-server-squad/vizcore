---
title: Scenario-run / flow / pattern routes use UNSCOPED SessionManager → cross-tenant write breaks AUTH-04
area: backend
severity: high
found: 2026-06-22
status: fixed
fixed: 2026-06-22
phase: 3
requirement: AUTH-04 (tenant isolation) / ADR-016
discovered_during: Phase-3 browser UAT deep-dive (Step 5 tenant isolation)
---

## Symptom (reproduced via REST)
With auth ON and two JWT tenants (alice, bob):
1. bob creates `session-1782115355101` (owner=bob); eventCount=0.
2. alice (different tenant) `POST /api/scenarios/nested?sessionId=session-1782115355101` → `success:true`.
3. bob's session AFTER: **eventCount=17, coroutineCount=4**.

→ alice injected events/coroutines into bob's private session. The READ path correctly isolates
(`alice GET /api/sessions/<bob's id>` → 404, not in alice's list — AUTH-04 read holds), but the
scenario-run WRITE path does not.

### Secondary symptom (same root cause)
A scoped caller (API key or JWT) that creates a session via a scenario-run endpoint cannot then
see/read it: the session is created UNSCOPED (owner=null/global), so the scoped tenant predicate
(`tenant_id = <caller>`) filters it out → `list` returns `[]` and `GET` returns 404 for the creator.

## Root cause
`ScenarioRunnerRoutes.kt:469-472`:
```kotlin
private suspend fun getOrCreateSession(sessionId: String?): VizSession =
    sessionId?.let { SessionManager.getSession(it) }            // UNSCOPED lookup — any tenant's session
        ?: SessionManager.createSession("auto-${System.currentTimeMillis()}")  // UNSCOPED create — owner=null
```
Same pattern in `FlowScenarioRoutes.kt:86-95` (`getOrCreateSession(sessionId, prefix)`) and the
pattern routes. These call the `SessionStoreInterface` (unscoped) overloads, not the
`TenantScopedSessionStore` (tenant) overloads. So:
- `getSession(id)` returns ANY session regardless of owner → cross-tenant write.
- `createSession(name)` stamps no owner → orphaned session invisible to its scoped creator.

The Phase-3 gap-closure (03-07) scoped the session *sub-resources* (events/stream/coroutines) and
share ownership, but the scenario-run / flow / pattern POST routes that mutate sessions were missed.

## Impact
- AUTH-04 breach on the write path: any holder of a valid token can inject/corrupt events into another
  tenant's session if they know or guess the session id (ids are `name-<ts>` / `session-<ts>` —
  low entropy). Also enables resource abuse against arbitrary sessions.
- Usability: scoped callers (API-key/CI, JWT users) can't see sessions they create via scenario runs.

## Candidate fixes
- Thread the caller's `TenantContext` (from the authenticated principal) into `getOrCreateSession`
  and call the tenant-scoped `getSession(id, tenant)` / `createSession(name, tenant)` overloads, so:
  (a) a cross-tenant `sessionId` resolves to null → 404/403, and (b) created sessions are owned by the caller.
- Apply the same fix to FlowScenarioRoutes + PatternRoutes + any other route calling the unscoped
  SessionManager overloads.
- Add e2e tests: tenant A cannot run a scenario into tenant B's session; a scenario-created session
  is owned by and visible to its creator.

## ✅ RESOLUTION (2026-06-22)
Threaded the caller's `TenantContext` into the scenario-run get-or-create:
- `SessionRoutes.tenantScopedStore()` made `internal` (shared helper).
- `ScenarioRunnerRoutes.getOrCreateSession` and `FlowScenarioRoutes.getOrCreateSession`
  (also used by PatternRoutes) converted to `ApplicationCall` extensions that, when the
  tenant-scoped store is active, resolve via `store.getSession(id, resolveTenant())` and create via
  `store.createSession(name, resolveTenant())`. A cross-tenant/unknown id → null → a NEW caller-owned
  session (never a write into another tenant's session). Memory/auth-off path unchanged (D-04b).
- All 22 call sites updated to `call.getOrCreateSession(...)`.
- Regression test `TenantIsolationE2ETest."tenant B running a scenario with tenant A's sessionId
  does NOT write into A's session"` (registers the scenario routes behind real JWT auth; asserts the
  run is redirected to a fresh session and alice's session keeps only her seeded event).

Verified (REST + browser, DB mode): fresh alice targeting bob's session id → redirected to a new
`auto-…` session she owns; bob's session events UNCHANGED (28); bob's session never visible to alice.
Full backend + core test suites green.
