---
phase: 13-intellij-plugin-delivery-rebuild-by-deletion
plan: 03
subsystem: ui
tags: [react, tanstack-router, tanstack-query, correlation, deep-link, ide-plugin]

# Dependency graph
requires:
  - phase: 09-session-correlation-shared-foundation-onb-01-close-out
    provides: "Phase 9 correlation mechanism (CorrelationRegistry + GET /api/sessions/resolve) and the ConnectWizard resolve-then-navigate poll reused verbatim"
provides:
  - "SPA root (`/`) accepts an optional `?correlation=<uuid>` deep-link that polls resolveCorrelation and auto-navigates to /sessions/$sessionId once the IDE-launched app's session binds (IDE-03 FE half)"
  - "Home page render path unchanged when no correlation param is present"
affects: [13-05-ide-run-action, intellij-plugin-jcef-deep-link, ide-03]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Optional search param branches the route component: validateSearch normalizes blank->undefined (CMPR-02 idiom), HomePage renders deep-link state when present else the unchanged home"
    - "Correlation deep-link reuses ConnectWizard's poll-then-navigate verbatim (token-scoped useQuery + 300ms refetchInterval + one-shot useRef guard)"

key-files:
  created:
    - frontend/src/routes/index.test.tsx
  modified:
    - frontend/src/routes/index.tsx

key-decisions:
  - "Implemented the deep-link as a thin ?correlation= branch on the existing index route (planner's option A) rather than a dedicated /connect route — minimal surface, byte-equivalent home when absent"
  - "Split the route component into HomePage (search branch) + CorrelationDeepLink (poll/navigate) + Home (unchanged page) so the hooks-conditional concern is isolated to a child component, avoiding any react-hooks rule pressure without an eslint-disable"

patterns-established:
  - "Deep-link entry pattern: validateSearch normalize blank->undefined, useSearch({strict:false}) for test-mountability, child component owns the poll+one-shot-navigate"

requirements-completed: [IDE-03]

# Metrics
duration: 12min
completed: 2026-06-29
---

# Phase 13 Plan 03: SPA-root ?correlation= deep-link auto-connect Summary

**Added a `?correlation=<uuid>` deep-link to the SPA root that reuses the Phase 9 resolve-then-navigate poll verbatim to auto-open the IDE-launched app's live session, leaving the home page byte-equivalent when the param is absent (IDE-03 FE half).**

## Performance

- **Duration:** ~12 min
- **Started:** 2026-06-29T05:59:00Z
- **Completed:** 2026-06-29T06:11:31Z
- **Tasks:** 1
- **Files modified:** 2 (1 modified, 1 created)

## Accomplishments
- `frontend/src/routes/index.tsx` now `validateSearch`-normalizes an optional `correlation?: string` (blank → `undefined`, T-13-06 mitigation) and branches: present → a "Connecting to your app…" state running the resolve poll; absent → the existing home page unchanged.
- The deep-link state reuses `ConnectWizard.tsx`'s poll-then-navigate logic verbatim: a token-scoped `useQuery({ queryKey:['resolve-correlation', correlation], queryFn:()=>apiClient.resolveCorrelation(correlation), enabled, refetchInterval:300 })` plus a one-shot `useRef` guard that `navigate({ to:'/sessions/$sessionId', params:{ sessionId } })` exactly once. No backend change, no new endpoint (D-08).
- A "View sessions" fallback link keeps the deep-link state from being a dead-end while resolve is pending (Pitfall 6).
- New `frontend/src/routes/index.test.tsx` covers all three behaviors (deep-link auto-navigate, no-param home render, poll-until-resolved one-shot) using the standalone memory-router + QueryClient idiom.

## Task Commits

Each task was committed atomically:

1. **Task 1: Add ?correlation= deep-link branch to the index route (TDD)** - `7bb6482` (feat)

_Test (RED) and implementation (GREEN) were authored and committed together in a single atomic `feat` commit; RED was verified failing (3 new tests red, 518 pre-existing green) before GREEN was written._

## Files Created/Modified
- `frontend/src/routes/index.tsx` - Added `validateSearch` for optional `?correlation=`, split into `HomePage` (search branch) + `CorrelationDeepLink` (resolve poll + one-shot navigate) + `Home` (unchanged page).
- `frontend/src/routes/index.test.tsx` - Standalone memory-router + QueryClient tests for the three required behaviors; mocks `@/lib/api-client` (`resolveCorrelation`, `listSessions`) and `@/components/Layout`.

## Decisions Made
- Thin `?correlation=` branch on the index route (planner's option A) over a dedicated `/connect` route — smallest surface, home stays byte-equivalent when absent.
- Isolated the conditional resolve/navigate hooks into a `CorrelationDeepLink` child component so the parent's early-return branch never conditionally calls hooks — no `eslint-disable react-hooks` needed (the project's flat ESLint config does not register react-hooks and would error on such a directive).

## Deviations from Plan

None - plan executed exactly as written. The implementation matches the plan's `<action>` (validateSearch normalize blank→undefined, verbatim ConnectWizard poll/navigate, literal Tailwind, no new dep, no react-hooks disable, View-sessions fallback) and the three specified test behaviors.

## Issues Encountered
- The worktree's `frontend/node_modules` was absent; ran `pnpm install --frozen-lockfile` (deterministic install of the existing pinned lockfile — not a new package add) to populate it before running the FE gates. `frontend/package.json` and `frontend/pnpm-lock.yaml` are unchanged (`git diff --stat` empty for both).

## Verification

- `pnpm test` — 521 passed (62 files), including the 3 new index-route behaviors.
- `npx tsc --noEmit` — 0 errors.
- `pnpm lint` — 0 errors, 5 warnings (all pre-existing: `mockServiceWorker.js`, `CoroutineTreeGraph.test.tsx`, `use-timeline.ts`, `utils.ts`); within the ≤6 budget. No warnings originate from `index.tsx`/`index.test.tsx`.
- `index.tsx` contains no `eslint-disable react-hooks` directive and imports only existing deps.
- `frontend/package.json` and `frontend/pnpm-lock.yaml` unchanged.
- No correlation param → home page heading "Coroutine Visualizer" + "View Sessions" button still render (Test 2).

## User Setup Required

None - no external service configuration required. This is the FE half of IDE-03; the IDE side mints the same UUID into the agent args and the JCEF URL (Plan 05).

## Next Phase Readiness
- The SPA root is ready to be deep-linked by the IntelliJ JCEF browser / system-browser fallback at `http://127.0.0.1:<port>/?correlation=<uuid>` (Plan 05 / D-08/D-09).
- No backend, schema, or endpoint changes were made; the Phase 9 correlation mechanism is reused unchanged.

## Self-Check: PASSED

- FOUND: frontend/src/routes/index.tsx
- FOUND: frontend/src/routes/index.test.tsx
- FOUND: .planning/phases/13-intellij-plugin-delivery-rebuild-by-deletion/13-03-SUMMARY.md
- FOUND commit: 7bb6482 (Task 1)

---
*Phase: 13-intellij-plugin-delivery-rebuild-by-deletion*
*Completed: 2026-06-29*
