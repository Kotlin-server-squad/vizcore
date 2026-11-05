# Gallery "How it works" Pages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give each of the 7 Basics demos a "How it works" page at `/gallery/$slug` that explains its story, code, steps, what the tree will show, its key idea and a related common mistake. Link the pages from the Gallery cards.

**Architecture:**
- **Content:** `src/content/gallery/`. The guides live in `basics.ts` and the "what you will see" pictures in `will-see.json`. The JSON is shared with a backend test, which runs each demo and checks that the pictured coroutines really exist at the pictured depth.
- **Page:** a new file route renders `HowItWorksPage`. It finds the demo by slug in the server catalog (`useScenarios`) and starts it through the existing `useStartDemo`.

**Tech Stack:**
- Frontend: React 19, TanStack Router (file routes, generated `routeTree.gen.ts`), HeroUI 2, Vitest with Testing Library.
- Backend test: Kotlin with JUnit 5.

**Spec:** `docs/superpowers/specs/2026-11-05-gallery-how-it-works-design.md`, PR 3 under "Delivery", and the sections "/gallery/$slug: How it works" and "Content".

**Working directory:** worktree `/Users/jirihermann/Documents/workspace-vizcore/vizcore-gallery`, branch `feat/gallery-how-it-works`. Run frontend commands from `frontend/` and backend commands from `backend/` with `JAVA_HOME=/Users/jirihermann/Library/Java/JavaVirtualMachines/azul-21/Contents/Home`.

**Gates:**
- frontend: `pnpm vitest run`, `pnpm exec tsc --noEmit`, `pnpm lint`, `pnpm build`;
- backend: `./gradlew ktlintCheck detekt test`. Restore `backend/docs/index.html` if a test run rewrites it.

**Deviation from the spec:**
- `will-see.json` holds `{ "<demoId>": { "moment": "...", "coroutines": [...] } }` instead of a bare array per demo. The caption naming the moment (spec section 4) lives next to the picture it describes.

---

## File map

| File | Change | Responsibility |
|---|---|---|
| `frontend/src/lib/demo-slug.ts` (+ test) | create | `demoSlug(id)`: `/` → `-`; test: every catalog id gets a distinct slug |
| `frontend/src/content/gallery/will-see.json` | create | per demo: the moment and the coroutines (label, state, depth) |
| `frontend/src/content/gallery/will-see.ts` | create | typed access: `willSeeFor(demoId)` |
| `frontend/src/content/gallery/basics.ts` | create | the 7 Basics guides |
| `frontend/src/content/gallery/guides.ts` | modify | `GUIDES` built from the Basics guides |
| `frontend/src/content/gallery/guides.test.ts` | create | content guards |
| `frontend/src/components/gallery/HowItWorksPage.tsx` (+ test) | create | the page and its fallback |
| `frontend/src/components/gallery/WillSeeTree.tsx` | create | the static "what you will see" tree |
| `frontend/src/routes/gallery/$slug.tsx` | create | the route |
| `frontend/src/routeTree.gen.ts` | regenerate | by the router plugin (`pnpm build`) |
| `frontend/src/components/gallery/DemoCard.tsx` | modify | "How it works" link when a guide exists |
| `frontend/src/components/gallery/GalleryPage.test.tsx` | modify | mock `Link`; the guide test now expects the link on Basics |
| `backend/src/test/kotlin/com/jh/proj/coroutineviz/scenarios/GuideLabelsTest.kt` | create | runs each demo in `will-see.json`, checks label and depth |
| `backend/build.gradle.kts` | modify | `will-see.json` as a declared test input |
| `.github/workflows/ci-backend.yml` | modify | CI Backend also triggers on `will-see.json` |
| `CHANGELOG.md` | modify | Unreleased entry |

---

### Task 1: Slugs

**Files:** create `frontend/src/lib/demo-slug.ts` and `frontend/src/lib/demo-slug.test.ts`.

- [ ] **Step 1: Write the failing test**

```ts
import { describe, it, expect } from 'vitest'
import { demoSlug } from './demo-slug'
import catalog from '@/test/demo-catalog.json'

describe('demoSlug', () => {
  it('replaces every / with -', () => {
    expect(demoSlug('nested')).toBe('nested')
    expect(demoSlug('flow/simple')).toBe('flow-simple')
    expect(demoSlug('sync/semaphore/producer-consumer')).toBe('sync-semaphore-producer-consumer')
  })

  it('gives every catalog demo its own slug', () => {
    const slugs = catalog.scenarios.map((s) => demoSlug(s.id))
    expect(new Set(slugs).size).toBe(slugs.length)
  })
})
```

- [ ] **Step 2: Run the test; it should fail** (the module is missing). Run: `pnpm vitest run src/lib/demo-slug.test.ts`
- [ ] **Step 3: Implement**

```ts
/** The URL slug of a demo: its catalog id with every `/` replaced by `-` (`flow/simple` → `flow-simple`). */
export function demoSlug(id: string): string {
  return id.replaceAll('/', '-')
}
```

- [ ] **Step 4: Run the test; it should pass.** Then commit: `feat(frontend): demo slugs for How-it-works pages`.

---

### Task 2: The content and its guards

**Files:**
- create `will-see.json`, `will-see.ts`, `basics.ts` and `guides.test.ts`;
- modify `guides.ts`.

All under `frontend/src/content/gallery/`.

- [ ] **Step 1: Write the failing guards** (`guides.test.ts`):

```ts
import { describe, it, expect } from 'vitest'
import { GUIDES } from './guides'
import { MISTAKES } from './mistakes'
import { WILL_SEE, willSeeFor } from './will-see'
import { CoroutineState } from '@/types/api'
import catalog from '@/test/demo-catalog.json'

const ids = new Set(catalog.scenarios.map((s) => s.id))
const basics = catalog.scenarios.filter((s) => s.group === 'basics').map((s) => s.id)
const markers = (code: string) => [...code.matchAll(/\/\/\s*(\d+)\b/g)].map((m) => Number(m[1]))

describe('gallery guides', () => {
  it('has a guide for every Basics demo', () => {
    expect(basics).toHaveLength(7)
    for (const id of basics) expect(GUIDES[id], id).toBeDefined()
  })

  it('keys every guide by its own demo id, which the catalog serves', () => {
    for (const [key, guide] of Object.entries(GUIDES)) {
      expect(guide.demoId).toBe(key)
      expect(ids.has(guide.demoId), guide.demoId).toBe(true)
    }
  })

  it('numbers the code exactly 1…n for its n steps', () => {
    for (const guide of Object.values(GUIDES)) {
      expect(guide.steps.length, guide.demoId).toBeGreaterThan(0)
      const want = guide.steps.map((_, i) => i + 1)
      expect([...new Set(markers(guide.code))].sort((a, b) => a - b), guide.demoId).toEqual(want)
    }
  })

  it('names only common mistakes that exist', () => {
    const known = new Set(MISTAKES.map((m) => m.id))
    for (const guide of Object.values(GUIDES)) {
      if (guide.mistakeId) expect(known.has(guide.mistakeId), guide.demoId).toBe(true)
    }
  })

  it('pictures every guide in will-see.json, with known states and a tree shape', () => {
    for (const id of Object.keys(GUIDES)) {
      const picture = willSeeFor(id)
      expect(picture, id).toBeDefined()
      expect(picture!.moment.length, id).toBeGreaterThan(0)
      expect(picture!.coroutines.length, id).toBeGreaterThan(0)
      expect(picture!.coroutines[0]!.depth, id).toBe(0)
      picture!.coroutines.forEach((c, i) => {
        expect(Object.values(CoroutineState), `${id} ${c.label}`).toContain(c.state)
        // A row is at most one level deeper than the row above it.
        if (i > 0) expect(c.depth, `${id} ${c.label}`).toBeLessThanOrEqual(picture!.coroutines[i - 1]!.depth + 1)
      })
    }
    expect(Object.keys(WILL_SEE).sort()).toEqual(Object.keys(GUIDES).sort())
  })
})
```

- [ ] **Step 2: Run the guards; they should fail.** `GUIDES` is empty and `will-see` is missing. Run: `pnpm vitest run src/content/gallery/guides.test.ts`

- [ ] **Step 3: Write the content.**
  - **`will-see.ts`:**

```ts
import data from './will-see.json'
import type { CoroutineState } from '@/types/api'

export interface WillSeeCoroutine {
  label: string
  state: `${CoroutineState}`
  depth: number
}

/** "What you will see" for one demo: the tree at one telling moment. */
export interface WillSee {
  /** When the picture is taken, e.g. "About 10 s in". */
  moment: string
  coroutines: WillSeeCoroutine[]
}

/** Read by GuideLabelsTest too: it checks every label at its depth against a real run. */
export const WILL_SEE = data as Record<string, WillSee>

export const willSeeFor = (demoId: string): WillSee | undefined => WILL_SEE[demoId]
```

  - **`basics.ts`** exports `BASICS_GUIDES: DemoGuide[]`.
  - **`guides.ts`** builds `GUIDES` from it with `Object.fromEntries(BASICS_GUIDES.map((g) => [g.demoId, g]))`.
  - **Source:** every guide and picture is written from `backend/src/main/kotlin/com/jh/proj/coroutineviz/scenarios/BasicScenarios.kt`. The table gives each demo's facts (scripted seconds), its picture and its mistake:

| Demo | Facts the guide must state | Picture (moment → label/state/depth) | Mistake |
|---|---|---|---|
| `nested` | `fetch-product` first and joined (2.5 s); then 3 sections at once; `fetch-reviews` starts `fetch-review-photos`, `fetch-similar-products` starts `fetch-thumbnails`; the page's code ends at 2.5 s, it completes at ~16 s; 11 coroutines | ~10 s: page WAITING_FOR_CHILDREN 0; fetch-product, load-price-box COMPLETED 1; fetch-price, fetch-delivery-date COMPLETED 2; load-reviews WAITING 1; fetch-reviews WAITING 2; fetch-review-photos SUSPENDED 3; load-recommendations WAITING 1; fetch-similar-products WAITING 2; fetch-thumbnails SUSPENDED 3 | GLOBAL_SCOPE_USAGE |
| `parallel` | 5 widgets at once (alerts 1.5 s … visitors 11 s); `joinAll`; then `render-dashboard` 2.5 s; ~14 s; 31 s of requests | ~5 s: load-dashboard ACTIVE 0 (joining); fetch-sales SUSPENDED 1; fetch-orders COMPLETED 1; fetch-visitors SUSPENDED 1; fetch-alerts COMPLETED 1; fetch-top-products SUSPENDED 1 | UNNECESSARY_ASYNC_AWAIT |
| `cancellation` | `profile-screen` with avatar (2 s), friends, activity feed; `sync-settings` a sibling outside it; closed at 6.5 s; avatar stays done; sync to ~12 s | ~7 s: app-session ACTIVE 0; profile-screen CANCELLED 1; fetch-avatar COMPLETED 2; fetch-friends, fetch-activity-feed CANCELLED 2; sync-settings SUSPENDED 1 | MISSING_CANCELLATION_CHECK |
| `deep-nesting` | 6 folders (home/ana/projects/vizcore/backend/src); each lists 1 s, starts its subfolder, indexes own files 1.5 s, joins, adds results 0.8 s; deepest 6 s of files; complete from the deepest up, src at ~12 s, home at ~16 s | ~14 s: index-home ACTIVE 0; index-ana ACTIVE 1; index-projects SUSPENDED 2; index-vizcore, index-backend, index-src COMPLETED 3/4/5 | COROUTINE_EXPLOSION |
| `mixed` | validate-cart first, joined (2.5 s); reserve-stock, charge-card (6 s, the slowest), calculate-shipping at once; then create-order, then send-receipt; ~14 s vs 19.5 s in a row | ~6 s: checkout ACTIVE 0; validate-cart COMPLETED 1; reserve-stock SUSPENDED 1; charge-card SUSPENDED 1; calculate-shipping COMPLETED 1 | SHARED_MUTABLE_STATE |
| `exception` | 5 requests at once; delivery status (3.5 s) and profile (6 s) complete; loyalty fails at 12 s with HTTP 500; orders and invoices, still loading, are cancelled; the page does not complete | just after 12 s: load-account-page FAILED 0; fetch-delivery-status, fetch-profile COMPLETED 1; fetch-orders, fetch-invoices CANCELLED 1; fetch-loyalty-points FAILED 1 | UNHANDLED_EXCEPTION |
| `dispatcher` | photos 2 s apart; download on IO (3 s), resize on Default (4 s), upload on IO (1.5 s); different pools at the same moment; ~13 s | ~4.5 s: make-thumbnails WAITING 0; process-photo-1 ACTIVE 1; download-photo-1 COMPLETED 2; resize-photo-1 SUSPENDED 2; process-photo-2 ACTIVE 1; download-photo-2 SUSPENDED 2; process-photo-3 ACTIVE 1; download-photo-3 SUSPENDED 2 | WRONG_DISPATCHER |

  - **States and `join()`:** a coroutine waiting in `join()`/`joinAll()` is shown as ACTIVE, because only `delay` (and `await`) emit `CoroutineSuspended`. A coroutine whose body has ended while children run is WAITING_FOR_CHILDREN.
  - **Checking the picture:** check each one in the browser (Task 6) and adjust it if the real tree differs. For example, the exception page's final state may be CANCELLED rather than FAILED.
  - **Wording:**
    - plain, short sentences;
    - Kotlin snippets simplified to `launch`/`coroutineScope`, with the same structure as the demo;
    - the only `// <number>` comments in a snippet are step markers, so a duration is written as `// takes 8 s`, not `// 8 s`.

- [ ] **Step 4: Run the guards and tsc; they should pass.** Run: `pnpm vitest run src/content/gallery && pnpm exec tsc --noEmit`
- [ ] **Step 5: Commit:** `feat(frontend): How-it-works guides for the 7 Basics demos`

---

### Task 3: The backend check of "what you will see"

**Files:**
- create `backend/src/test/kotlin/com/jh/proj/coroutineviz/scenarios/GuideLabelsTest.kt`;
- modify `backend/build.gradle.kts` (next to the `spaDemoCatalog` input);
- modify `.github/workflows/ci-backend.yml` (both `paths:` lists).

- [ ] **Step 1: Write the test.** It reads `../frontend/src/content/gallery/will-see.json`, as `DemoCatalogTest` reads the catalog fixture, and fails with a clear message if the file is missing. For each demo id, a `@ParameterizedTest` with `@MethodSource` reading the JSON does the following:
  - starts the demo with the same `when (id)` switch as `BasicsDemoPacingTest`; an unknown id fails with "add <id> to GuideLabelsTest";
  - runs it inside `DemoRuns(maxConcurrent = 0, timeScale = 0.02).tryStart()`, with a 20 s timeout;
  - waits until every created coroutine has a terminal event;
  - computes each label's depth from the `parentCoroutineId` chain among the run's `CoroutineCreated` events, so the root is depth 0;
  - asserts that every pictured `label` was created at its `depth`;
  - does not check states, because a state depends on the moment (spec).
- [ ] **Step 2: See it fail for the right reason.**
  - **Missing file:** before Task 2's JSON exists on the branch, the test fails on the missing file.
  - **Wrong depth:** with the JSON in place, temporarily change one depth (e.g. `fetch-thumbnails` 3 → 2) and see that demo fail with the label and both depths in the message. Then restore the depth.
- [ ] **Step 3: Declare the input and add the trigger path.**
  - Add `will-see.json` as a test input in `build.gradle.kts`, as `withPropertyName("galleryWillSee")` with `PathSensitivity.RELATIVE`.
  - Add `'frontend/src/content/gallery/will-see.json'` to both `paths:` lists in `ci-backend.yml`.
- [ ] **Step 4: Run it.** Run `./gradlew test --tests '*GuideLabelsTest*'`, then `ktlintCheck detekt`. All should PASS.
- [ ] **Step 5: Commit:** `test(backend): How-it-works pictures name real coroutines at their depth`

---

### Task 4: The page

**Files:**
- create `HowItWorksPage.tsx`, `WillSeeTree.tsx` and `HowItWorksPage.test.tsx` in `frontend/src/components/gallery/`;
- create `frontend/src/routes/gallery/$slug.tsx`.

- [ ] **Step 1: Write the failing tests** (`HowItWorksPage.test.tsx`).
  - **Mocks:**
    - `useScenarios` and `useStartDemo`, as `GalleryPage.test.tsx` does;
    - `@tanstack/react-router`'s `Link`, rendered as an `<a>` whose `href` is built from `to`, `params`, `search` and `hash`.
  - **Cases:**
    - **`slug="nested"` renders in order:**
      - the breadcrumb, Gallery › Basics › Nested: Product Page, with links to `/gallery` and `/gallery?group=basics`;
      - the h1 with the catalog name, the level chip and the duration;
      - the sections Story, Code, Step by step, What you will see (with its moment caption), Key idea and Common mistake;
      - the mistake link pointing to `/gallery?group=mistakes#GLOBAL_SCOPE_USAGE`.
    - **Steps:** they appear as an ordered list, numbered 1…n, matching the guide.
    - **What you will see:** every coroutine of the picture appears, with its state.
    - **Run this example:** calls `start` with the catalog scenario. A `startError` renders the `DemoStartAlert` text.
    - **Unknown slug:** the page reads "No guide for this example yet", has a link back to the Gallery, and has no Run.
    - **A catalog demo without a guide** (e.g. `flow-simple`): the page shows the fallback with a Run button for that demo.
    - **Catalog loading:** a skeleton. **Catalog error:** `ErrorState` with Retry.
- [ ] **Step 2: Run the tests; they should fail** (the module is missing).
- [ ] **Step 3: Implement.**
  - **`HowItWorksPage({ slug })`:**
    - finds the scenario with `demoSlug(s.id) === slug` and its guide in `GUIDES`;
    - renders the sections in the spec's order: a two-column grid on `lg` for Code next to Step by step, then What you will see, Key idea and Common mistake;
    - the mistake box uses an amber border and a soft fill (`border-warning-300 bg-warning-50`) with normal text colour, never amber text.
  - **`WillSeeTree`:** draws each row indented by `depth`, with `getStateColors(state)`'s icon and a chip in its `chipColor`. The label is in mono, and the state text is lower-case with spaces.
  - **The route:**

```tsx
import { createFileRoute } from '@tanstack/react-router'
import { Layout } from '@/components/Layout'
import { HowItWorksPage } from '@/components/gallery/HowItWorksPage'

export const Route = createFileRoute('/gallery/$slug')({ component: HowItWorksRoute })

function HowItWorksRoute() {
  const { slug } = Route.useParams()
  return (
    <Layout>
      <HowItWorksPage slug={slug} />
    </Layout>
  )
}
```

- [ ] **Step 4: Regenerate the route tree and run the checks.** Run `pnpm build`, which regenerates `routeTree.gen.ts`, then `pnpm vitest run src/components/gallery src/routes && pnpm exec tsc --noEmit`. All should PASS.
- [ ] **Step 5: Commit:** `feat(frontend): How-it-works page at /gallery/$slug`

---

### Task 5: "How it works" on the Gallery cards

**Files:**
- modify `frontend/src/components/gallery/DemoCard.tsx`;
- modify `frontend/src/components/gallery/GalleryPage.test.tsx`.

- [ ] **Step 1: Change the tests.**
  - Mock `Link` in `GalleryPage.test.tsx`, as in Task 4.
  - Replace the test "offers no How it works or level until a demo has a guide" with two tests:
    - Every Basics card has a "How it works" link to `/gallery/<slug>`, with an accessible name `How it works: <name>`. It also shows its guide's level.
    - A Flow card has no link and no level.
- [ ] **Step 2: Run the tests; they should fail** (no link yet).
- [ ] **Step 3: Implement.** When `guide` is set, `DemoCard` shows a bordered "How it works" button next to Run. It uses HeroUI `Button as={Link} to="/gallery/$slug" params={{ slug: demoSlug(scenario.id) }}`, with the `aria-label` from Step 1. Lay it out like the sketch, with the two buttons side by side.
- [ ] **Step 4: Run the tests; they should pass.** Run: `pnpm vitest run src/components/gallery`
- [ ] **Step 5: Commit:** `feat(frontend): Gallery cards link to their How-it-works page`

---

### Task 6: Browser check, changelog, PR

- [ ] **Step 1: Start the app.**
  - Backend: `PORT=18291 CORS_ALLOWED_ORIGINS=http://localhost:13291 ./gradlew :run`.
  - Frontend: `VITE_PROXY_TARGET=http://localhost:18291 pnpm dev --port 13291`.
- [ ] **Step 2: Check the pages.**
  - Open every Basics guide in both themes.
  - Run at least two of them, e.g. `nested` and `exception`. Compare the tree at about the stated moment with "What you will see", and correct the JSON or the text where the real tree differs.
  - Check the mistake link (it should scroll to the card) and the fallback (`/gallery/no-such-demo` and `/gallery/flow-simple`).
  - Save screenshots to the scratchpad: one guide in dark and in light, and the Basics cards with "How it works".
- [ ] **Step 3: Add the CHANGELOG entry** under Unreleased → Added, e.g. "How it works pages for the 7 Basics demos …".
- [ ] **Step 4: Run all gates, then push and open the PR.**
- [ ] **Step 5: Run up to 3 Codex rounds.** Ask Codex to check content accuracy against `BasicScenarios.kt`. Fix the findings with tests where testable, wait for CI to pass, and update the PR description through the REST API.
