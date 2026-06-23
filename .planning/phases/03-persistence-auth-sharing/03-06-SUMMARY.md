---
phase: 03-persistence-auth-sharing
plan: 06
subsystem: sharing-ui
tags: [react, tanstack-router, tanstack-query, heroui, vitest, sharing, read-only, clipboard]

# Dependency graph
requires:
  - phase: 03-04
    provides: "four share endpoints (owner POST/GET/DELETE + public GET /api/shared/{token}); 200/410/404/429 status matrix"
  - phase: 03-05
    provides: "api-client share methods (createShare/listShares/revokeShare/getSharedSession) + types/share.ts DTOs + SharedSessionResult typed union"
provides:
  - "SessionDetails readOnly prop gating all mutation/nav affordances (live-stream toggle, Run/Reset/Clear scenario controls, Share/Manage trigger) while keeping panels + replay + export — reused, not forked (D-09/D-10)"
  - "standalone /shared/$token route (no Layout/Navbar) seeding the public {session,events} into the React Query cache + branching the 200/410/404/429 matrix to UI-SPEC copy (D-12)"
  - "ShareDialog (D-11): expiry picker (1d/7d/30d/never) + create + copy-link with toasts"
  - "ManageShares (D-13): shares Table (Link/Expires/Views/Last accessed/Revoke) + destructive revoke confirm + empty/error states"
  - "useThreadActivity/useThreadLanesByDispatcher/DispatcherOverview enabled flag to disable the protected /threads fetch+poll in read-only mode"
affects: []

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "read-only reuse via cache-seeding: the shared route writes the public getSharedSession payload into queryClient under the SAME keys the viewer hooks read (['sessions', id], ['sessions', id, 'events']), so SessionDetails renders read-only with NO protected fetch — gated by a readOnly prop, never forked (D-10)"
    - "read-only thread lanes derived client-side via projectThreadActivity(events) instead of the protected GET /sessions/{id}/threads snapshot"
    - "ShareDialog/ManageShares mirror the RecordConfirmModal HeroUI Modal pattern (size=sm, ModalHeader/Body/Footer, light Cancel + primary/danger action)"
    - "optimistic revoke: revokeShare success filters the revoked token out of the cached ['shares', id] list so the row vanishes without a refetch"

key-files:
  created:
    - frontend/src/routes/shared.$token.tsx
    - frontend/src/routes/shared.$token.test.tsx
    - frontend/src/components/share/ShareDialog.tsx
    - frontend/src/components/share/ShareDialog.test.tsx
    - frontend/src/components/share/ManageShares.tsx
    - frontend/src/components/share/ManageShares.test.tsx
  modified:
    - frontend/src/components/SessionDetails.tsx
    - frontend/src/components/DispatcherOverview.tsx
    - frontend/src/hooks/use-thread-activity.ts
    - frontend/src/routeTree.gen.ts

key-decisions:
  - "Cache-seeding over prop-drilling events: rather than thread a `sharedEvents` prop through SessionDetails (which would fork the data path), the /shared route seeds queryClient with the public payload under the existing query keys. SessionDetails keeps using useSession/useSessionEvents unchanged; readOnly only gates affordances. This is the literal D-10 'reuse, not fork' contract."
  - "Read-only disables the protected /threads fetch via a new `enabled` param on useThreadActivity (threaded through useThreadLanesByDispatcher + DispatcherOverview). The shared shell has no Bearer; without this the Threads tab would 404+poll. Thread lanes are instead derived from the shared events with the same projectThreadActivity reducer used for replay."
  - "The 'Read-only shared view' banner lives in the /shared route shell (not inside SessionDetails) so it is testable at the route level and SessionDetails stays a pure affordance-gating change."
  - "410 (expired) and 404 (not-found/revoked) collapse to the SAME 'This link is no longer available' empty state — ADR-019 gives no oracle to distinguish a revoked token from an unknown one; 429 gets the distinct 'Too many requests' copy (D-12)."
  - "Revoke is optimistic: on success the revoked token is filtered out of the cached ['shares', id] list (row vanishes) rather than refetching; ShareDialog close invalidates the list so a freshly minted link appears."

requirements-completed: [SHAR-01, SHAR-02]

# Metrics
duration: ~12min
completed: 2026-06-21
---

# Phase 3 Plan 06: Sharing UI Summary

**The four Phase-3 sharing surfaces shipped on top of the Plan-05 api-client: a `readOnly` prop on `SessionDetails` gates OFF every mutation/nav affordance (live-stream toggle, Run/Reset/Clear, the Share/Manage trigger) while keeping the tree/graph/timeline/thread-lanes panels, the ReplayController and the ExportMenu — and the new standalone `/shared/$token` route (no `Layout`/Navbar) seeds the public `getSharedSession` `{session, events}` into the React Query cache under the viewer hooks' own keys so the SAME `SessionDetails` renders read-only with no Bearer-bearing fetch (D-09/D-10, reused not forked). The `ShareDialog` (D-11) mints a link with a 1d/7d/30d/never expiry picker and copies it to the clipboard with a "Link copied" toast; `ManageShares` (D-13) lists Link/Expires/Views/Last-accessed rows with a destructive `Revoke` confirm; and the shared shell branches the 200/410/404/429 matrix to the locked UI-SPEC copy (D-12).**

## Performance
- **Duration:** ~12 min
- **Tasks:** 2
- **Files modified:** 10 (6 created, 4 modified)

## Accomplishments
- **`SessionDetails` `readOnly` prop** — gates OFF the live-stream toggle, the scenario controls card (Run/Reset/Clear), and the Share/Manage-shares trigger; KEEPS the panels, the `ReplayController` (play/scrub/speed), and the `ExportMenu` (PNG/SVG/WebM/JSON) — all read operations (D-09). In read-only mode the Threads tab derives lanes from `projectThreadActivity(events)` and the protected `/threads` fetch is disabled. Reused, never forked (D-10).
- **`routes/shared.$token.tsx`** — a STANDALONE shell with NO `Layout`/Navbar. On mount fetches `getSharedSession(token)` (public, token = credential, no Bearer); on a valid result seeds `queryClient` (`['sessions', id]` + `['sessions', id, 'events']`) from the payload and renders `<SessionDetails readOnly />` plus a subtle "Read-only shared view" chip. 410/404 → the "This link is no longer available" `EmptyState`; 429 → the "Too many requests" copy (D-12).
- **`share/ShareDialog.tsx`** (D-11) — HeroUI `Modal` size="sm" mirroring `RecordConfirmModal`. Phase 1: a `Select` expiry picker "Link expires" ("1 day"/"7 days"/"30 days"/"Never" → `1d`/`7d`/`30d`/`never`, default 7d) + a "Create link" CTA. Phase 2: a read-only URL `Input` (auto-select on focus) + the helper copy + a "Copy link" action (`navigator.clipboard.writeText` → `toastSuccess('Link copied')`, failure → `toastError` leaving the URL selectable) + a "Done" dismiss. Create failure → `toastError('Could not create the share link. Try again.')`.
- **`share/ManageShares.tsx`** (D-13) — `useQuery(listShares)` → a HeroUI `Table` (Link short-token / Expires "Never" for null / Views `accessCount` / Last accessed "—" for null / `Revoke` danger). Empty → an `EmptyState` "No active share links" whose "Create link" action opens the `ShareDialog`. List-fetch failure → `ErrorAlert` "Could not load share links. Try again." `Revoke` → a destructive confirm `Modal` ("Revoke share link?") → `revokeShare` → optimistic row removal + `toastSuccess('Share link revoked')`.
- **`useThreadActivity` / `useThreadLanesByDispatcher` / `DispatcherOverview` `enabled` flag** — disables the protected thread-activity fetch + poll so the read-only shared shell (no Bearer) never 404s on `/threads`.

## Task Commits
1. **Task 1: readOnly SessionDetails + standalone /shared/$token shell** — `9478947` (feat)
2. **Task 2: Share dialog + Manage-shares list with revoke confirm** — `1b0f65c` (feat)

_TDD note: per the Plan-04/05 precedent, each task's impl and its tests were authored together (the tests reference the impl under test); RED was confirmed first (route/component modules missing → suite fails to resolve), then GREEN once wired._

## How the shared route feeds events into SessionDetails
The standalone `/shared/$token` route fetches the public `getSharedSession(token)` (no Bearer — the token IS the credential). On a `status: 'ok'` result it calls `queryClient.setQueryData(['sessions', id], session)` and `setQueryData(['sessions', id, 'events'], events)` — the EXACT keys `useSession` / `useSessionEvents` read — then renders `<SessionDetails sessionId={id} readOnly />`. SessionDetails is therefore unmodified in its data path: it reads the same hooks, which now resolve synchronously from the seeded cache. `readOnly` only gates affordances. The Threads tab, which normally hits the protected `/sessions/{id}/threads` endpoint, instead derives its lanes from the seeded events via `projectThreadActivity` (the same reducer replay uses), and the protected fetch is disabled through the new `enabled` flag.

## ShareDialog / ManageShares composition
`ManageShares` owns the `ShareDialog` (opened from the empty-state "Create link" action) and an inline destructive revoke-confirm `Modal`. `SessionDetails` mounts `ManageShares` inside an owner-only "Manage shares" `Modal` triggered by a "Share" button in the toolbar — both gated OFF when `readOnly`, so a shared link can never re-share (ADR-019). Closing the `ShareDialog` invalidates `['shares', id]` so a freshly minted link appears in the list.

## Deviations from Plan
None — plan executed as written. The `useThreadActivity` `enabled` param + `DispatcherOverview` `enabled` prop are the mechanism for the plan's stated requirement that the read-only shell "drive the panels from the events returned by the shared endpoint rather than the authenticated session hooks" — not a scope change. The impl-with-test sequencing within each task follows the documented Plan-04/05 precedent.

## Authentication Gates
None — no external auth/login was required during execution.

## Issues Encountered
- **`userEvent.setup()` clipboard stub:** `userEvent.setup()` installs its own `navigator.clipboard` stub, shadowing the test's mock so `writeText` assertions never matched. Fixed by installing the clipboard mock AFTER `userEvent.setup()` in the two copy-path tests (`installClipboard()` helper). Test-only.
- **Route tree generation:** `routeTree.gen.ts` is generated by the `@tanstack/router-vite-plugin` at Vite startup (no standalone CLI). Regenerated by running `vite build` directly (registered `/shared/$token`, 17 refs). `frontend/dist/` is gitignored and was not committed.
- The unused `waitFor` import in `shared.$token.test.tsx` (flagged by `tsc --noEmit`) was removed in the Task-2 commit.

## Known Stubs
None. All three surfaces call the real Plan-04 backend endpoints via the Plan-05 api-client: `ShareDialog` → `createShare`, `ManageShares` → `listShares`/`revokeShare`, and the `/shared/$token` shell → `getSharedSession`. No placeholder/empty-data code path exists — the read-only viewer renders the real `{session, events}` snapshot fed through the cache.

## Threat Flags
None — all new client surface was enumerated in the plan `<threat_model>` (T-03-22..25) and matches the dispositions: `readOnly` gates OFF all mutation/nav affordances with the server enforcing read-only independently (defense in depth, T-03-22/25); the 429 surfaces the generic "Too many requests" copy with the per-IP limit enforced server-side (T-03-23); the share URL written to the clipboard contains a revocable, expiring token on owner-initiated copy (T-03-24 accept).

## Verification
- `frontend/src/routes/shared.$token.test.tsx`: 6 passed (valid/410/404/429 + no-chrome + read-only wiring).
- `frontend/src/components/share/ShareDialog.test.tsx`: 4 passed (create/expiry-map, copy+toast, copy-failure, create-failure).
- `frontend/src/components/share/ManageShares.test.tsx`: 4 passed (rows, empty-state, list-error, revoke-confirm + optimistic removal).
- `frontend/src/components/SessionDetails.test.tsx`: 18 passed (no regression).
- **Full frontend suite: 417 passed / 48 files** (up from 403 / 45; no regressions).
- ESLint: 0 errors on all new/modified files. `tsc --noEmit`: no errors. `vite build`: green (2134 modules).
- `/shared/$token` renders WITHOUT the `Layout`/Navbar (asserted by the route test's no-chrome check); `SessionDetails` is a single component reused via `readOnly` — no `SharedSessionDetails` fork file exists.

## Self-Check: PASSED

All six created files exist on disk; both feature commits (`9478947`, `1b0f65c`) present in git history. (Verified below.)

---
*Phase: 03-persistence-auth-sharing*
*Completed: 2026-06-21*
