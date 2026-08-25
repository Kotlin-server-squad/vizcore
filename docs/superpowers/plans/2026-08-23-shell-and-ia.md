# Shell & Information Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the sessions list the product's front door and collapse the five-destination navbar down to the two places a developer actually goes, without touching the session workspace itself.

**Architecture:** The badged sessions home already exists — `SessionsSidebar` + `deriveSessionKind` shipped in Phase 08.5, parked at `/sessions` and shaped as a 320px sidebar card. This plan rehosts it as the root route in a full-width layout, folds Scenarios and Gallery into a "New demo session" action launched from it, converts Compare from a destination into a selection-driven overlay, and reduces the navbar accordingly. No changes to `SessionDetails.tsx`.

**Tech Stack:** React 19, TanStack Router (file-based routes), HeroUI v2.7, Tailwind v3.4, Vitest 4 + Testing Library.

**Spec:** `docs/superpowers/specs/2026-08-23-spa-workspace-redesign-design.md` — decisions D-1, D-2, D-3.

**Branch:** cut from `feat/spa-design-tokens` (PR #102). This plan assumes the token layer is present.

---

## Context for the implementer

Work in `frontend/`. All paths are relative to it.

**What already exists — do not rebuild it:**

- `src/components/sessions/SessionsSidebar.tsx` — grouped, badged list ("Live apps" / "Demo scenarios"), inline empty state, `+ Connect` button wired to `onConnect`. Constrained by `className="w-[320px] shrink-0"`.
- `src/components/sessions/SessionRow.tsx` — one row with its LIVE/DEMO badge.
- `src/lib/session-kind.ts` — `deriveSessionKind(session): 'live' | 'demo'`, keyed off a `scenario-` id prefix. There is **no backend field** for this.
- `src/components/connect/ConnectWizard.tsx` — the 3-step connect flow.
- `src/routes/index.tsx` — currently the marketing home, and also hosts the `?correlation=` IDE deep-link (`CorrelationDeepLink`). **That deep-link must keep working**; it is how the IntelliJ plugin lands users on a live session.

**Routes being retired as destinations:** `/scenarios`, `/scenarios/builder`, `/gallery`, `/compare`. They keep working as URLs (redirects) so existing links and the plugin's assumptions do not break — only the navbar entries and the mental model change.

**The fidelity ladder is only two-valued today.** The spec describes three rungs (demo / attached / instrumented), but distinguishing *attached* from *instrumented* requires knowing which event families a session actually emits — that is sub-project 4 work. This plan keeps the shipped LIVE/DEMO badge and does not fake the third rung.

---

### Task 1: Sessions becomes the root route

**Files:**
- Modify: `src/routes/index.tsx`
- Test: `src/routes/index.test.tsx` (exists — extend it)

- [ ] **Step 1: Write the failing test**

Add to `src/routes/index.test.tsx`:

```tsx
it('renders the sessions home at the root, not a marketing hero', async () => {
  renderHome()
  expect(await screen.findByRole('heading', { name: /sessions/i })).toBeInTheDocument()
  expect(screen.queryByText(/Real-time visualization of Kotlin coroutine/i)).not.toBeInTheDocument()
})
```

Match the existing file's render helper and mocking style rather than inventing a new one — read it first.

- [ ] **Step 2: Run the test to verify it fails**

Run: `pnpm vitest run src/routes/index.test.tsx`
Expected: FAIL — the hero copy is still present.

- [ ] **Step 3: Replace the `Home` component**

In `src/routes/index.tsx`, leave `validateSearch`, `HomePage` and `CorrelationDeepLink` **exactly as they are** — the `?correlation=` branch must be untouched. Replace only the `Home` function body so it renders the sessions home:

```tsx
function Home() {
  const [wizardOpen, setWizardOpen] = useState(false)

  return (
    <Layout>
      <div className="container-custom py-8">
        <SessionsHome onConnect={() => setWizardOpen(true)} />
      </div>
      <ConnectWizard isOpen={wizardOpen} onClose={() => setWizardOpen(false)} />
    </Layout>
  )
}
```

`SessionsHome` is built in Task 2. Until then, import `SessionsSidebar` directly so this task stays green on its own.

Remove the now-unused `Card`, `CardBody`, `CardHeader`, `FiPlay`, `FiActivity` imports and the `useSessions` call.

- [ ] **Step 4: Run the test to verify it passes**

Run: `pnpm vitest run src/routes/index.test.tsx`
Expected: PASS.

- [ ] **Step 5: Verify the deep-link branch still works**

Run: `pnpm vitest run src/routes/index.test.tsx -t correlation`
Expected: PASS. If there is no correlation test, add one asserting that `?correlation=tok` renders the "Connecting to your app…" state rather than the sessions list.

- [ ] **Step 6: Commit**

```bash
git add src/routes/index.tsx src/routes/index.test.tsx
git commit -m "feat(ia): sessions list becomes the root route"
```

---

### Task 2: A home layout, not a 320px sidebar

`SessionsSidebar` is hard-constrained to `w-[320px] shrink-0`. Dropped into a `max-w-7xl` container it renders as a narrow column against dead space. Rather than adding a `variant` prop that forks the component's layout logic, lift the width decision to the caller.

**Files:**
- Modify: `src/components/sessions/SessionsSidebar.tsx`
- Create: `src/components/sessions/SessionsHome.tsx`
- Test: `src/components/sessions/SessionsHome.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { SessionsHome } from './SessionsHome'

describe('SessionsHome', () => {
  it('does not constrain itself to the sidebar width', () => {
    const { container } = render(<SessionsHome onConnect={() => {}} />)
    expect(container.querySelector('.w-\\[320px\\]')).toBeNull()
  })
})
```

Wrap in whatever providers the sibling `SessionsSidebar.test.tsx` uses — read it first and copy that harness.

- [ ] **Step 2: Run the test to verify it fails**

Run: `pnpm vitest run src/components/sessions/SessionsHome.test.tsx`
Expected: FAIL — module does not exist.

- [ ] **Step 3: Make the width a prop on `SessionsSidebar`**

Change its root element so the width class comes from the caller, defaulting to today's behavior so the existing `/sessions` usage is unchanged:

```tsx
export function SessionsSidebar({
  onConnect,
  selectedSessionId,
  className = 'w-[320px] shrink-0',
}: {
  onConnect: () => void
  selectedSessionId?: string
  className?: string
}) {
  // ...
  return (
    <Card className={className}>
```

- [ ] **Step 4: Add `SessionsHome`**

```tsx
import { SessionsSidebar } from './SessionsSidebar'

/**
 * The root-route presentation of the sessions list. Same component and same
 * behavior as the sidebar — only the width constraint differs, so the list is
 * not forked into two implementations that can drift.
 */
export function SessionsHome({ onConnect }: { onConnect: () => void }) {
  return <SessionsSidebar onConnect={onConnect} className="w-full" />
}
```

- [ ] **Step 5: Point the root route at it**

In `src/routes/index.tsx`, swap the direct `SessionsSidebar` import for `SessionsHome`.

- [ ] **Step 6: Run the tests**

Run: `pnpm vitest run src/components/sessions src/routes/index.test.tsx`
Expected: PASS, including the pre-existing `SessionsSidebar` tests.

- [ ] **Step 7: Commit**

```bash
git add src/components/sessions/ src/routes/index.tsx
git commit -m "feat(ia): host the sessions list full-width on the root route"
```

---

### Task 3: Collapse the navbar

Home / Sessions / Scenarios / Gallery / Compare becomes a single brand link plus the one destination that survives. Root *is* sessions, so a "Sessions" entry alongside "Home" would be two names for one page.

**Files:**
- Modify: `src/components/Layout.tsx`
- Test: `src/components/Layout.test.tsx` (create)

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { Layout } from './Layout'

describe('Layout nav', () => {
  it('no longer advertises retired destinations', () => {
    renderLayout()
    for (const gone of ['Scenarios', 'Gallery', 'Compare', 'Home']) {
      expect(screen.queryByRole('link', { name: gone })).toBeNull()
    }
  })

  it('keeps the brand link to the sessions home', () => {
    renderLayout()
    expect(screen.getByRole('link', { name: /coroutine visualizer/i })).toHaveAttribute('href', '/')
  })
})
```

`Layout` renders TanStack `Link`s, so the test needs a router harness — copy the pattern from `src/routes/index.test.tsx`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `pnpm vitest run src/components/Layout.test.tsx`
Expected: FAIL — all five nav items are present.

- [ ] **Step 3: Reduce the navbar**

In `src/components/Layout.tsx`, delete the `NavbarContent` block containing the five `NavbarItem`s. Keep `NavbarBrand` (the link to `/`). The navbar becomes brand-only; per-page actions live on their own pages.

- [ ] **Step 4: Run the test to verify it passes**

Run: `pnpm vitest run src/components/Layout.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/components/Layout.tsx src/components/Layout.test.tsx
git commit -m "feat(ia): collapse the navbar to brand-only"
```

---

### Task 4: Retired routes redirect instead of 404

The routes stay reachable — shared links, bookmarks and any plugin assumptions must not break — but they stop being places you navigate *to*.

**Files:**
- Modify: `src/routes/scenarios/index.tsx`, `src/routes/gallery/index.tsx`, `src/routes/compare/index.tsx`
- Test: `src/routes/retired-routes.test.tsx` (create)

- [ ] **Step 1: Write the failing test**

Assert each retired route's `beforeLoad` throws a redirect to `/`. Read TanStack Router's `redirect` helper usage in the existing codebase first (`src/lib/navigation.ts` and the 401 path in `api-client.ts` show the project's conventions).

- [ ] **Step 2: Run it to verify it fails**

Run: `pnpm vitest run src/routes/retired-routes.test.tsx`

- [ ] **Step 3: Add the redirects**

For each of the three routes, add to the `createFileRoute` options:

```ts
beforeLoad: () => {
  throw redirect({ to: '/' })
},
```

Leave `/scenarios/builder` alone for now — it is the 410-line `ScenarioBuilder`, and whether it survives is an open question in the spec. Retiring it is not this plan's call.

- [ ] **Step 4: Run the test to verify it passes**

- [ ] **Step 5: Check for orphaned tests**

Run: `pnpm vitest run src/routes/`
`routes/gallery/index.test.ts` and `routes/compare/index.test.tsx` test components that no longer render at those paths. If they fail, do **not** delete them — the components survive and are re-hosted in Task 5. Retarget them at the components directly.

- [ ] **Step 6: Commit**

```bash
git add src/routes/
git commit -m "feat(ia): retire scenarios, gallery and compare as destinations"
```

---

### Task 5: Re-host what those routes did

Two affordances must survive their routes: creating a demo session, and comparing two sessions.

**Files:**
- Create: `src/components/sessions/NewDemoSessionModal.tsx`
- Create: `src/components/sessions/CompareOverlay.tsx`
- Modify: `src/components/sessions/SessionsSidebar.tsx`
- Test: colocated for both new components

- [ ] **Step 1: Write the failing tests**

For `NewDemoSessionModal`: opening it lists the available scenarios and starting one navigates to the created session.
For `CompareOverlay`: given two selected session ids it renders `ComparisonView`; given fewer it renders nothing.

- [ ] **Step 2: Run them to verify they fail**

- [ ] **Step 3: Build `NewDemoSessionModal`**

Reuse the scenario-listing logic from `src/routes/gallery/index.tsx` and the run logic from `useRunScenario`. This is a re-host: lift the existing code, do not rewrite the scenario catalog.

- [ ] **Step 4: Build `CompareOverlay`**

Wrap the existing `ComparisonView` (457 lines) in a HeroUI `Modal`. Do not modify `ComparisonView` itself — it is re-hosted, not rewritten.

- [ ] **Step 5: Wire both into the sessions list**

Add a secondary `New demo session` action beside `+ Connect`, and multi-select on rows that enables a `Compare` action once exactly two are selected.

- [ ] **Step 6: Run the tests**

Run: `pnpm vitest run src/components/sessions`

- [ ] **Step 7: Commit**

```bash
git add src/components/sessions/
git commit -m "feat(ia): re-host demo-session creation and compare into the sessions home"
```

---

### Task 6: Verify the whole app

- [ ] **Step 1:** `pnpm test` — all pass. Investigate any failure; do not accept it as pre-existing without checking `git stash` + re-run.
- [ ] **Step 2:** `pnpm lint` — 0 errors.
- [ ] **Step 3:** `pnpm build` — clean.
- [ ] **Step 4: Look at it.** Run `pnpm dev` (use an alt port; 3000 is usually taken on this machine) and confirm, reporting what you see:
  - `/` shows the badged sessions list full-width, not a hero and not a narrow column
  - the navbar is brand-only
  - `/scenarios`, `/gallery`, `/compare` land on `/`
  - `+ Connect` opens the wizard; `New demo session` opens the scenario picker
  - selecting two sessions enables Compare and the overlay renders
  - `/?correlation=anything` still shows "Connecting to your app…" — **this is the regression most likely to slip through**
- [ ] **Step 5:** Commit any fixes.

---

## Definition of done

- `/` is the sessions home; the marketing hero is gone.
- The navbar advertises no retired destination.
- Nothing that worked before is unreachable: demo creation, compare and the `?correlation=` deep-link all still function.
- `SessionDetails.tsx` is untouched by this plan.

## What this deliberately does not do

- No workspace changes — no state bar, no inspector, no tab migration. That is the next plan.
- No third fidelity rung. `deriveSessionKind` stays two-valued until sub-project 4 provides the data.
- No decision on `/scenarios/builder`.
