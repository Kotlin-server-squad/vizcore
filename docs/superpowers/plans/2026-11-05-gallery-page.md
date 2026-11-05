# Gallery Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring back `/gallery` as a page that lists every demo by topic, with a Common-mistakes tab, reachable from new Sessions and Gallery links in the top bar.

**Architecture:**
- **Demo list:** the list comes from `GET /api/scenarios` (`useScenarios`). It is grouped by the same `groupDemos` the "New demo session" dialog uses, which this plan moves to `src/lib/demo-groups.ts`.
- **Starting a demo:** the dialog's create-and-navigate code becomes a hook, `useStartDemo`, shared by the dialog and the Gallery.
- **Teaching content:** it lives in `src/content/gallery/`:
  - `mistakes.ts` holds the 10 common mistakes;
  - `guides.ts` exports an empty `GUIDES` record, the seam that PR 3 fills.
- **The page:** `/gallery` keeps the selected tab in `?group=`.

**Tech Stack:** React 19, TypeScript, TanStack Router and Query, HeroUI 2, Vitest with Testing Library.

**Spec:** `docs/superpowers/specs/2026-11-05-gallery-how-it-works-design.md`, PR 2 under "Delivery", "Pages › /gallery" and "Content".

**Working directory:** worktree `/Users/jirihermann/Documents/workspace-vizcore/vizcore-gallery` on branch `feat/gallery-page` (from `origin/main` at 7eb22c3). Run frontend commands from `frontend/`.

---

## File map

| File | Change | Responsibility |
|---|---|---|
| `frontend/src/lib/demo-groups.ts` (+ test) | create | `groupDemos` and its labels, moved from the dialog |
| `frontend/src/hooks/use-start-demo.ts` (+ test) | create | create a `scenario-` session, open it, keep the per-demo pending and error state |
| `frontend/src/components/demos/DemoStartAlert.tsx` | create | the inline "Could not start …" alert shared by the dialog and the Gallery |
| `frontend/src/components/sessions/NewDemoSessionModal.tsx` | modify | use the three above; behaviour unchanged |
| `frontend/src/content/gallery/types.ts` | create | `DemoGuide`, `MistakeId`, `Mistake` |
| `frontend/src/content/gallery/guides.ts` | create | `GUIDES`, empty until PR 3 |
| `frontend/src/content/gallery/mistakes.ts` (+ test) | create | the 10 mistakes |
| `frontend/src/components/gallery/DemoCard.tsx` | create | one demo card |
| `frontend/src/components/gallery/MistakeCard.tsx` | create | one mistake card |
| `frontend/src/components/gallery/GalleryPage.tsx` (+ test) | create | tabs, cards, the loading, error and Run-error states |
| `frontend/src/routes/gallery/index.tsx` | rewrite | the route: `validateSearch` and the page |
| `frontend/src/routes/gallery/index.test.ts` | rewrite | tests for `validateSearch`, replacing the old `GALLERY_SCENARIOS` tests |
| `frontend/src/components/Layout.tsx` (+ test) | modify | Sessions and Gallery links, the G-1 comment |
| `frontend/src/routes/retired-routes.test.tsx` | modify | the comment no longer calls the Gallery retired |
| `CHANGELOG.md` | modify | an Unreleased entry |

---

### Task 1: Move `groupDemos` to `src/lib/demo-groups.ts`

- [ ] **Step 1: Write the failing test,** `src/lib/demo-groups.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { groupDemos } from './demo-groups'
import catalog from '@/test/demo-catalog.json'
import type { Scenario } from '@/types/api'

describe('groupDemos', () => {
  it('groups the full catalog in server order, labelled with groupLabel', () => {
    const groups = groupDemos(catalog.scenarios as Scenario[])
    expect(groups.map((g) => g.label)).toEqual([
      'Real life', 'Basics', 'Channels', 'Flow', 'Patterns', 'Sync: Mutex', 'Sync: Semaphore', 'Combined',
    ])
    expect(groups.reduce((n, g) => n + g.demos.length, 0)).toBe(32)
  })

  it('falls back to category labels, real life first, for a server without groups', () => {
    const groups = groupDemos([
      { id: 'nested', name: 'Nested', category: 'basic' } as Scenario,
      { id: 'order', name: 'Order', category: 'realistic' } as Scenario,
    ])
    expect(groups.map((g) => g.label)).toEqual(['Real life', 'Basics'])
  })
})
```

- [ ] **Step 2: Run it and see it fail.** Run `pnpm vitest run src/lib/demo-groups.test.ts`. It should FAIL to resolve `./demo-groups`.
- [ ] **Step 3: Move the code.** Move `CATEGORY_LABELS`, `DemoGroup` (now exported) and `groupDemos` verbatim from `NewDemoSessionModal.tsx` into `src/lib/demo-groups.ts`, and have the dialog import `groupDemos` from there.
- [ ] **Step 4: Run the tests and see them pass.** Run `pnpm vitest run src/lib/demo-groups.test.ts src/components/sessions/`. Both should PASS.
- [ ] **Step 5: Commit.** Message: `refactor(frontend): demo grouping lives in lib/demo-groups`.

### Task 2: `useStartDemo` and `DemoStartAlert`

- [ ] **Step 1: Write the failing test,** `src/hooks/use-start-demo.test.ts`. Mock `useNavigate` and `useCreateSession` the way `NewDemoSessionModal.test.tsx` does, then check:
  - `start(scenario)` calls `mutateAsync('scenario-<name>')`;
  - it then calls `navigate({ to: '/sessions/$sessionId', params: { sessionId }, search: { scenarioId, scenarioName } })`;
  - it calls `onStarted` before navigating;
  - `preparing` is the scenario id while the call is pending, and `null` after;
  - a rejected create sets `startError = { scenario: name, error }` and never rejects;
  - a later `start` clears the previous error.
- [ ] **Step 2: Run it and see it fail.** Run `pnpm vitest run src/hooks/use-start-demo.test.ts`. It should FAIL to resolve the module.
- [ ] **Step 3: Implement the hook:**

```ts
import { useCallback, useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useCreateSession } from '@/hooks/use-sessions'
import type { Scenario } from '@/types/api'

export interface DemoStartError {
  scenario: string
  error: unknown
}

/**
 * Starts a demo: mint a `scenario-`-prefixed session (what `deriveSessionKind`
 * keys the DEMO badge off), then open it. Shared by the "New demo session"
 * dialog and the Gallery. Never rejects: a failed create is kept in
 * `startError` for the caller to show (#139).
 */
export function useStartDemo(onStarted?: () => void) {
  const createSession = useCreateSession()
  const navigate = useNavigate()
  const [preparing, setPreparing] = useState<string | null>(null)
  const [startError, setStartError] = useState<DemoStartError | null>(null)

  const start = useCallback(
    async (scenario: Scenario) => {
      setPreparing(scenario.id)
      setStartError(null)
      try {
        const result = await createSession.mutateAsync(`scenario-${scenario.name}`)
        onStarted?.()
        navigate({
          to: '/sessions/$sessionId',
          params: { sessionId: result.sessionId },
          search: { scenarioId: scenario.id, scenarioName: scenario.name },
        })
      } catch (error) {
        setStartError({ scenario: scenario.name, error })
      } finally {
        setPreparing(null)
      }
    },
    [createSession, navigate, onStarted],
  )

  return { start, preparing, startError }
}
```

  Then `DemoStartAlert.tsx`, which is the dialog's existing alert markup, unchanged:

```tsx
import { describeApiError } from '@/lib/api-errors'
import type { DemoStartError } from '@/hooks/use-start-demo'

export function DemoStartAlert({ startError }: { startError: DemoStartError | null }) {
  if (!startError) return null
  return (
    <div role="alert" className="rounded-medium border border-danger-200 bg-danger-50 p-3 text-sm text-danger">
      Could not start “{startError.scenario}”:{' '}
      {describeApiError(startError.error, 'The scenario').message}
    </div>
  )
}
```

- [ ] **Step 4: Use them in the dialog.** Replace the dialog's own `start`, `preparing`, `startError` and alert with `useStartDemo(onClose)` and `<DemoStartAlert …/>`.
- [ ] **Step 5: Run the tests and see them pass.** Run `pnpm vitest run src/hooks/use-start-demo.test.ts src/components/sessions/`. They should all PASS, including the dialog's existing error tests (the 503 "Server busy" alert).
- [ ] **Step 6: Commit.** Message: `refactor(frontend): useStartDemo shared by the demo dialog`.

### Task 3: Content types, `GUIDES` and the 10 mistakes

- [ ] **Step 1: Write the failing test,** `src/content/gallery/mistakes.test.ts`:

```ts
import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'
import { MISTAKES } from './mistakes'
import { GUIDES } from './guides'
import type { MistakeId } from './types'

// Every AntiPatternType value, spelled out so a new one fails this test until it has an entry.
const ALL: MistakeId[] = [
  'GLOBAL_SCOPE_USAGE', 'BLOCKING_ON_MAIN', 'LEAKED_COROUTINE', 'UNNECESSARY_ASYNC_AWAIT',
  'RUN_BLOCKING_MISUSE', 'COROUTINE_EXPLOSION', 'UNHANDLED_EXCEPTION',
  'MISSING_CANCELLATION_CHECK', 'SHARED_MUTABLE_STATE', 'WRONG_DISPATCHER',
]

const here = dirname(fileURLToPath(import.meta.url))
const detector = readFileSync(
  resolve(here, '../../../../backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/checksystem/AntiPatternDetector.kt'),
  'utf8',
)

describe('common mistakes', () => {
  it('has exactly one entry per anti-pattern kind', () => {
    expect(MISTAKES.map((m) => m.id).sort()).toEqual([...ALL].sort())
  })

  it('marks as detected exactly the kinds the detector reports today', () => {
    const reported = [...new Set([...detector.matchAll(/AntiPatternType\.([A-Z_]+)/g)].map((m) => m[1]))].sort()
    expect(MISTAKES.filter((m) => m.detected).map((m) => m.id).sort()).toEqual(reported)
  })

  it('gives every mistake a title, what goes wrong and the fix', () => {
    for (const m of MISTAKES) {
      expect(m.title.length, m.id).toBeGreaterThan(0)
      expect(m.whatGoesWrong.length, m.id).toBeGreaterThan(20)
      expect(m.fix.length, m.id).toBeGreaterThan(20)
    }
  })

  it('has no guides yet, and every guide mistake would exist', () => {
    for (const guide of Object.values(GUIDES)) {
      if (guide.mistakeId) expect(ALL).toContain(guide.mistakeId)
    }
  })
})
```

- [ ] **Step 2: Run it and see it fail.** Run `pnpm vitest run src/content/gallery/`. It should FAIL to resolve the modules.
- [ ] **Step 3: Write the content.** In `types.ts`:
  - `DemoGuide`, exactly as in the spec (without `willSee`, which is JSON in PR 3);
  - `export type MistakeId = AntiPatternType`;
  - `Mistake { id; title; whatGoesWrong; fix; detected: boolean }`.

  `detected` is an addition to the spec. Only 5 of the 10 kinds are reported by `AntiPatternDetector.detectAll()` today, so the card must not claim the visualizer flags the other 5.

  In `guides.ts`: `export const GUIDES: Record<string, DemoGuide> = {}`.

  In `mistakes.ts`: 10 entries in plain, short sentences. Base the detected 5 on the detector's own description and suggestion. Those five are:
  - `GLOBAL_SCOPE_USAGE`
  - `BLOCKING_ON_MAIN` (50 ms)
  - `LEAKED_COROUTINE`
  - `UNNECESSARY_ASYNC_AWAIT`
  - `COROUTINE_EXPLOSION` (100 per second)

- [ ] **Step 4: Run the tests and see them pass.** Run `pnpm vitest run src/content/gallery/`.
- [ ] **Step 5: Commit.** Message: `feat(frontend): the 10 common coroutine mistakes, as Gallery content`.

### Task 4: The route's search params

- [ ] **Step 1: Write the failing test.** Rewrite `src/routes/gallery/index.test.ts` to test the exported `validateSearch`:
  - `{ group: 'basics' }` stays `{ group: 'basics' }`;
  - `{ group: 'mistakes' }` is kept;
  - a blank value, a non-string value or no value gives `{ group: undefined }`.

  Delete the old `GALLERY_SCENARIOS` tests; the list they guarded is gone.
- [ ] **Step 2: Run it and see it fail.** Run `pnpm vitest run src/routes/gallery/`. It should FAIL, because there is no `validateSearch` export.
- [ ] **Step 3: Rewrite `src/routes/gallery/index.tsx`.** It should contain only:
  - `GallerySearch` and `validateSearch`;
  - `export const Route = createFileRoute('/gallery/')({ validateSearch, component: GalleryRoute })`;
  - `GalleryRoute`, which reads `Route.useSearch()` and renders `<Layout><GalleryPage group={…} onGroupChange={(g) => navigate({ to: '/gallery', search: { group: g } })} /></Layout>`.

  The redirect and the old 28 cards go away. Until Task 5, `GalleryPage` can be a stub that renders a heading.
- [ ] **Step 4: Run the tests and the type check.** Run `pnpm vitest run src/routes/gallery/ && pnpm exec tsc --noEmit`. Both should PASS.
- [ ] **Step 5: Commit.** Message: `feat(frontend): /gallery is a page again, with its tab in ?group=`.

### Task 5: `GalleryPage`, `DemoCard` and `MistakeCard`

- [ ] **Step 1: Write the failing test,** `src/components/gallery/GalleryPage.test.tsx`. Mock `useScenarios` with the catalog fixture, and `useStartDemo` with a spy. Check that:
  - the page has the heading "Example Gallery";
  - the tabs are, in order, the 8 `groupLabel`s and then "Common mistakes", each with its count;
  - with no `group`, the first group (Real life) is shown;
  - `group="basics"` shows exactly the 7 Basics cards;
  - an unknown group falls back to the first group;
  - choosing a tab calls `onGroupChange` with that group's key, or `'mistakes'`;
  - every catalog demo has a card in its own tab, so all 32 are reachable;
  - Run on a card calls `start` with that scenario, and its accessible name is `Run <name> (<groupLabel>)`;
  - a `startError` from the hook renders the shared alert;
  - while loading, skeletons are shown and there are no cards;
  - an error with no data shows `ErrorState` with Retry, which calls `refetch`;
  - `group="mistakes"` shows 10 mistake cards. Each has `id=<MistakeId>`, a disabled "Demo coming" button, and a "Detected by the visualizer" chip only where `detected` is true;
  - with `group="mistakes"` and `location.hash = '#LEAKED_COROUTINE'`, that card is scrolled into view (spy on `Element.prototype.scrollIntoView`);
  - no card shows "How it works" or a level chip, because `GUIDES` is empty.
- [ ] **Step 2: Run it and see it fail.** Run `pnpm vitest run src/components/gallery/`.
- [ ] **Step 3: Implement it.**
  - **Header and tabs:** the title "Example Gallery" and one sentence. HeroUI `Tabs` (`variant="bordered"`, `color="primary"`) with `selectedKey` = the valid `group`, else the first group's key. Each tab title has its count. The Common-mistakes tab has an amber `FiAlertTriangle` icon (`text-warning`) with the label in normal text colour.
  - **`DemoCard`:**
    - name, `description` and duration (`FiClock`);
    - a primary flat Run button with `isLoading` while `preparing === id`;
    - when a guide is passed: the level chip and tags. The How-it-works link waits for PR 3, which adds its route.
  - **`MistakeCard`:**
    - `id={mistake.id}`, a `border-warning-300` border and an amber icon;
    - the title, the id in mono, and the "Detected by the visualizer" chip when `detected`;
    - "What goes wrong" and "Do instead" paragraphs in normal text colours;
    - a disabled button "Demo coming".
  - **Loading:** 6 HeroUI `Skeleton` cards.
  - **Error:** `ErrorState` with `subject="The demo list"` and `onRetry={refetch}`.
  - **Run error:** `<DemoStartAlert>` above the grid.
  - **Anchors:** a `useEffect` on `[group, isLoading]` scrolls `location.hash`'s element into view on the mistakes tab.
  - **Grid:** `grid gap-4 sm:grid-cols-2 lg:grid-cols-3`.
- [ ] **Step 4: Run the tests and see them pass.** Run `pnpm vitest run src/components/gallery/ src/routes/gallery/`.
- [ ] **Step 5: Commit.** Message: `feat(frontend): Gallery page with topic tabs and common mistakes`.

### Task 6: The top bar

- [ ] **Step 1: Write the failing test.** Update `Layout.test.tsx`:
  - Replace the "no longer advertises retired destinations" test with two tests. One checks for links named "Sessions" (`href="/"`) and "Gallery" (`href="/gallery"`). The other checks that Scenarios, Compare and Home are still absent.
  - Keep the brand and theme-switch tests.
- [ ] **Step 2: Run it and see it fail.** Run `pnpm vitest run src/components/Layout.test.tsx`.
- [ ] **Step 3: Implement it.** Add `<NavbarContent className="gap-4" justify="start">` with two `NavbarItem`s, each holding a `Link` (`to="/"` Sessions, `to="/gallery"` Gallery). Use `activeProps={{ className: 'font-semibold text-foreground' }}`, `inactiveProps={{ className: 'text-default-500' }}` and `activeOptions={{ exact: true }}` for Sessions. Rewrite the doc comment:

```ts
/**
 * The app shell.
 *
 * The navbar carries the two places: Sessions (the sessions home) and the
 * Gallery (G-1 of the 2026-11-05 Gallery spec, superseding D-2 for the Gallery
 * only). Scenarios and Compare stay actions on the pages that own them
 * (D-2/D-3); the theme switch sits on the right.
 */
```

  Then fix the `retired-routes.test.tsx` comment: "Scenarios is retired outright…". The Gallery is a page again.
- [ ] **Step 4: Run the tests and see them pass.** Run `pnpm vitest run src/components/Layout.test.tsx src/routes/`.
- [ ] **Step 5: Commit.** Message: `feat(frontend): Sessions and Gallery in the top bar`.

### Task 7: Gates, browser check, changelog, PR

- [ ] **Step 1: Run the gates.** Run `pnpm vitest run && pnpm exec tsc --noEmit && pnpm lint && pnpm build`. Lint should show 0 errors.
- [ ] **Step 2: Check it in the browser,** with the backend on 18291 and the frontend on 13291 via `VITE_PROXY_TARGET`, in both themes:
  - every tab;
  - Run from a card opens the session and the demo starts;
  - `/gallery?group=mistakes#WRONG_DISPATCHER` scrolls to that card;
  - the top-bar links;
  - the "New demo session" dialog still starts a demo.

  Take screenshots of the Gallery in dark and in light, and of the mistakes tab.
- [ ] **Step 3: Add a CHANGELOG entry** under Unreleased → Added.
- [ ] **Step 4: Push and open the PR.** Then run Codex review rounds, as in earlier PRs.
