---
phase: 03-persistence-auth-sharing
plan: 05
subsystem: frontend-auth
tags: [react, tanstack-router, jwt, localStorage, sse, heroui, vitest, auth]

# Dependency graph
requires:
  - phase: 03-02
    provides: "POST /api/auth/token ({token, expiresAt}); SSE ?token= locked contract; uniform 401"
provides:
  - "auth-store: getToken/setToken/clearToken — localStorage `vizcore.jwt` + in-memory source of truth, rehydrated on load (D-06)"
  - "api-client Bearer injection on /api fetches (token present only, D-07/D-08); 401 -> clearToken + navigateToLogin (D-05)"
  - "createEventSource ?token= append (locked SSE contract) when token present, unchanged when off"
  - "navigation indirection (registerNavigator/navigateToLogin) — testable router-navigate seam for non-React code"
  - "/login route (outside Layout) with UI-SPEC copy + redirect-back resume"
  - "apiClient.login (typed, distinguishes 401 wrong-creds from network/server)"
  - "share api-client methods + types/share.ts DTOs (typed surface for Plan 06)"
affects: [03-06-sharing-ui]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "framework-agnostic auth-store: in-memory `let token` rehydrated from localStorage at module init (D-06)"
    - "navigate indirection (registerNavigator) so the api-client redirects to /login from outside React without importing the router"
    - "login() bypasses fetchJson's global 401-interception (token endpoint 401 = wrong creds, not session-expiry); throws typed LoginAuthError"
    - "getSharedSession returns a typed SharedSessionResult (ok|expired|not-found|rate-limited) mapping the 200/410/404/429 matrix instead of throwing"

key-files:
  created:
    - frontend/src/lib/auth-store.ts
    - frontend/src/lib/navigation.ts
    - frontend/src/types/share.ts
    - frontend/src/lib/auth-store.test.ts
    - frontend/src/routes/login.tsx
    - frontend/src/routes/login.test.tsx
  modified:
    - frontend/src/lib/api-client.ts
    - frontend/src/lib/api-client.test.ts
    - frontend/src/main.tsx
    - frontend/src/routeTree.gen.ts

key-decisions:
  - "Router-navigate indirection: a tiny lib/navigation.ts (registerNavigator/navigateToLogin) wired to the real router in main.tsx — the api-client lives outside React, and importing the concrete router (created in main.tsx) would be circular; the indirection keeps the 401->/login path unit-testable with a spy"
  - "login() is a dedicated method (NOT fetchJson): the token-endpoint 401 means wrong-credentials and must NOT trigger the global clearToken+navigate (the user is already on /login); it throws a typed LoginAuthError the form branches on for the two error copies"
  - "Redirect-back via a ?redirect= search param on /login (validateSearch); default to '/' — the resume mechanism for D-05"
  - "Share DTOs live in types/share.ts and the share methods on the api-client choke point now (createShare/listShares/revokeShare/getSharedSession) so Plan 06 does not re-open the client; getSharedSession maps 410/404/429 to a typed result and attaches NO Bearer (the share token is the credential)"

requirements-completed: [AUTH-03]

# Metrics
duration: ~8min
completed: 2026-06-21
---

# Phase 3 Plan 05: Frontend Auth Summary

**The frontend auth loop closed at the single `/api` choke point: the api-client attaches `Authorization: Bearer <jwt>` only when a token exists (no header when auth is off — D-07/D-08 invisibility), intercepts a 401 to clear the token and route to a new `/login` screen (D-05), and appends the JWT as the locked `?token=` query param on the SSE EventSource (Pitfall 2); the JWT lives in localStorage with an in-memory source of truth rehydrated on load (D-06), and a HeroUI `/login` form posts to `POST /api/auth/token` with the exact UI-SPEC copy and resumes the user to their originating route.**

## Performance
- **Duration:** ~8 min
- **Started:** 2026-06-21T08:51:32Z
- **Completed:** 2026-06-21
- **Tasks:** 2
- **Files modified:** 10 (6 created, 4 modified)

## Accomplishments
- **`lib/auth-store.ts`** — framework-agnostic token store: an in-memory `let token` is the source of truth, rehydrated from `localStorage.getItem('vizcore.jwt')` at module init (D-06). `getToken`/`setToken`/`clearToken`; storage access guarded against unavailable localStorage (private mode / SSR).
- **`api-client.ts` `fetchJson`** — reads `getToken()`; adds `Authorization: Bearer <token>` only when non-null (auth-off sends no header, D-07/D-08). On `401`: `clearToken()` + `navigateToLogin()` then rethrows so callers' error states still render. When auth is off the backend fails open and never 401s, so `/login` is never reached.
- **`api-client.ts` `createEventSource`** — appends `?token=${encodeURIComponent(token)}` only when a token exists; URL unchanged otherwise. Binds to the LOCKED `token` query-param name from 03-02 (SSE_TOKEN_QUERY_PARAM).
- **`lib/navigation.ts`** — `registerNavigator`/`navigateToLogin` indirection; wired to `router.navigate` in `main.tsx`. Lets the non-React api-client redirect on 401 without importing the router (no circular import), and lets tests inject a spy.
- **`api-client.ts` `login`** — dedicated method posting `{username, password}` to `/api/auth/token`; 401 throws a typed `LoginAuthError` (wrong creds), any other failure throws a generic `Error` (network/server). Bypasses the global 401 interception by design.
- **`api-client.ts` share methods + `types/share.ts`** — `createShare(sessionId, expiresIn)` (201 `{token,url,expiresAt}`), `listShares` (`ShareSummary[]`), `revokeShare`, and `getSharedSession(token)` which attaches NO Bearer (token IS the credential) and returns a typed `SharedSessionResult` mapping 200/410/404/429 → `ok`/`expired`/`not-found`/`rate-limited`. Typed surface ready for Plan 06.
- **`routes/login.tsx`** — `/login` file route OUTSIDE the `Layout` nav chrome (UI-SPEC focal point); HeroUI `Card`/`Input`/`Button`; EXACT copy (heading "Sign in", subhead "This server requires authentication.", `Username`/`Password`, CTA "Sign in" / "Signing in…", 401 "Incorrect username or password." via `ErrorAlert`, network "Could not reach the server. Check your connection and try again."). zod non-empty validation; password `type=password`; success → `setToken` + navigate to `?redirect=` (default `/`); credentials never logged.

## Task Commits
1. **Task 1: auth-store + api-client Bearer/401 + token-aware SSE + share methods** — `b12970c` (feat)
2. **Task 2: /login route — UI-SPEC copy, HeroUI form, token endpoint** — `48661b4` (feat)

## Files Created/Modified
- `frontend/src/lib/auth-store.ts` (created) — localStorage `vizcore.jwt` + in-memory source of truth
- `frontend/src/lib/navigation.ts` (created) — registerNavigator/navigateToLogin indirection
- `frontend/src/types/share.ts` (created) — share DTOs + `SharedSessionResult` typed-status union
- `frontend/src/lib/auth-store.test.ts` (created) — persist/read/clear/rehydrate coverage
- `frontend/src/routes/login.tsx` (created) — `/login` route + form
- `frontend/src/routes/login.test.tsx` (created) — success/redirect/401/network/loading/validation coverage
- `frontend/src/lib/api-client.ts` (modified) — Bearer + 401 interception, SSE ?token=, login(), share methods, LoginAuthError/LoginResponse
- `frontend/src/lib/api-client.test.ts` (modified) — Bearer attach / no-header-when-off / 401 clear+navigate / SSE token append / share-method coverage
- `frontend/src/main.tsx` (modified) — registerNavigator wired to router.navigate
- `frontend/src/routeTree.gen.ts` (modified) — regenerated to register `/login`

## Decisions Made
- **Router-navigate indirection (recorded per `<output>`):** `lib/navigation.ts` exposes `registerNavigator(fn)` and `navigateToLogin()`. The api-client calls `navigateToLogin()` on a 401; `main.tsx` registers `(path) => router.navigate({ to: path })` at bootstrap. Chosen over importing the concrete router (circular: the router is created in `main.tsx`, which imports the api-client transitively) and keeps the 401→/login path unit-testable with a `vi.fn()` spy.
- **`login()` does not reuse `fetchJson`:** a 401 from the token endpoint means "wrong credentials," not "session expired," so it must NOT fire the global `clearToken + navigateToLogin` (the user is already on `/login`). `login()` does a raw fetch and throws a typed `LoginAuthError` on 401 so the form picks the "Incorrect username or password." copy vs. the network copy for everything else.
- **Redirect-back mechanism (recorded per `<output>`):** `/login` declares a `?redirect=` search param via `validateSearch`; on success it navigates to `redirect ?? '/'`. A future 401-interceptor enhancement can populate `?redirect=` with the current path; the route already honors it.
- **Share DTO types (recorded per `<output>`, Plan 06 consumes):** `types/share.ts` mirrors backend `ShareDtos.kt` — `CreateShareResponse{token,url,expiresAt}`, `ShareSummary{token,expiresAt,accessCount,lastAccessedAt}`, `SharedSessionResponse{session,events}`, plus a `SharedSessionResult` discriminated union (`ok|expired|not-found|rate-limited`). `getSharedSession` returns the union so Plan 06's public view branches exhaustively on the 200/410/404/429 matrix without catching raw errors, and attaches no Bearer.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] EventSource test stub was not a constructor**
- **Found during:** Task 1 (GREEN run)
- **Issue:** The SSE test stubbed `EventSource` with `vi.fn().mockImplementation(() => ({...}))`; `new EventSource(url)` then threw "X is not a constructor".
- **Fix:** Replaced with a `class FakeEventSource` that records constructed URLs into an array the assertions read. Test-only change.
- **Files modified:** `frontend/src/lib/api-client.test.ts`
- **Committed in:** `b12970c`

**2. [Rule 1 - Bug] Ambiguous "Sign in" test selector**
- **Found during:** Task 2 (GREEN run)
- **Issue:** `findByText('Sign in')` matched BOTH the `<h1>` heading and the submit button → "Found multiple elements".
- **Fix:** Page-loaded assertion uses `findByRole('heading', { name: 'Sign in' })`; the button is still targeted via `getByRole('button', { name: 'Sign in' })`. Test-only change.
- **Files modified:** `frontend/src/routes/login.test.tsx`
- **Committed in:** `48661b4`

### Convention note (not a deviation)
- The plan `<read_first>` referenced MSW for the api-client tests; the existing `api-client.test.ts` already uses the `vi.stubGlobal('fetch', mockFetch)` convention, so the new tests follow that same in-file fetch-stub pattern (no MSW server) for consistency with the file they extend. `msw` remains a dependency but is not used here.

## Authentication Gates
None — no external auth/login was required during execution (the login form built here is the product surface, not an executor gate).

## Issues Encountered
- The route tree (`routeTree.gen.ts`) is generated by the `@tanstack/router-vite-plugin` at Vite startup, with no standalone CLI in this project. Regenerated by running `vite build` directly (`node node_modules/vite/bin/vite.js build`) — `npx`/`pnpm` were intercepted by an `rtk` shell proxy in this environment. The build succeeded (2133 modules) and registered `/login` (17 refs). `frontend/dist/` is gitignored and was not committed.

## Known Stubs
None. The auth-store, Bearer/401 interception, token-aware SSE, the `/login` form, and `login()` are fully wired and test-covered against the live backend contracts. The four share methods + DTOs are fully implemented (they call the real backend endpoints) — their UI *consumers* are Plan 06's concern, a documented forward dependency, not a stub: no placeholder/empty-data code path exists.

## Threat Flags
None — all new client surface (JWT in localStorage, client-side Bearer attach, login form credentials, 401 copy) was enumerated in the plan `<threat_model>` (T-03-18..21) and matches the dispositions: JWT-in-localStorage accepted tradeoff with no token logged (T-03-18), client only ATTACHES the credential — all validation server-side (T-03-19), password field `type=password` + creds posted only to the token endpoint, never logged (T-03-20), generic 401 copy with no enumeration (T-03-21).

## Verification
- `frontend/src/lib/auth-store.test.ts` + `frontend/src/lib/api-client.test.ts`: 28 passed.
- `frontend/src/routes/login.test.tsx`: 6 passed.
- Full frontend suite: **403 passed / 45 files** (no regressions).
- ESLint: 0 errors on all new/modified files (`auth-store.ts`, `navigation.ts`, `share.ts`, `api-client.ts`, `login.tsx`, and tests); whole-repo lint has only pre-existing warnings in unrelated files.
- `tsc --noEmit`: no errors. `vite build`: green.

## Next Phase Readiness
- **Plan 06 (sharing UI):** the api-client already exposes `createShare`/`listShares`/`revokeShare`/`getSharedSession` with the `types/share.ts` DTOs and the `SharedSessionResult` typed-status union — Plan 06 builds the Share dialog, Manage-shares list, and `/shared/:token` read-only shell on top of this surface without re-opening the client. The `/shared/:token` view should branch on `getSharedSession`'s `status` for the 410/404/429 copy.

## Self-Check: PASSED

All created files exist on disk; both feature commits (`b12970c`, `48661b4`) present in git history. (Verified below.)

---
*Phase: 03-persistence-auth-sharing*
*Completed: 2026-06-21*
