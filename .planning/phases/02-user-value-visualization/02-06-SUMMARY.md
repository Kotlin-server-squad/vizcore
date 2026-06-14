---
phase: 02-user-value-visualization
plan: 06
subsystem: comparison-ui
tags: [react, typescript, tanstack-router, heroui, comparison, routing, tdd]

# Dependency graph
requires:
  - phase: 02-user-value-visualization
    plan: 01
    provides: "GET /api/sessions/compare?a=&b= contract + SessionComparison type"
provides:
  - "/compare TanStack route with validateSearch(a,b) — shareable ?a=&b= URLs (D-10)"
  - "ComparisonView controlled A/B selection (a/b props + onChange) routed to URL search params"
  - "SyncedTreePair: two synced coroutine trees with A-only/B-only delta badges + cross-tree selection sync (D-19/D-20)"
  - "Session-not-found surface for unknown ?a=/?b= ids (D-12)"
  - "Compare nav item in Layout"
affects: [compare-route, session-comparison-ui]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Route as selection source of truth: validateSearch normalizes a/b; picker onChange writes navigate({ search }) (shareable URL)"
    - "Component reuse under a standalone test router via useSearch({ strict: false }) + exported component/validateSearch (avoids file-route __root__ duplication)"
    - "Cross-tree selection match: label-first then coroutineId fallback"

key-files:
  created:
    - frontend/src/routes/compare/index.tsx
    - frontend/src/routes/compare/index.test.tsx
    - frontend/src/components/comparison/SyncedTreePair.tsx
    - frontend/src/components/comparison/SyncedTreePair.test.tsx
  modified:
    - frontend/src/components/comparison/ComparisonView.tsx
    - frontend/src/components/Layout.tsx
    - frontend/src/routeTree.gen.ts

key-decisions:
  - "ComparisonView accepts controlled a/b + onChange but keeps internal-state fallback so existing isolated unit tests still pass"
  - "SyncedTreePair renders its own lightweight clickable tree nodes (not the existing CoroutineTree) because selection rings + per-node delta badges + click need node-level control CoroutineTree does not expose"
  - "Session-not-found detected from the compare query error message (404 / 'not found') rather than a typed error code — api-client throws a plain Error"
  - "Route component uses useSearch({ strict: false }) so the same component mounts under a standalone test router without re-parenting the file route (which duplicates __root__)"

patterns-established:
  - "Testing a TanStack file route: export the component + validateSearch, mount under a fresh createRootRoute/createRoute with createMemoryHistory"
  - "Cross-tree node match key: prefer shared label, fall back to coroutineId"

requirements-completed: [CMPR-02]

# Metrics
duration: ~14min
completed: 2026-06-14
---

# Phase 02 Plan 06: /compare Route + Synced Tree Pair Summary

**Mounted the existing ComparisonView on a shareable `/compare?a=&b=` TanStack route with controlled URL-driven A/B selection, a session-not-found state (D-12), a Compare nav item, and a new `SyncedTreePair` rendering two synchronized coroutine trees with A-only/B-only delta badges and cross-tree selection sync — completing CMPR-02.**

## Performance

- **Duration:** ~14 min
- **Tasks:** 2 (both TDD)
- **Files:** 7 (4 created, 3 modified)

## Accomplishments
- `/compare` route: directory-based file route with `validateSearch` normalizing `a`/`b` (blank/non-string dropped, T-02-10), page layout per UI-SPEC §6 (`container-custom py-8 space-y-6`, h1 "Compare Sessions"), seeds `<ComparisonView>` and writes picker changes back to the URL via `navigate({ search })`.
- `ComparisonView` lifted to controlled `a`/`b` + `onAChange`/`onBChange` props (URL is the source of truth, D-10); internal-state fallback retained for isolated use. Surfaces a "Session not found" `EmptyState` (heading/body/action copy locked in UI-SPEC) when the compare query 404s, in place of results.
- `SyncedTreePair`: `grid grid-cols-2 gap-4` two cards ("Session A/B — {id}"), each rendering its session's tree. A-only nodes → `warning` "A only" chip + `ring-1 ring-warning`; B-only → `secondary` "B only" + `ring-1 ring-secondary`; common nodes neutral. Clicking a node applies `ring-2 ring-primary` to it AND its counterpart (matched by label then coroutineId); no scroll/zoom coupling (D-20). Wired into `ComparisonView` below the diff tables via per-session `useSession` snapshot fetch.
- Compare `NavbarItem` added after Gallery in `Layout`.

## Task Commits

1. **Task 1: /compare route + controlled ComparisonView + Compare nav** — `6963f56` (feat)
2. **Task 2: SyncedTreePair synced trees + delta badges + selection sync** — `03d87b4` (feat)

_TDD note: each task was written test-first (RED confirmed failing against the stub/un-refactored component, then GREEN); committed as a single `feat` per task since the test file and implementation form one unit._

## Decisions Made
- **Controlled-with-fallback ComparisonView:** accepts `a`/`b`/`onChange` but falls back to internal `useState` when uncontrolled, so the three existing `ComparisonView.test.tsx` cases keep passing unchanged.
- **SyncedTreePair renders its own clickable nodes** instead of the existing `CoroutineTree` — selection rings, per-node delta badges, and click handlers require node-level control `CoroutineTree` does not expose. Hierarchy still comes from the shared `buildCoroutineTree`; state colors from `getStateColors`.
- **404 detection by error message** (`404` / `not found`) since `api-client` throws a plain `Error` (no typed status). Adequate for the strict-404 D-12 contract.
- **`useSearch({ strict: false })` in the route component** so the same component mounts under a standalone test router; re-parenting the file route duplicated `__root__` and threw an invariant.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Route component test harness: file route cannot be re-parented**
- **Found during:** Task 1 (route test RED)
- **Issue:** Mounting the imported file `Route` under a fresh `createRootRoute` threw `Invariant failed: Duplicate routes found with id: __root__` — `createFileRoute` already carries a root linkage.
- **Fix:** Exported `ComparePage` + `validateSearch`; the route component now reads search via `useSearch({ strict: false })`; tests build a standalone `createRoute` from those exports. No production behavior change (the real route still uses `Route.useSearch` semantics via strict:false).
- **Files modified:** frontend/src/routes/compare/index.tsx, frontend/src/routes/compare/index.test.tsx
- **Committed in:** 6963f56

**2. [Rule 1 - Bug] HeroUI Select value not rendered in jsdom — seeding assertion adjusted**
- **Found during:** Task 1 (seeding test)
- **Issue:** HeroUI `Select` leaves the `data-slot="value"` span empty in jsdom even with `selectedKeys` set, so asserting visible selected-value text was unreliable.
- **Fix:** Assert seeding via the downstream effect — `useComparison` is called with `('session-a','session-b')` — which proves both pickers are seeded from the URL. The picker-change test drives the real listbox (open trigger → click option), which does work in jsdom.
- **Files modified:** frontend/src/routes/compare/index.test.tsx
- **Committed in:** 6963f56

**3. [Rule 1 - Bug] Split header text in SyncedTreePair test**
- **Found during:** Task 2 (header assertion)
- **Issue:** "Session A — sess-a" renders as separate text nodes (header text + `font-mono` id span), so an exact-string `getByText` failed.
- **Fix:** Query the header text (`/Session A/`) and the id (`sess-a`) separately, scoped to the tree-column testid.
- **Files modified:** frontend/src/components/comparison/SyncedTreePair.test.tsx
- **Committed in:** 03d87b4

---

**Total deviations:** 3 auto-fixed (all Rule 1 — test-harness mechanics around TanStack file routes and HeroUI/jsdom rendering). No scope creep; plan intent fully realized.

## Regeneration Note
`frontend/src/routeTree.gen.ts` was regenerated by the TanStack Router Vite plugin (via `pnpm vite build`) to register `/compare`; the committed file now includes the route so `tsc`/test runs resolve `to: '/compare'` without a dev server. Generated `frontend/dist/` is gitignored and not committed.

## Verification
- `pnpm test compare SyncedTreePair Layout --run` → 7 passed.
- `pnpm test ComparisonView --run` → 6 passed (existing suite unaffected).
- `pnpm tsc --noEmit` → clean.

## Known Stubs
None — `SyncedTreePair`'s Task-1 stub was fully implemented in Task 2.

## Self-Check: PASSED

- All created/modified files exist on disk.
- Both task commits (`6963f56`, `03d87b4`) present in git history.
- Source assertions confirmed: `validateSearch` + `ComparisonView` in routes/compare/index.tsx; `SyncedTreePair` export + `selectedCoroutineId`-style selection state; `/compare` in Layout.tsx.

---
*Phase: 02-user-value-visualization*
*Completed: 2026-06-14*
