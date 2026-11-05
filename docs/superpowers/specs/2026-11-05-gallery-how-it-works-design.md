# Gallery, "How it works" pages, and light mode

**Date:** 2026-11-05
**Status:** design approved in conversation; awaiting review of this document
**Sketch:** `.superpowers/brainstorm/75781-1793866395/gallery-pages-v3.html` (pages 1 and 2)

## Goal

Bring back a Gallery that teaches coroutine patterns: a page listing every demo by topic, and a
"How it works" page per demo that explains the story, the code and what the visualizer will show.
Add a light theme next to today's dark one. The session view (tree, graph, timeline, threads)
stays as it is.

## Decisions

| # | Decision |
|---|---|
| G-1 | **The Gallery is a destination again.** The top bar, brand-only today (`Layout.tsx`, D-1/D-2/D-3), gets two links after the brand, **Sessions** (`/`) and **Gallery** (`/gallery`), and the ☾ / ☀ switch on the right. This supersedes D-2 of the 2026-08-23 SPA redesign for the Gallery only, and the brand-only comment in `Layout.tsx` is rewritten to say so. Compare stays an action (D-3). The "New demo session" dialog stays as the quick way to start a demo from the sessions list. |
| G-2 | **The session view is not changed.** Run, from the Gallery or from a "How it works" page, opens the same session view the dialog opens today. The guide panel inside the session view (sketch page 3) is out of scope. |
| G-3 | **The demo list comes from the server; the teaching content lives in the frontend.** `GET /api/scenarios` already gives id, name, description, duration and group for all 32 demos. Level, tags and the "How it works" text are frontend content keyed by demo id. A demo without content still appears, with Run but without "How it works". |
| G-4 | **Content first for the 7 Basics.** They have their stories (PR #282). Other groups get content when their story PRs land. |
| G-5 | **Light and dark, dark by default.** A ☾ / ☀ switch in the top bar; the choice is remembered in the browser. Default stays dark, so nobody's current view changes unasked. |
| G-6 | **Common mistakes are shown as text first.** A "Common mistakes" tab lists the 10 anti-patterns the detector already knows (`AntiPatternDetector`), each with what goes wrong and the fix. Their Run buttons arrive with the mistake demos, a separate later project. |
| G-7 | **No new dependency for code.** Code is shown in a plain monospace block with numbered step comments; no syntax-highlighting library. |

## Pages

### `/gallery`: the list

- Title "Example Gallery" and one sentence of introduction.
- Tabs, one per catalog group in catalog order, labelled with the server's `groupLabel`
  (Real life, Basics, Channels, Flow, Patterns, Sync: Mutex, Sync: Semaphore, Combined), each with
  its count. Then comes **⚠ Common mistakes**, marked in amber (`--warning`, as a potential problem,
  never red). Amber is used for icons, borders and soft fills, never for body text, because it is
  too faint as text on white.
- The tab is kept in the URL: `/gallery?group=<group>` for the catalog groups and
  `/gallery?group=mistakes` for the mistakes. Each mistake card has an anchor, its id (e.g.
  `/gallery?group=mistakes#LEAKED_COROUTINE`), so links can point at one card.
- A card per demo, as the old gallery had:
  - **Title row:** the name, plus a level chip (beginner, intermediate or advanced) when content
    exists.
  - **Body:** the description, then the tags and the duration.
  - **Buttons:** **How it works** (only when content exists) and **Run**.
- A Common-mistakes card shows the title, the detector's name for it (e.g. `LEAKED_COROUTINE`),
  one line on what goes wrong, and a disabled Run with "Demo coming".
- **States:** loading uses skeleton cards, and an error offers Retry. A group the server does not
  send is not shown. A Run that fails shows an inline alert above the cards, with the same text the
  dialog uses.

### `/gallery/$slug`: How it works

The slug is the demo id with `/` replaced by `-` (`flow/simple` → `flow-simple`,
`sync/semaphore/producer-consumer` → `sync-semaphore-producer-consumer`). A test asserts that every
catalog id maps to a distinct slug.

Sections, top to bottom:

1. **Header:** a breadcrumb (Gallery › Basics › Product page), the title, the level and duration,
   and a primary **Run this example** button.
2. **The story:** two or three sentences in everyday terms.
3. **The code,** next to **Step by step.** The code is a Kotlin snippet whose `// 1`, `// 2`
   comments match the numbered steps on the right. Each step has a short title and one or two
   sentences.
4. **What you will see:** the coroutine names and states as they will appear in the tree,
   indented by depth. It is drawn with the app's existing state colours and chips, as a static
   picture, not a live view. It shows the most telling moment, which the guide author picks: for
   the Product page that is mid-run, with the parent waiting for its children. The caption names
   that moment, e.g. "About 8 s in".
5. **Key idea:** one sentence.
6. **Common mistake:** an amber box, when the content names one. It gives the mistake in one or
   two sentences and links to its card on the Common-mistakes tab
   (`/gallery?group=mistakes#<id>`).

A Run that fails shows an inline alert under the header, with the same text the dialog uses.

An unknown slug, or a demo without content, shows "No guide for this example yet", with a Run
button when the demo exists and a link back to the Gallery.

### Run

Run does exactly what the "New demo session" dialog does today: create a `scenario-` session, then
navigate to `/sessions/$id` with `scenarioId` and `scenarioName`. The dialog's start logic moves
into a shared hook (`useStartDemo`), used by the dialog, the Gallery and the How-it-works page.
Errors read the same as in the dialog (`describeApiError`), including "Server busy" for the demo
limit.

## Content

`frontend/src/content/gallery/`:

```ts
// types.ts
export interface DemoGuide {
  demoId: string                       // catalog id, e.g. 'nested'
  level: 'beginner' | 'intermediate' | 'advanced'
  tags: string[]
  story: string
  code: string                         // Kotlin, with // 1, // 2 … step markers
  steps: { title: string; text: string }[]
  keyIdea: string
  mistakeId?: MistakeId
}

// The detector's kinds, e.g. 'LEAKED_COROUTINE'; the 10 values of AntiPatternType.
export type MistakeId = AntiPatternType   // from '@/hooks/use-anti-patterns'

export interface Mistake {
  id: MistakeId
  title: string
  whatGoesWrong: string
  fix: string
}
```

- **Guides:** one file per group (`basics.ts` first), exported together from an index as
  `GUIDES: Record<string, DemoGuide>`.
- **Mistakes:** `mistakes.ts` holds the 10 entries, whose ids are the `AntiPatternType` values
  from `use-anti-patterns.ts`.
- **Wording:** plain, short sentences for readers who are new to coroutines and may not be native
  English speakers.

Tests guard the content:

- every guide's `demoId` exists in the catalog fixture (`src/test/demo-catalog.json`);
- every Basics demo has a guide;
- `steps` is not empty, the code's step markers are exactly 1…n for n steps, and `will-see.json`
  has a non-empty entry for every guide;
- every `mistakeId` exists in `mistakes.ts`, and `mistakes.ts` has exactly one entry for each
  `AntiPatternType` value.

**What you will see** lives in `frontend/src/content/gallery/will-see.json`, keyed by demo id:
`{ "nested": [{ "label": "load-product-page", "state": "WAITING_FOR_CHILDREN", "depth": 0 }, …] }`.
It is JSON so that both sides can read it:

- **Frontend:** the page reads it.
- **Backend:** a test runs each demo listed there at a fast time scale and checks that every
  label appears among the coroutines it really creates, at the given depth. It checks labels and
  depth only, not states, because the states depend on the moment. It reads the file the
  same way `DemoCatalogTest` reads the catalog fixture, as a declared test input.

So renaming a coroutine in a demo fails the backend test until the guide is updated.

## Light mode

The colours already live in one place: `src/styles/tokens.css` (CSS variables), mirrored by
`src/styles/palette.ts` and fed to HeroUI's `dark` theme in `tailwind.config.ts`.

- **Tokens:** add a light set of the same variables under `html.light` (surfaces `#fafafa`,
  `#ffffff`, `#f4f4f5`, `#e4e4e7`; text `#18181b`, muted `#52525b`, faint `#71717a`; border
  `#e4e4e7`, border-2 `#d4d4d8`; shadow `0 8px 30px rgba(0,0,0,.08)`). The semantic colours
  (primary, success, warning, danger) stay the same in both themes. `palette.ts` gets the light
  values as a separate export, `lightPalette`, and `tokens.test.ts` checks it against the
  `html.light` block, as it checks `palette` against `:root` today.
- **HeroUI:** fill the `light` theme's background, foreground, content1–3 and divider from the
  light palette, as the `dark` theme does today.
- **Switch:** `useTheme` sets `dark` or `light` on `<html>`, saves the choice to `localStorage`
  (`vizcore-theme`), and is driven by a ☾ / ☀ button in the top bar. An inline script in
  `index.html` applies the saved theme before the first paint, so there is no flash.
- **Hard-coded colours:** about 90 places in 7 files use fixed dark colours. Most are
  `zinc`/`gray`/`slate`/`black`/`white` classes in JobInfoCard, EnhancedCoroutineTreeNode,
  ScenarioBuilder, JobStatusDisplay and WaitingIndicator, and the rest are raw hex in
  DeadlockVisualization and FlowParticlePath.
  - Surface, border and text colours become tokens or HeroUI semantic classes.
  - Colours that carry meaning, such as FlowParticlePath's per-operator colours and
    DeadlockVisualization's fills, move into a separate export in `palette.ts`, `vizColors`, with no
    CSS variables. They are the same in both themes.
  - A test fails on raw hex colours in `.tsx` files outside `src/styles/`. It matches only colour
    literals: `#` and exactly 3, 4, 6 or 8 hex digits as a whole token, inside quotes or after `:`,
    outside comments. Issue references such as `#139` do not trip it.
- **The visualizations:** the tree, graph, timeline and thread lanes keep their structure and
  state colours. Only their surfaces, borders and text follow the theme. Exported PNG and SVG keep
  today's dark look.
- **`color-scheme`:** `color-scheme` in `index.css` follows the theme, so scrollbars and form
  controls match.

## Delivery

Three PRs, each under ~500 lines where possible, each with its own tests:

1. **Light mode:**
   - the tokens, the HeroUI light theme and `useTheme`;
   - the switch and the no-flash script;
   - the hard-coded colour clean-up.

   Tests: the tokens in both themes, the switch saving and restoring the choice, the default being
   dark, and the raw-hex guard. A manual check in the browser covers every main page in both
   themes.
2. **Gallery list:**
   - the `/gallery` route (replacing the redirect), the top-bar link, the tabs and the cards;
   - the `useStartDemo` hook taken out of the dialog;
   - the Common-mistakes tab, with `mistakes.ts`.

   The old route's `GALLERY_SCENARIOS` list and its test (`routes/gallery/index.test.ts`) are
   deleted. The comment in `retired-routes.test.tsx` that mentions the Gallery is corrected.

   Tests:
   - the tabs follow the catalog order;
   - the tab is kept in the URL;
   - every demo has a card;
   - Run starts the right demo id;
   - Run errors read as in the dialog;
   - the dialog still works;
   - the top bar shows Sessions, Gallery and the theme switch.
3. **How it works:** the `/gallery/$slug` route, the page, the 7 Basics guides, `will-see.json`,
   and the backend label test.

   Tests:
   - the content guards above;
   - the page renders every section for one guide;
   - an unknown slug, or a demo with no guide, shows the fallback;
   - Run starts the demo;
   - the slugs are unique.

Before PR 3, PR #282 (the Basics stories) must be merged, because the guides describe its
coroutine names.

Each PR goes through the usual gates (`pnpm vitest run`, `tsc --noEmit`, `pnpm lint`), up to three
Codex review rounds, and a check in the browser in both themes.

## Out of scope

- **The guide panel inside the session view** (sketch page 3).
- **The 10 mistake demos** on the server. Their Run buttons appear when they exist.
- **Guides for the groups other than Basics.** They follow each group's story PR.
- **A theme that follows the operating system.** It can be added later as a third choice.
- **Themed PNG and SVG exports.**
