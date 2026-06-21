---
phase: 03-persistence-auth-sharing
reviewed: 2026-06-21T00:00:00Z
depth: standard
files_reviewed: 32
files_reviewed_list:
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/Application.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/Auth.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/Routing.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/MetricsWiring.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/auth/ApiKeyStore.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/auth/JwtConfig.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/auth/Principals.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/auth/Tenancy.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/auth/UserStore.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/persistence/DatabaseFactory.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/persistence/DbRetentionPolicy.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/persistence/ExposedEventStore.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/persistence/ExposedSessionStore.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/persistence/tables/EventsTable.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/persistence/tables/SessionsTable.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/persistence/tables/SharesTable.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/AuthRoutes.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareDtos.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareRoutes.kt
  - backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareService.kt
  - backend/src/main/resources/application.yaml
  - backend/src/main/resources/db/migration/common/V1__core_schema.sql
  - frontend/src/components/DispatcherOverview.tsx
  - frontend/src/components/SessionDetails.tsx
  - frontend/src/components/share/ManageShares.tsx
  - frontend/src/components/share/ShareDialog.tsx
  - frontend/src/hooks/use-thread-activity.ts
  - frontend/src/lib/api-client.ts
  - frontend/src/lib/auth-store.ts
  - frontend/src/lib/navigation.ts
  - frontend/src/main.tsx
  - frontend/src/routes/login.tsx
  - frontend/src/routes/shared.$token.tsx
  - frontend/src/types/share.ts
findings:
  critical: 4
  warning: 7
  info: 5
  total: 16
status: issues_found
---

# Phase 3: Code Review Report

**Reviewed:** 2026-06-21
**Depth:** standard
**Files Reviewed:** 32
**Status:** issues_found

## Summary

This phase adds API-key + JWT auth, multi-tenant isolation, JDBC/Exposed persistence, and revocable share tokens. The security plumbing is mostly well-built: SHA-256 keys with constant-time compare, Argon2id password verification with uniform 401s, explicit JWT algorithm (no `none`), parameterized Exposed DSL everywhere (no SQL injection found), and a share-resolution status matrix that does not leak revoked-vs-unknown.

However, the central security promise of the phase — tenant isolation — is **not actually enforced on most read paths**. The tenant filter only runs for four routes (`POST/GET/GET-one/DELETE /api/sessions`); every sub-resource route and the entire sharing surface fall back to unscoped `SessionManager.getSession`, which returns any tenant's session. This is a cross-tenant data-leak (BLOCKER) that defeats AUTH-04/D-03. There are three further Critical findings (share ownership bypass, share-creator authorization gap on `created_by`, and an event-trim correctness bug that can over- or under-delete), plus quality issues around metrics wiring, fail-open defaults, and DB credentials.

## Structural Findings (fallow)

No `<structural_findings>` block was provided with this review; this section is intentionally empty.

## Narrative Findings (AI reviewer)

## Critical Issues

### CR-01: Tenant isolation bypassed on every session sub-resource and the SSE stream

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt:137-316` (events, hierarchy, threads, timeline, `sse /stream`)
**Issue:** Only the four top-level session routes (`createSession`, `listSessions`, `getSession/{id}`, `delete`) resolve and pass `call.resolveTenant()` into the tenant-scoped store. Every sub-resource route resolves the session via `SessionManager.getSession(sessionId)` directly. That method (`SessionManager.kt:105-108`) delegates to `backingStore.getSession(sessionId)`, which maps to `ExposedSessionStore.getSession(sessionId)` → `getSession(sessionId, TenantContext.Unscoped)` (`ExposedSessionStore.kt:46`) — i.e. `Op.TRUE`, no tenant filter. Consequently tenant A can read tenant B's `/events`, `/hierarchy`, `/threads`, `/coroutines/{id}/timeline`, and the live `/stream` simply by knowing/guessing the session id (ids are `name-<millis>`, low-entropy — see CR-02). The `GET /api/sessions/{id}` route is correctly scoped, so the entry returns 404 cross-tenant, but the *unscoped* sub-resources still serve the full event payload, which includes the entire session content. This is a direct cross-tenant data leak that nullifies AUTH-04 / D-03.
**Fix:** Route every per-session read through the tenant-scoped store with the resolved tenant, e.g.:
```kotlin
val store = tenantScopedStore()
val session = if (store != null) store.getSession(sessionId, call.resolveTenant())
              else SessionManager.getSession(sessionId)
if (session == null) { call.respond(HttpStatusCode.NotFound, ...); return@get }
```
Apply this in `/events`, `/hierarchy`, `/threads`, `/coroutines/{id}/timeline`, and the `sse(".../stream")` handler. Do not call `SessionManager.getSession` (unscoped) from any authenticated session-bound route.

### CR-02: Share creation, listing, and revocation perform no tenant/ownership check

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareRoutes.kt:52-123`, `backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareService.kt:150-187`
**Issue:** `POST /api/sessions/{id}/share` checks existence with `SessionManager.getSession(sessionId)` (unscoped — CR-01), so any authenticated tenant can mint a public, never-expiring share link for **another tenant's** session, permanently exfiltrating its full event history through the public `/api/shared/{token}` route. `GET /api/sessions/{id}/shares` (`listForSession`) and `DELETE /api/sessions/{id}/shares/{token}` (`revoke`) filter on `session_id`/`token` only — never on the calling principal or the share's `created_by`. So any authenticated user can list and revoke any other user's share links for any session id. The `created_by` column is recorded but never enforced.
**Fix:** Before minting, verify the caller owns the session via the tenant-scoped store (`store.getSession(sessionId, call.resolveTenant()) != null`), returning 404 otherwise. Scope `listForSession`/`revoke` by `created_by` (or by verified session ownership) so a principal can only see/revoke shares it owns:
```kotlin
fun revoke(sessionId: String, token: String, createdBy: String): Boolean =
    transaction(db) {
        SharesTable.deleteWhere {
            (SharesTable.sessionId eq sessionId) and
            (SharesTable.token eq token) and
            (SharesTable.createdBy eq createdBy)
        }
    } > 0
```

### CR-03: Low-entropy, guessable session ids enable cross-tenant enumeration

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/persistence/ExposedSessionStore.kt:59-61`
**Issue:** Session ids are `"$name-${System.currentTimeMillis()}"` (or `"session-<millis>"`). The only entropy is a millisecond timestamp — fully predictable and enumerable. Combined with CR-01/CR-02 (unscoped sub-resource reads and unscoped share minting), an attacker who knows a victim created a session around time T can brute-force the id over a small millisecond window and read its events or mint a public share for it. Even independent of those bugs, predictable ids are an authorization-by-obscurity smell for a multi-tenant store.
**Fix:** Generate session ids with a CSPRNG, e.g. append `UUID.randomUUID()` (as `ShareService.create` already does for tokens) rather than a timestamp: `"${name ?: "session"}-${UUID.randomUUID()}"`. Reserve the timestamp for an ordering column if needed.

### CR-04: Event-trim deletes the wrong row count when `seq` is not unique / contiguous

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/persistence/DbRetentionPolicy.kt:132-152`
**Issue:** `trimEvents` computes `keepCutoffSeq = seqs.sortedDescending()[maxEventsPerSession - 1]` and then deletes `EventsTable.seq less keepCutoffSeq`. This is correct only if every `seq` in a session is unique. The schema (`EventsTable.kt`, `V1__core_schema.sql`) does **not** enforce uniqueness on `(session_id, seq)` — it is only an index — and `seq` is supplied by the application (`event.seq`). If two events ever share a `seq` (re-seeded session, replayed import, bug in seq assignment), `less keepCutoffSeq` retains *all* rows equal to the cutoff seq, so the retained count exceeds the cap; conversely, because it uses strict `<`, the boundary rows at exactly `keepCutoffSeq` are never trimmed even when there are duplicates pushing the count over. The cap is therefore not reliably enforced. Additionally the whole `events` table is loaded into memory (all `(session_id, seq)` pairs) to compute the cutoff, which is fragile at scale (flagged as correctness-adjacent, not perf).
**Fix:** Enforce a unique constraint on `(session_id, seq)` in the migration, OR delete by row identity rather than seq value: select the ids of the newest `maxEventsPerSession` rows per session (ordered by `seq DESC, id DESC`) and delete the complement. A DB-side `DELETE ... WHERE id NOT IN (SELECT id ... ORDER BY seq DESC LIMIT cap)` per over-cap session avoids both the duplicate-seq bug and the full-table load.

## Warnings

### WR-01: Auth fails open by default — any misconfiguration silently disables all authn

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/Auth.kt:58-75`, `backend/src/main/resources/application.yaml:8-24`
**Issue:** `authDisabled = apiKeyStore.isEmpty && !jwtUsable`. The shipped `application.yaml` defaults `auth.apiKey`, `auth.keys`, `auth.users`, and `jwt.secret` all to empty, so the out-of-box state is fully public. That is the documented D-04a invariant for memory mode, but it is dangerous in combination with `storage.type=database`: a deployer who sets `STORAGE_TYPE=database` (persisting real tenant data) but forgets `JWT_SECRET`/users gets a silently public server with no 401 anywhere. The only signal is a single `WARN` log line (`Application.kt:110`). Persisted multi-tenant data should not fail open.
**Fix:** When `storage.type=database` and no auth is configured, either refuse to start or require an explicit opt-in flag (e.g. `auth.allowAnonymous=true`). At minimum, escalate the warning to a startup-blocking error unless the operator explicitly acknowledges open access.

### WR-02: Tenant-scoped session creation bypasses metrics/onSessionCreated wiring

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt:45-50`
**Issue:** When persistence is on, `POST /api/sessions` calls `store.createSession(name, call.resolveTenant())` directly on `ExposedSessionStore`, bypassing `SessionManager.createSession`, which is the only place that invokes `onSessionCreated` (`SessionManager.kt:86`). `MetricsWiring.kt:60-81` attaches `events.emitted`, `events.dropped`, the per-session buffer gauge, and `event.processing.duration` inside that callback. So every DB-mode session created through the API gets **no metrics instrumentation** — the ADR-020 counters silently undercount and `events.buffer.size` is never registered for these sessions. (Sessions created via `SessionManager.createSession` in memory mode are fine.)
**Fix:** Route tenant-scoped creation through a path that still fires `onSessionCreated`, or have `ExposedSessionStore.createSession(name, tenant)` invoke the `SessionManager.onSessionCreated` hook for the new session before returning. Keep a single creation choke point so instrumentation cannot be skipped.

### WR-03: H2 file DB defaults to a blank-password `sa` account with on-disk persistence

**File:** `backend/src/main/resources/application.yaml:47-51`, `backend/src/main/kotlin/com/jh/proj/coroutineviz/persistence/DatabaseFactory.kt:34-41`
**Issue:** The default DB config is `jdbc:h2:file:./data/coroutineviz;AUTO_SERVER=TRUE`, user `sa`, empty password. `AUTO_SERVER=TRUE` opens a TCP listener so other local processes can connect to the same file DB, and the `sa`/empty-password combo means no credential gates that listener. For a tool whose DB now holds multi-tenant session data and share tokens, an unauthenticated local TCP-reachable H2 is a meaningful exposure on shared hosts/CI. The `url=$url` is also logged at INFO (`DatabaseFactory.kt:40`) — fine for H2 here, but if a deployer puts credentials in the URL (common for Postgres), they would be logged despite the "never log the password" comment.
**Fix:** Drop `AUTO_SERVER=TRUE` from the default (use embedded-only), and/or set a non-trivial default password. Sanitize the URL before logging (strip any `user=`/`password=` query params and userinfo) so credential-bearing JDBC URLs are not leaked.

### WR-04: Share retention guard / resolve mix `kotlin.time.Instant` with DB timestamp columns and `expiresAt <= now` boundary

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/persistence/DbRetentionPolicy.kt:101-120`, `backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareService.kt:122-126`
**Issue:** Two related robustness concerns. (1) `DbRetentionPolicy.cleanup` calls `clock()` twice — once for `now`, once for `cutoff` — so under load `now` and `cutoff` are computed from two different wall-clock reads; harmless today but means the "age" window is not atomic and tests with a fixed `clock` lambda that has side effects could diverge. (2) `ShareService.resolve` treats `expiresAt <= now` as expired, while `DbRetentionPolicy` treats a share as *active* when `expiresAt greater now` (strictly). At the exact tick `expiresAt == now`, the share is simultaneously "expired" (resolve → 410) and "not active" (retention may delete the backing session). The two boundaries are consistent enough but the equality tick is a latent off-by-one between the user-facing expiry and the retention guard; document or align them (`<` vs `<=`).
**Fix:** Read the clock once in `cleanup()` and derive both `now` and `cutoff` from it. Align the expiry boundary operator between `resolve` and the retention `activeShareSubquery` so a share is "active for retention" for exactly as long as `resolve` treats it valid.

### WR-05: `ApiKeyStore.validate` constant-time guarantee is undermined by `firstOrNull` short-circuit and hex re-encoding

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/auth/ApiKeyStore.kt:44-51`
**Issue:** The doc claims a timing-side-channel-free compare, but `keys.firstOrNull { MessageDigest.isEqual(...) }` short-circuits on the first match, so the *number of iterations* leaks how far down the key list the matching key sits (a minor multi-key oracle). More importantly the comparison is done over the lowercase **hex string bytes**, not the raw digest bytes; `entry.sha256Hash.lowercase()` is recomputed on every call for every entry. `isEqual` is constant-time per candidate, but comparing hex text (64 bytes) instead of the 32 raw digest bytes is wasteful and the per-entry `.lowercase().toByteArray()` allocates each call. Functionally correct, but the "defeats timing side-channels" claim is overstated.
**Fix:** Pre-decode stored hashes to raw `ByteArray` once at construction (lowercased), compare against the raw `sha256(rawKey)` digest bytes, and iterate over all entries (accumulate a boolean) rather than `firstOrNull` if the multi-key count must stay secret. At minimum, drop the per-call `.lowercase()` allocation by normalizing in `fromConfig`.

### WR-06: SSE error path swallows tenant 404 into a 200 stream with an in-band error frame

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/SessionRoutes.kt:231-241`
**Issue:** For the SSE stream, a missing session returns an in-band `event: error` frame on an already-200 response. Once CR-01 is fixed to make this tenant-scoped, a cross-tenant access will also surface as a 200 + error frame rather than a clean 404, which (a) is inconsistent with the JSON routes' 404 and (b) gives a weak oracle (200-with-error-frame vs connection refused). Minor on its own; called out so the CR-01 fix also normalizes the not-found/cross-tenant response here.
**Fix:** When the tenant-scoped lookup returns null, respond `HttpStatusCode.NotFound` before upgrading to SSE (reject pre-stream) rather than emitting an error event on a successful stream.

### WR-07: `resolveBaseUrl` trusts request-derived host/scheme for the public share URL

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/share/ShareRoutes.kt:193-204`
**Issue:** When `app.publicBaseUrl` is unset, the share URL is built from `call.request.local` (serverHost/scheme/port). Behind a proxy without `XForwardedHeaders`, this yields an internal host (e.g. `http://localhost:8080/shared/...`), producing share links that do not work externally. It is not a classic Host-header-injection (it reads `local`, not the `Host` header) but it silently emits wrong/unusable links in the common reverse-proxy deployment, and the value is handed to users as a clickable link.
**Fix:** Prefer `app.publicBaseUrl` and log a warning (or fail) when it is unset in `storage.type=database`/proxied deployments. Document that `publicBaseUrl` is effectively required for correct external links.

## Info

### IN-01: `Role.fromConfig` silently downgrades unknown roles to VIEWER

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/auth/Principals.kt:19-23`
**Issue:** A typo'd role in config (e.g. `role: admln`) silently becomes `VIEWER` with no warning. For a key intended as ADMIN this is a fail-safe direction (good) but the silent downgrade can mask a misconfiguration that locks an operator out.
**Fix:** Log a warning when a non-blank role string fails to parse before defaulting to VIEWER.

### IN-02: `@file:Suppress("DEPRECATION")` blanket-suppresses the deprecated Ktor `Principal` API across whole files

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/Auth.kt:1`, `auth/Principals.kt:1`, `auth/Tenancy.kt:1`
**Issue:** File-level deprecation suppression hides any *future* deprecation in these files, not just the intended `Principal` one. As Ktor's auth API evolves, real deprecations will be invisible here.
**Fix:** Narrow to targeted `@Suppress("DEPRECATION")` on the specific `Principal`-implementing declarations, or migrate off the deprecated `Principal` marker.

### IN-03: `LoginRequest` defaults username/password to empty strings, weakening the typed contract

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/routes/AuthRoutes.kt:17,41`
**Issue:** `data class LoginRequest(val username: String = "", val password: String = "")` lets a body like `{}` deserialize successfully into blank creds, which is then caught by the `isBlank()` guard — functional, but the defaults make a malformed payload look valid at the type level. Minor.
**Fix:** Drop the defaults and treat a missing field as a deserialization failure (already handled by the `runCatching` → uniform 401).

### IN-04: `ExposedEventStore.byCoroutine` loads the entire session and filters in memory

**File:** `backend/src/main/kotlin/com/jh/proj/coroutineviz/persistence/ExposedEventStore.kt:68`
**Issue:** `byCoroutine` calls `all()` (full session load + deserialize) then filters in Kotlin. Correctness is fine; flagged only as a maintainability/consistency note since the other reads use DSL `where` predicates. (Perf is out of v1 scope.)
**Fix:** When practical, push a `kind`/coroutine predicate into SQL, or document that `byCoroutine` is intentionally a small-N convenience.

### IN-05: `getDispatcherColor` default of `'primary'` collides Default and unknown dispatchers visually

**File:** `frontend/src/components/DispatcherOverview.tsx:152-160`
**Issue:** Unknown dispatcher names fall back to `'primary'`, the same color as `Default`, so a custom dispatcher is indistinguishable from the Default dispatcher in the overview. Cosmetic.
**Fix:** Reserve a distinct neutral color (e.g. `default`) for unmapped dispatcher names.

---

_Reviewed: 2026-06-21_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
