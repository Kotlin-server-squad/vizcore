---
title: Validation endpoint 404s for EVERY session in DB mode (unscoped SessionManager lookup)
area: backend
severity: high
status: fixed
fixed: 2026-06-22
found: 2026-06-22
phase: 3
requirement: ADR-012 (validation engine) / CR-01 / D-03 (tenant scoping)
discovered_during: Phase-3 browser UAT deep-dive (Scenarios → Order Processing → "is validation working?")
ledger_id: F7
---

## Symptom (reproduced live, DB mode)
`POST /api/validate/session/{id}` returned **404 "Session not found"** for *every* real session:

| Target | validate | but readable? |
|---|---|---|
| fresh scenario session `auto-1782130035914-…` | 404 | `/events` → 200 |
| old `uat-deepdive-1782108875917` | 404 | `/api/sessions/{id}` → **200** |
| bogus id | 404 | n/a |

Sessions that are fully readable through the normal (tenant-scoped) read paths could NOT be
validated. The validation feature was effectively dead in any `storage.type=database` deployment.

## Root cause
`ValidationRoutes.kt` looked the session up via the **unscoped in-memory** `SessionManager.getSession(sessionId)`.
In DB mode the sessions live in the Exposed store (`ExposedSessionStore`), and `SessionManager`'s
in-memory map is always empty — so the lookup returned null → 404 for all sessions. Same class of bug
as F5 (route bypassing the tenant-scoped store).

## Why tests didn't catch it
`ValidationRoutesTest` boots the default app in **memory mode**, where `SessionManager` *does* hold the
session — so the happy path passed and the DB-mode path had zero coverage.

## ✅ RESOLUTION (2026-06-22)
- `ValidationRoutes.kt`: resolve the session via `call.resolveScopedSession(sessionId)` — the SAME
  tenant-scoped helper the read paths use (DB store when persistence is on; falls back to
  `SessionManager` in memory/auth-off mode). Removed the now-unused `SessionManager` import.
- `SessionRoutes.kt`: `resolveScopedSession` visibility `private` → `internal` (shared within the
  `routes` package, alongside the already-`internal` `tenantScopedStore`).
- Regression: `TenantIsolationE2ETest.validate session works in DB mode for the owner and stays 404
  cross-tenant` — registers `registerValidationRoutes()` in the H2-backed authed app; asserts owner
  200 (+ results + timing) and cross-tenant 404 with no id leak.

## Verified live
After restart (DB mode), `POST /api/validate/session/auto-1782130035914-…` → **200**, 41 checks across
8 rules + timing report, all passing. The same call returned 404 immediately before the fix.

## Related / follow-up
- The catalog (`GET /api/validate/rules`) still advertises an **"EventOrdering"** rule that
  `ValidationRoutes` never runs (dead entry). The requested `EventsInExactOrder` rule (seq strictly
  increasing + contiguous; no gaps/dups/reorder) should replace/back it. Tracked separately as the
  in-progress validation-ordering feature.
