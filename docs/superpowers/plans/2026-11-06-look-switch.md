# PR L0 · The Look switch — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Let the user try three new looks for the session page — **A · Compact**, **B · Calm**, **C · Bold** — in the real app, next to today's view (**Classic**), and keep whichever works best in use. This PR builds only the switch and the structure the looks plug into. A, B and C get their base tokens (colours, fonts, density, spacing) read from the sketches; their full layouts come in follow-up PRs (L1–L3). Classic stays pixel-identical. After the trial, the losing looks are deleted.

**Not in this PR:** any look's layout, any change to behaviour, data or the API, any page other than the session page, the backend.

**Base:** stacked on Part 4, PR #297 (`feat/session-selection`, not merged yet). Branch `feat/look-switch` from `origin/feat/session-selection` (7cff02bd), worktree `../vizcore-looks`. The PR targets `feat/session-selection`; once #297 merges, GitHub retargets it to `main`.

**Spec:** [`docs/superpowers/specs/2026-11-05-session-dock-design.md`](../specs/2026-11-05-session-dock-design.md) — the session page this PR re-skins (header, main view, dock and its tabs). The sketches are copied to [`2026-11-06-look-switch/sketches/`](2026-11-06-look-switch/sketches/) (K-A/B/C × Tree, Timeline, Problems, Story; open in a browser). The Tree sketches (`K-A-Compact`, `K-B-Calm`, `K-C-Bold`) set the tokens; the Timeline, Problems and Story sketches (Parts 7, 9, 10) were read only to make sure the same tokens cover their colours (a danger red, a purple for "waiting for children", amber callouts).

**Gates** (from `frontend/`): `pnpm exec tsc --noEmit && pnpm lint && pnpm test && pnpm build`. No backend change, so no backend tests.

---

## 1. What the user sees

- The session page's **⋯** menu gains a section **"Look · trial"** with four items: **Classic**, **A · Compact**, **B · Calm**, **C · Bold**, under a divider after the page's own actions. The current one carries a check mark and `aria-current="true"` (the menu has no selection mode: its other items are actions, and a radio menu would turn them into radios). Picking one re-skins the page at once.
- The choice is **remembered per browser** (`localStorage`, key `vizcore-look`).
- A link can set it: **`/sessions/<id>?look=a`** (also `b`, `c`, `classic`). The link wins over the stored choice and is then remembered; the `look` parameter is then removed from the address (replace, no history entry), so a later menu choice is not overruled on reload.
- An **unknown value** (`?look=z`, a stored `b` after B was deleted, garbage in storage) **falls back to Classic**. For the link, Classic is then remembered too: the link always wins, whatever it says.
- Every other page (Sessions, Gallery, How it works, Compare, the shared read-only view) stays Classic: the look only applies while a `/sessions/$sessionId` page is mounted.
- Exported PNG and SVG and recorded replays stay Classic dark, as today, whatever the look (the export already forces dark; it now also drops the look for that instant).

## 2. Mechanism

### 2.1 The look on `<html>`

`data-look="classic|a|b|c"` is set on `document.documentElement` by the session page while it is mounted (a layout effect, so the first paint already has it), and removed when the page unmounts. Why `<html>` and not a wrapper `div`:

- HeroUI's popovers, dropdowns, tooltips and modals render in a portal under `<body>`; tokens set on a page wrapper would not reach them, and the ⋯ menu itself would stay Classic.
- `body`'s background (`var(--bg)`) shows around and below the page; the look must recolour it.
- HeroUI's theme variables live on `html.dark` / `html.light`; overriding them on the same element keeps the cascade simple (see 2.3).

The navbar therefore follows the look on the session page (it is part of that page's chrome); leaving the page removes the attribute, so it never leaks to other pages.

### 2.2 The hook and the provider

`frontend/src/looks/look-context.tsx`:

```ts
export function LookProvider({ link, onLinkRead, children }: {
  link: string | undefined          // the raw ?look= value, if any
  onLinkRead?: () => void           // the route strips ?look= from the address
  children: ReactNode
})
export function useLook(): { look: LookId; setLook: ((id: LookId) => void) | null; looks: readonly LookDefinition[] }
```

- Initial look: `link !== undefined ? parseLook(link) : parseLook(readStored())`. A link is saved at once.
- When `link` changes to another defined value while mounted (a client-side navigation to `?look=b`), it is applied and saved too. After a link is read, `onLinkRead()` runs.
- `setLook(id)` sets state and saves, and also keeps the choice in a module variable for this visit. Opening a session page starts from **this visit's choice first, then storage, then Classic**: storage that is blocked, or reads but will not write (full, a policy), still keeps the choice across navigations (the same idea as `useTheme`'s `data-viz-theme`).
- The provider applies `data-look` to `<html>` in a `useLayoutEffect` and removes it on unmount.
- **Outside a provider** `useLook()` returns `{ look: 'classic', setLook: null }`. The shared view (`/shared/$token`) and every component test that renders `SessionHeader` without a provider get Classic and no Look section in the menu: the switch is offered only where it works.

The provider is mounted by `routes/sessions/$sessionId.tsx` around the page. The route adds `look?: string` to `validateSearch`: absent stays `undefined`; a string is kept as given; **any other present value** (TanStack parses the search as JSON, so `?look=1`, `?look=null`, `?look=true`, `?look=["a"]`, `?look={}` arrive as a number, null, a boolean, an array, an object) becomes `''`, which reads as unknown → Classic — never dropped (that would restore the stored look) and never coerced (`String(["a"])` would select A). The route passes `onLinkRead = () => navigate({ search: prev => ({ ...prev, look: undefined }), replace: true })`, which keeps `scenarioId`, `scenarioName` and `live`.

### 2.3 Tokens per look (CSS)

Each look's `tokens.css` overrides, for `html.dark[data-look='x']` and `html.light[data-look='x']`:

- **vizcore tokens** from `styles/tokens.css`: `--bg`, `--surface*`, `--border*`, `--text*`, `--primary/success/warning/danger/created`, the `*-soft` fills, `--state-chip-text`.
- **HeroUI theme variables** (`--heroui-background`, `--heroui-foreground`, `--heroui-divider`, `--heroui-focus`, `--heroui-content1..4`, the `--heroui-default-50..900` neutral ramp, and 50–900 ramps for `primary`, `secondary`, `success`, `warning`, `danger`, with foregrounds). Components mostly use HeroUI/Tailwind classes (`bg-content1`, `text-default-500`, `border-divider`, `Chip color="warning"`), so overriding only the vizcore tokens would change little. HeroUI needs `H S% L%` triplets and ramps that CSS cannot derive from a hex value, so `2026-11-06-look-switch/gen-look-tokens.mjs` generates this block from a small per-look table of base colours; the output is committed and may be hand-edited.

And for `html[data-look='x']` (both themes):

- **Density:** the root `font-size` (A 15 px, B and C 16 px = today). Tailwind and HeroUI sizes are in `rem`, so this scales text, controls and spacing together — the cheapest density knob. Where a fixed pixel value assumes 16 px (the session page height `calc(100dvh - 65px)` for the 4 rem navbar), the look's CSS corrects it.
- **Shape:** `--radius`, `--radius-sm`, `--heroui-radius-small/medium/large` (A 4/3 px, B 14/10 px, C 16/10 px). The main view and the dock use a fixed Tailwind `rounded-xl`, so each look also sets their `border-radius` through the `main` and `dock` hooks (2.4); Classic's utilities stay as they are.
- **Fonts:** `--font-sans` and `--font-mono` stay Inter and JetBrains Mono in all looks (the sketches use them). C adds `--font-display: 'Space Grotesk'` for the page title and dock headings (a new `@fontsource/space-grotesk` dependency, imported only by C's CSS).
- **Spacing:** the page padding and the gap between the header and the body, set on the CSS hooks below (A tight 8×12 px / 8 px, B airy 24×32 px / 18 px, C 16×28 px / 14 px). The 12 px gap between the main view and the dock stays in every look: `lib/dock-layout.ts` sizes the dock with it (`GAP_PX`). A look that wants another gap, or a dock beside the main view, first makes the dock's size rules read the real gap and orientation (and the separator's semantics follow), with Classic's defaults unchanged — a shared change, in that look's PR. `looks-css.test.ts` guards the gap.

**Specificity:** `html.dark[data-look='a']` (0,2,1) beats HeroUI's `.dark` (0,1,0) and `tokens.css`'s `html.light` (0,1,1), whatever the order the bundles load in. **Classic has no CSS at all**, so it cannot drift: its pixels are today's by construction, and the before/after screenshots prove it.

Colours that are resolved in JS (`stateColor()` hex values for the SVG canvas, `palette.ts`) do not follow the CSS tokens. That is accepted for L0: the tree's state colours stay as they are in every look until a follow-up decides whether its look needs them per look (then via a CSS variable read by the canvas, in that PR).

### 2.4 CSS hooks: `data-look-part`

Stable attributes on the session page's big parts, with no Classic CSS attached:

| Hook | Element |
|---|---|
| `data-look-part="page"` | the full-height session container in `routes/sessions/$sessionId.tsx` |
| `data-look-part="workspace"` | `SessionWorkspace`'s root column (header + main view + dock) |
| `data-look-part="header"` | `SessionHeader`'s `<header>` |
| `data-look-part="body"` | Classic's `SessionBody` column (main view + dock) |
| `data-look-part="main"` | `CoroutineCanvas`'s root |
| `data-look-part="dock"` | `SessionDock`'s root |
| `data-look-part="dock-tabs"` | `DockTabs`' tab bar |

Per-look CSS targets `[data-look='x'] [data-look-part='header']` etc. (0,2,0+, above Tailwind utilities' 0,1,0). Adding an attribute changes no pixels. Follow-ups may add hooks where they need them (one attribute, no Classic CSS).

### 2.5 Per-look layout slots (only where CSS cannot do it)

A look may replace a piece of layout that CSS cannot rearrange. The registry types the available slots:

```ts
export interface LookSlots {
  /** The main view and the dock under the header. Classic: a column, main view above the dock. */
  SessionBody: (props: SessionBodyProps) => ReactElement
}
export interface SessionBodyProps { sharedRef: Ref<HTMLDivElement>; main: ReactNode; dock: ReactNode }
```

`useLookSlot('SessionBody')` returns the current look's version, or Classic's. **A slot is a render function, called in place** (`sessionBody({ … })`, not `<SessionBody />`): swapping a component type would remount everything under it when the look changes — the graph's pan and zoom, Evidence's open sections, every panel's query subscription. Called in place, React keeps them, provided every look's version returns the same shape: for SessionBody, one `div` whose first two children are `main` and then `dock`; attributes, classes and extra children after those two may differ. `slots.test.tsx` proves it with a look that brings its own body: stateful children keep their state and mount once across switching there and back.

Classic's `classicSessionBody` renders exactly today's `<div ref={sharedRef} className="flex min-h-0 flex-1 flex-col gap-3">{main}{dock}</div>` (plus the `body` hook). In L0 no look fills a slot; the one call site (in `SessionWorkspace`) proves the plumbing and costs nothing. A follow-up that needs a different arrangement adds `slots: { SessionBody: boldSessionBody }` to its look and keeps every prop, handler and data flow as is. A rearrangement that cannot keep that shape needs portals or a shared change, and its PR says so. New slots are added only when a look needs one, with Classic's version extracted unchanged.

**Rule for every look:** a look changes presentation only. Data, hooks, handlers, selection, tab state, request profile and accessibility names are shared; a slot receives rendered nodes and refs, never data to re-derive.

## 3. File layout

```
frontend/src/looks/
  registry.ts            the list of looks: id, label, trial flag, slots; LookId; parseLook; DEFAULT_LOOK
  look-context.tsx       LookProvider, useLook, useLookSlot; storage; <html data-look>
  look-context.test.tsx  the switch, persistence, ?look=, fallback, data-look, slots
  classic/
    index.ts             Classic's definition: no CSS; the default slots
    SessionBody.tsx      classicSessionBody: today's main-view-and-dock column, unchanged
  a-compact/
    index.ts             A's definition; imports ./tokens.css
    tokens.css           colours (generated block), density, shape, spacing
  b-calm/  (same)
  c-bold/  (same, + Space Grotesk)
```

`registry.ts`:

```ts
import { classic } from './classic'
import { compact } from './a-compact'
import { calm } from './b-calm'
import { bold } from './c-bold'

export const LOOKS = [classic, compact, calm, bold] as const
export type LookId = (typeof LOOKS)[number]['id']
```

Each look folder's `index.ts` exports a `LookDefinition` (`{ id, label, trial, slots? }`) and imports its own CSS, so the CSS ships only while the folder is referenced. Each folder also holds `palette.json` (the base colours the generator reads) and `look.test.ts` (that look's id and label). Every shared test (`registry.test.ts`, `look-context.test.tsx`, `looks-css.test.ts`, the ⋯ menu and route tests) is **registry-driven**: it reads the ids and labels from `LOOKS`, so deleting a look changes no shared test. The generator, `docs/superpowers/plans/2026-11-06-look-switch/gen-look-tokens.mjs <look folder>`, holds no look data: it reads the folder's `palette.json` and rewrites the generated block of its `tokens.css`. HeroUI foregrounds on solid fills are chosen by measured contrast (white or near-black), and `looks-css.test.ts` asserts every fill/foreground pair reaches WCAG AA (4.5:1) in both themes.

## 4. Removing a look

To delete, say, B after the trial:

1. Delete `frontend/src/looks/b-calm/`.
2. Delete its import and its entry in `frontend/src/looks/registry.ts`.
3. Run `pnpm exec tsc --noEmit`: `LookId` no longer contains `'b'`, so any leftover reference fails to compile. Look-specific tests and data (`look.test.ts`, `palette.json`) live in the look's folder and go with it; shared tests are registry-driven and need no change.
4. A stored or linked `b` now falls back to Classic by itself (2.2).
5. The one exception, for C only: also remove `@fontsource/space-grotesk` from `package.json` (`pnpm remove @fontsource/space-grotesk`). C's `tokens.css` says so at the top.
6. Remove the look's entry from the CHANGELOG's trial note when the trial ends.

When one look wins and replaces Classic, a later PR moves its tokens into `styles/tokens.css` / `palette.ts` and deletes the switch (`looks/`, the provider in the route, the menu section) — out of scope here.

## 5. How A, B and C plug in (L1–L3)

Each follow-up PR works inside its look's folder:

- **Tokens first:** adjust `tokens.css` (re-run the generator or hand-edit).
- **CSS per part:** add rules under `[data-look='x'] [data-look-part='…']`, adding hooks to components where needed (an attribute only, never a Classic style).
- **Slots only where CSS cannot do it:** add the slot type in `registry.ts`, extract Classic's version unchanged into `classic/`, call `useLookSlot` at that point, and give the look its component in its folder.
- **Content differences** (B's sentence under the title, C's "Right now" banner) are layout, not behaviour: they read only what the page already has, via a slot's props. Anything needing new data is a separate, look-independent PR.
- Each L-PR proves Classic unchanged with the same before/after screenshot comparison.

## 6. The base tokens (from the sketches)

| | A · Compact | B · Calm | C · Bold |
|---|---|---|---|
| Sketch theme | dark (JetBrains-style) | light (soft zinc) | dark (near-black, saturated) |
| bg / surface | `#1e1f22` / `#2b2d30` | `#f7f7f8` / `#ffffff` | `#0b0d12` / `#18181b` |
| border / border-2 | `#393b40` / `#4e5157` | `#e4e4e7` / `#d4d4d8` | `#27272a` / `#3f3f46` |
| text / muted | `#dfe1e5` / `#9da0a8` | `#18181b` / `#52525b` | `#f4f4f5` / `#a1a1aa` |
| primary · success · warning · danger | `#548af7` `#5fb865` `#e5a50a` `#e5534b` | `#2563eb` `#15803d` `#b45309` `#dc2626` | `#3b82f6` `#22c55e` `#f59e0b` `#f43f5e` |
| waiting for children (secondary) | `#a571e6` | `#9333ea` | `#a78bfa` |
| other theme | derived: IntelliJ-Light-like | derived: calm dark zinc | derived: bold light, strong borders |
| fonts | Inter, JetBrains Mono | Inter | Space Grotesk (display), Inter, JetBrains Mono |
| density (root size) | 15 px | 16 px | 16 px |
| radius (lg / sm) | 4 / 3 px | 14 / 10 px | 16 / 10 px |
| page padding / gap | 8×12 / 8 px | 24×32 / 18 px | 16×28 / 14 px |

Light-theme status colours are the text-safe (≈ AA on white) shades, as Classic's `lightPalette` does. Full tables: `gen-look-tokens.mjs`.

---

## Plan review

- **Codex R1** (revise): P2 current item had no accessible state → `aria-current="true"` (1.); P2 malformed `?look=` values → present non-strings read as Classic, tests for each and for keeping the other search params (2.2, Task 4); P2 removal touched shared tests and the generator → registry-driven tests, per-look `palette.json` and `look.test.ts`, the font dependency named as the one exception (3., 4.); P2 radius tokens did not reach the main view and dock → look-scoped `border-radius` on the hooks (2.3); P2 white text on light fills (2.54:1) → foregrounds by contrast, tested (3.); P2 export tests needed a dark start → specified (Task 5); P3 divider after, not before, a section → the actions get a section with the divider (1., Task 3).

- **Codex R2** (revise, no P1): P2 a component-type slot would remount the main view and dock on a look change → slots are render functions with a fixed shape, tested (2.5); P2 the dock's size rules assume a 12 px gap → looks keep it, guarded by a test, and a side dock is named as a shared change for its PR (2.3); P2 storage that reads but will not write lost the visit's choice → the visit's choice comes first, tested (2.2). Not taken to a third round: no P1 in R2, and the code review covers the fixes.

## Tasks

### Task 1: Registry and `parseLook` (TDD)

- [ ] Test (`looks/look-context.test.tsx`): `parseLook('a'|'b'|'c'|'classic')` returns it; `'z'`, `''`, `null`, `undefined`, `'A'` return `'classic'`; `LOOKS` ids are `classic, a, b, c` in that order, Classic first and not a trial, the others trials with labels `A · Compact`, `B · Calm`, `C · Bold`. Run → fails (no module).
- [ ] Implement `registry.ts` and the four folders' `index.ts` (A/B/C with empty `tokens.css` for now). Run → passes. Commit.

### Task 2: Provider, hook, persistence, link, `data-look` (TDD)

- [ ] Tests, each seen failing first:
  - no provider → `useLook()` is `{ look: 'classic', setLook: null }`, and `<html>` gets no `data-look`;
  - provider, empty storage → Classic, `<html data-look="classic">`;
  - stored `b` → B; stored garbage → Classic;
  - `setLook('c')` → `data-look="c"` and storage `c`;
  - `link="a"` with stored `b` → A, stored `a`, `onLinkRead` called once;
  - `link="z"` → Classic, stored `classic`;
  - link changes from `undefined` to `'c'` while mounted → C;
  - unmount → `data-look` removed;
  - storage that throws → still switches, and a remounted provider keeps the visit's choice.
- [ ] Implement `look-context.tsx`. Commit.

### Task 3: The ⋯ menu section (TDD)

- [ ] Tests in `SessionHeader.test.tsx` (registry-driven): with a `LookProvider`, opening ⋯ shows a "Look · trial" group with every look's label in registry order; only the current one has `aria-current="true"`; choosing one sets `data-look` on `<html>` and stores it; the page's actions are still there. Without a provider there is no Look section (existing tests keep passing).
- [ ] Implement in `SessionHeader.tsx`. With a switch, the page's actions go into an unlabelled-but-`aria-label`led `DropdownSection` with `showDivider` (HeroUI draws the divider *after* a section), followed by the Look section; without one, the menu is exactly today's items. Items keyed `look:<id>`; `onAction` routes `look:` keys to `setLook`. Commit.

### Task 4: Route wiring (TDD)

- [ ] Test (route-level, memory history, real route tree, as `first-run-ctas.test.tsx` does): `/sessions/s1?look=<trial>&scenarioId=…&scenarioName=…&live=true` → `<html data-look>` set, stored, and the address keeps `scenarioId`, `scenarioName`, `live` but loses `look`; `?look=classic` wins over a stored trial look; `?look=z|1|null|true|["a"]|{}|` (empty) all read as Classic and store Classic; no link → the stored look; navigating to another page removes `data-look`.
- [ ] Implement in `routes/sessions/$sessionId.tsx` (`validateSearch` + `LookProvider` + strip) and add `data-look-part="page"`. Commit.

### Task 5: Hooks, the `SessionBody` slot, export stays Classic (TDD where it has behaviour)

- [ ] Test: `useLookSlot('SessionBody')` returns Classic's body for every look in L0; Classic's body renders its children in order inside one column with the ref attached.
- [ ] Test (`export-theme.test.ts`): starting from **both** dark and light, `withDarkTheme` drops `data-look` for the read (before its "already dark" shortcut, which now applies only when there is neither a light class nor a look) and restores it in `finally`, also when the read throws; `asDarkClone` drops it in the clone; with no `data-look` nothing changes (the existing tests).
- [ ] Implement: `classic/SessionBody.tsx`, the call site in `SessionWorkspace`, the `data-look-part` attributes, export-theme change. Commit.

### Task 6: Base tokens for A, B, C

- [ ] Generate the colour blocks; write density, shape, fonts and spacing per 2.3/6; add `@fontsource/space-grotesk` (lockfile edited to add only that package; `pnpm install --frozen-lockfile` passes). `looks-css.test.ts` asserts: every trial look in the registry has a folder with tokens and Classic has none; every rule in a look's CSS is scoped to `[data-look='<its id>']`; dark and light define the same variables; solid fill/foreground pairs reach 4.5:1; **no CSS outside `looks/` mentions `data-look`** (Classic has no rules). Commit.

### Task 7: Gates, Classic pixel check, browser check

- [ ] Gates green.
- [ ] **Classic before/after:** build the base (`origin/feat/session-selection`) and this branch; serve both against one backend (18501) on 13502 and 13501; open the same finished demo session at 1440×900, dark and light, with the dock on Inspector and on Evidence; screenshot with Playwright; pixel-diff (PIL). Expected: 0 differing pixels (or differences only in live clocks, explained).
- [ ] Browser check on 13501: run a demo; switch looks via ⋯ and via `?look=` links; check reload keeps the choice, `?look=z` → Classic, other pages Classic; screenshots of all four looks (dark and light) to the scratchpad `looks-shots/`. Stop the servers.

### Task 8: Codex code review, PR

- [ ] Codex review of the diff, fix P1/P2 (≤ 3 rounds + confirmation).
- [ ] CHANGELOG entry; push; PR against `feat/session-selection`, stacked on #297; watch CI. Never merge.
