# Design Token Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the frontend's placeholder indigo, light-only styling with the validated dark-first vizcore token system, so every later redesign step lands in the real app instead of in HTML mockups.

**Architecture:** One typed palette module (`src/styles/palette.ts`) is the single source of truth. It feeds three consumers: a CSS custom-property layer (`src/styles/tokens.css`) for raw CSS and ported sketches, the HeroUI theme in `tailwind.config.ts` for component styling, and a `stateColor()` helper for coroutine-state colouring in components. A consistency test binds the CSS layer back to the palette so the three can never drift.

**Tech Stack:** TypeScript, Tailwind CSS v3.4, HeroUI v2.7, Vite 6, Vitest 4, @fontsource (self-hosted fonts).

**Spec:** `docs/superpowers/specs/2026-08-23-spa-workspace-redesign-design.md` (sub-project 2)

---

## Context for the implementer

You are working in `frontend/`. All paths below are relative to `/Users/jirihermann/Documents/workspace-vizcore/vizcore/frontend` unless stated otherwise.

**What is wrong today, concretely:**

- `tailwind.config.js` sets `primary` to `#6366f1` (indigo). The validated vizcore primary is `#006fee`.
- `tailwind.config.js` sets `darkMode: "class"` and **nothing in the app ever adds that class**, so the "dark-first dev tool" renders in light mode.
- `src/index.css` is 25 lines with no design tokens.
- No fonts are loaded at all. `index.css` names `Inter` in a font stack but the family is never fetched, so the app silently falls back to the system UI font. JetBrains Mono is specified by the design direction and is entirely absent.

**Naming convention — read this before Task 1.** The CSS custom properties use the *same unprefixed names* as the validated sketch theme at `.planning/sketches/themes/default.css` (`--bg`, `--surface`, `--primary`, …). This is deliberate: it means the six validated sketches port into the app with zero renaming. HeroUI namespaces its own variables under `--heroui-*`, so there is no collision.

**Out of scope for this plan:** a light/dark toggle. The light theme definition stays in the Tailwind config so a toggle is a cheap later addition, but dark is simply on. Do not build a theme switcher.

---

### Task 1: Palette as the single source of truth

**Files:**
- Create: `src/styles/palette.ts`
- Create: `src/styles/tokens.css`
- Test: `src/styles/tokens.test.ts`

- [ ] **Step 1: Write the failing test**

Create `src/styles/tokens.test.ts`:

```ts
import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'
import { palette } from './palette'

const here = dirname(fileURLToPath(import.meta.url))
const css = readFileSync(resolve(here, 'tokens.css'), 'utf8')

describe('design tokens', () => {
  it('declares every palette colour as a CSS custom property with the same value', () => {
    for (const [name, value] of Object.entries(palette)) {
      expect(css, `missing or mismatched --${name}`).toContain(`--${name}: ${value};`)
    }
  })

  it('uses the validated vizcore primary, not the placeholder indigo', () => {
    expect(palette.primary).toBe('#006fee')
    expect(css).not.toContain('#6366f1')
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `pnpm vitest run src/styles/tokens.test.ts`
Expected: FAIL — cannot resolve `./palette` (module does not exist yet).

- [ ] **Step 3: Write the palette module**

Create `src/styles/palette.ts`:

```ts
/**
 * The single source of truth for vizcore colour.
 *
 * Values are taken verbatim from the validated sketch theme at
 * `.planning/sketches/themes/default.css`. Three consumers read this module:
 * `tokens.css` (CSS custom properties), `tailwind.config.ts` (HeroUI theme),
 * and `stateColor()` (per-coroutine-state colouring). `tokens.test.ts` asserts
 * the CSS layer never drifts from these values.
 *
 * Semantic meaning is fixed by the design direction: a *potential* leak is
 * warning/amber, never danger/red.
 */
export const palette = {
  // surfaces
  bg: '#0a0a0b',
  surface: '#131316',
  'surface-2': '#18181b',
  'surface-3': '#1f1f23',
  border: '#27272a',
  'border-2': '#323237',

  // text
  text: '#fafafa',
  'text-muted': '#a1a1aa',
  'text-faint': '#71717a',

  // semantic (HeroUI names)
  primary: '#006fee', // running / active / accent
  success: '#17c964', // completed
  warning: '#f5a524', // suspended / cancelled / potential leak
  danger: '#f31260', // failed
  created: '#8b8b94', // created, not yet started
} as const

export type PaletteKey = keyof typeof palette
```

- [ ] **Step 4: Write the CSS token layer**

Create `src/styles/tokens.css`:

```css
/**
 * vizcore design tokens.
 *
 * Names match `.planning/sketches/themes/default.css` verbatim so validated
 * sketches port with no renaming. Values are mirrored from `palette.ts` and
 * guarded by `tokens.test.ts`.
 */
:root {
  /* surfaces */
  --bg: #0a0a0b;
  --surface: #131316;
  --surface-2: #18181b;
  --surface-3: #1f1f23;
  --border: #27272a;
  --border-2: #323237;

  /* text */
  --text: #fafafa;
  --text-muted: #a1a1aa;
  --text-faint: #71717a;

  /* semantic */
  --primary: #006fee;
  --success: #17c964;
  --warning: #f5a524;
  --danger: #f31260;
  --created: #8b8b94;

  /* soft fills for badges and chips */
  --primary-soft: rgba(0, 111, 238, 0.16);
  --success-soft: rgba(23, 201, 100, 0.15);
  --warning-soft: rgba(245, 165, 36, 0.15);
  --danger-soft: rgba(243, 18, 96, 0.15);

  /* type */
  --font-sans: Inter, system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif;
  --font-mono: 'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, monospace;

  /* shape */
  --radius: 12px;
  --radius-sm: 8px;
  --shadow: 0 8px 30px rgba(0, 0, 0, 0.4);
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `pnpm vitest run src/styles/tokens.test.ts`
Expected: PASS, 2 tests.

- [ ] **Step 6: Commit**

```bash
git add src/styles/palette.ts src/styles/tokens.css src/styles/tokens.test.ts
git commit -m "feat(tokens): add vizcore palette and CSS token layer"
```

---

### Task 2: Load the real typefaces

Inter and JetBrains Mono are self-hosted via `@fontsource` rather than linked from Google Fonts, so the app works offline and against a localhost backend with no external request.

**Files:**
- Modify: `package.json` (dependencies)
- Modify: `src/index.css`

- [ ] **Step 1: Install the font packages**

Run: `pnpm add @fontsource/inter @fontsource/jetbrains-mono`
Expected: both packages resolve and are added to `dependencies`.

- [ ] **Step 2: Import the fonts and the token layer**

Replace the top of `src/index.css` — the file currently begins with the three `@tailwind` directives. The imports must come **before** the Tailwind directives so the `@import` statements are valid CSS.

```css
/* Self-hosted typefaces — Inter for UI, JetBrains Mono for ids, file:line and code. */
@import '@fontsource/inter/400.css';
@import '@fontsource/inter/500.css';
@import '@fontsource/inter/600.css';
@import '@fontsource/inter/700.css';
@import '@fontsource/jetbrains-mono/400.css';
@import '@fontsource/jetbrains-mono/500.css';
@import '@fontsource/jetbrains-mono/600.css';

/* Design tokens — must load before Tailwind so utilities can reference them. */
@import './styles/tokens.css';

@tailwind base;
@tailwind components;
@tailwind utilities;
```

- [ ] **Step 3: Point the base font stacks at the tokens**

In the same file, replace the `font-family` line inside `:root` and add a `body` background so the dark ground is painted by the app rather than inherited:

```css
:root {
  font-family: var(--font-sans);
  line-height: 1.5;
  font-weight: 400;

  color-scheme: dark;

  font-synthesis: none;
  text-rendering: optimizeLegibility;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}

body {
  margin: 0;
  min-width: 320px;
  min-height: 100vh;
  background: var(--bg);
  color: var(--text);
}
```

Note the `color-scheme` change from `light dark` to `dark` — this makes native form controls and scrollbars render dark to match.

- [ ] **Step 4: Add a mono utility**

In the `@layer utilities` block at the bottom of `src/index.css`, alongside the existing `.container-custom`:

```css
@layer utilities {
  .container-custom {
    @apply mx-auto max-w-7xl px-4 sm:px-6 lg:px-8;
  }

  /* Ids, file:line references, durations and code. Tabular figures keep
     columns of numbers aligned in the tree and inspector. */
  .font-mono-viz {
    font-family: var(--font-mono);
    font-variant-numeric: tabular-nums;
  }
}
```

- [ ] **Step 5: Verify the app still builds**

Run: `pnpm build`
Expected: build succeeds. Font files appear in the build output under `dist/assets/`.

- [ ] **Step 6: Commit**

```bash
git add package.json pnpm-lock.yaml src/index.css
git commit -m "feat(tokens): self-host Inter and JetBrains Mono, wire token layer"
```

---

### Task 3: Turn dark mode on

`darkMode: "class"` has been configured since the project started and the class has never been added. Setting it on `<html>` in `index.html` — rather than from `main.tsx` — avoids a flash of light content before React mounts.

**Files:**
- Modify: `index.html`
- Test: `src/styles/theme-applied.test.ts`

- [ ] **Step 1: Write the failing test**

Create `src/styles/theme-applied.test.ts`:

```ts
import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'

const here = dirname(fileURLToPath(import.meta.url))
const html = readFileSync(resolve(here, '../../index.html'), 'utf8')

describe('dark scheme', () => {
  it('sets the dark class on <html> so tailwind darkMode:"class" actually applies', () => {
    expect(html).toMatch(/<html[^>]*class="[^"]*\bdark\b[^"]*"/)
  })

  it('advertises the dark theme colour, not the placeholder indigo', () => {
    expect(html).toContain('content="#006fee"')
    expect(html).not.toContain('#6366f1')
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `pnpm vitest run src/styles/theme-applied.test.ts`
Expected: FAIL on both — `<html lang="en">` has no class, and `theme-color` is still `#6366f1`.

- [ ] **Step 3: Apply the class and fix the theme colour**

In `index.html`, change the opening tag:

```html
<html lang="en" class="dark">
```

And the theme-color meta:

```html
<meta name="theme-color" content="#006fee" />
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `pnpm vitest run src/styles/theme-applied.test.ts`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add index.html src/styles/theme-applied.test.ts
git commit -m "feat(tokens): enable dark scheme and correct theme colour"
```

---

### Task 4: Map the HeroUI theme onto the palette

HeroUI components (`Button`, `Card`, `Chip`, `Tabs`…) are styled through the Tailwind plugin's theme, not through the CSS variables. This task makes them read the same palette. The config is converted from `.js` to `.ts` so it can import the typed palette — Tailwind v3.4 loads TypeScript configs natively.

**Files:**
- Delete: `tailwind.config.js`
- Create: `tailwind.config.ts`
- Test: `src/styles/tailwind-theme.test.ts`

- [ ] **Step 1: Write the failing test**

Create `src/styles/tailwind-theme.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { palette } from './palette'
import config from '../../tailwind.config'

describe('tailwind/heroui theme', () => {
  it('exposes the vizcore font families to tailwind utilities', () => {
    const fontFamily = config.theme?.extend?.fontFamily as Record<string, string[]>
    expect(fontFamily.sans[0]).toBe('Inter')
    expect(fontFamily.mono[0]).toBe('JetBrains Mono')
  })

  it('keeps darkMode class-based so the <html class="dark"> switch applies', () => {
    expect(config.darkMode).toBe('class')
  })

  it('does not reference the placeholder indigo anywhere', () => {
    expect(JSON.stringify(config)).not.toContain('#6366f1')
  })

  it('sources semantic colours from the palette module', () => {
    expect(palette.primary).toBe('#006fee')
    expect(palette.warning).toBe('#f5a524')
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `pnpm vitest run src/styles/tailwind-theme.test.ts`
Expected: FAIL — `tailwind.config` resolves to the `.js` file, which has no `fontFamily` extension and still contains `#6366f1`.

- [ ] **Step 3: Replace the config**

Delete `tailwind.config.js` and create `tailwind.config.ts`:

```ts
import { heroui } from '@heroui/react'
import type { Config } from 'tailwindcss'
import { palette } from './src/styles/palette'

/**
 * The HeroUI dark theme is the real theme — vizcore is dark-first by design
 * decision. The light theme is kept in sync so a future toggle is cheap, but
 * nothing switches to it today (see the `dark` class on <html>).
 */
const semantic = {
  primary: { DEFAULT: palette.primary, foreground: '#ffffff' },
  success: { DEFAULT: palette.success, foreground: '#00110a' },
  warning: { DEFAULT: palette.warning, foreground: '#1a1200' },
  danger: { DEFAULT: palette.danger, foreground: '#ffffff' },
  focus: palette.primary,
}

export default {
  content: [
    './index.html',
    './src/**/*.{js,ts,jsx,tsx}',
    './node_modules/@heroui/theme/dist/**/*.{js,ts,jsx,tsx}',
  ],
  theme: {
    extend: {
      fontFamily: {
        sans: ['Inter', 'system-ui', '-apple-system', 'Segoe UI', 'Roboto', 'sans-serif'],
        mono: ['JetBrains Mono', 'ui-monospace', 'SFMono-Regular', 'Menlo', 'monospace'],
      },
      borderRadius: {
        viz: '12px',
        'viz-sm': '8px',
      },
    },
  },
  darkMode: 'class',
  plugins: [
    heroui({
      themes: {
        light: { colors: { ...semantic } },
        dark: {
          colors: {
            ...semantic,
            background: palette.bg,
            foreground: palette.text,
            content1: palette.surface,
            content2: palette['surface-2'],
            content3: palette['surface-3'],
            divider: palette.border,
          },
        },
      },
    }),
    require('@tailwindcss/typography'),
  ],
} satisfies Config
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `pnpm vitest run src/styles/tailwind-theme.test.ts`
Expected: PASS, 4 tests.

- [ ] **Step 5: Verify Tailwind actually loads the TypeScript config**

Run: `pnpm build`
Expected: build succeeds. If Tailwind reports it cannot find a config, confirm no stale `tailwind.config.js` remains — both files present is the likely cause.

- [ ] **Step 6: Commit**

```bash
git add tailwind.config.ts src/styles/tailwind-theme.test.ts
git rm tailwind.config.js
git commit -m "feat(tokens): map HeroUI theme onto the vizcore palette"
```

---

### Task 5: One helper for coroutine-state colour

Components currently pick state colours ad hoc. This helper is the only sanctioned mapping, and it is what the workspace rebuild will consume. It encodes one rule that is easy to get wrong: a **potential leak is amber, never red** — red is reserved for an actual failure.

**Files:**
- Create: `src/lib/state-color.ts`
- Test: `src/lib/state-color.test.ts`

- [ ] **Step 1: Write the failing test**

Create `src/lib/state-color.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { CoroutineState } from '@/types/api'
import { stateColor, stateToken, leakColor } from './state-color'
import { palette } from '@/styles/palette'

describe('stateColor', () => {
  it('maps running states to primary', () => {
    expect(stateColor(CoroutineState.ACTIVE)).toBe(palette.primary)
  })

  it('maps suspended and cancelled to warning', () => {
    expect(stateColor(CoroutineState.SUSPENDED)).toBe(palette.warning)
    expect(stateColor(CoroutineState.CANCELLED)).toBe(palette.warning)
  })

  it('maps completed to success', () => {
    expect(stateColor(CoroutineState.COMPLETED)).toBe(palette.success)
  })

  it('maps failed to danger', () => {
    expect(stateColor(CoroutineState.FAILED)).toBe(palette.danger)
  })

  it('maps created and waiting-for-children to their own neutrals', () => {
    expect(stateColor(CoroutineState.CREATED)).toBe(palette.created)
    expect(stateColor(CoroutineState.WAITING_FOR_CHILDREN)).toBe(palette.primary)
  })

  it('covers every member of CoroutineState', () => {
    for (const state of Object.values(CoroutineState)) {
      expect(stateColor(state)).toMatch(/^#[0-9a-f]{6}$/i)
    }
  })

  it('returns a CSS variable reference from stateToken', () => {
    expect(stateToken(CoroutineState.SUSPENDED)).toBe('var(--warning)')
  })

  it('colours a potential leak amber, never danger red', () => {
    expect(leakColor()).toBe(palette.warning)
    expect(leakColor()).not.toBe(palette.danger)
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `pnpm vitest run src/lib/state-color.test.ts`
Expected: FAIL — cannot resolve `./state-color`.

- [ ] **Step 3: Write the implementation**

Create `src/lib/state-color.ts`:

```ts
import { CoroutineState } from '@/types/api'
import { palette } from '@/styles/palette'

/**
 * The only sanctioned mapping from coroutine state to colour.
 *
 * `WAITING_FOR_CHILDREN` is primary rather than a neutral: structurally the
 * parent is still live work, and dimming it makes a healthy structured-
 * concurrency tree look half-dead.
 */
const STATE_TO_KEY = {
  [CoroutineState.CREATED]: 'created',
  [CoroutineState.ACTIVE]: 'primary',
  [CoroutineState.SUSPENDED]: 'warning',
  [CoroutineState.WAITING_FOR_CHILDREN]: 'primary',
  [CoroutineState.COMPLETED]: 'success',
  [CoroutineState.CANCELLED]: 'warning',
  [CoroutineState.FAILED]: 'danger',
} as const satisfies Record<CoroutineState, keyof typeof palette>

/** Resolved hex, for canvas, SVG and inline styles. */
export function stateColor(state: CoroutineState): string {
  return palette[STATE_TO_KEY[state]]
}

/** CSS variable reference, for stylesheets and the `--c` badge pattern. */
export function stateToken(state: CoroutineState): string {
  return `var(--${STATE_TO_KEY[state]})`
}

/**
 * A *potential* leak is a heuristic finding, not a failure. It is always
 * warning/amber — reserving danger/red for coroutines that actually failed.
 */
export function leakColor(): string {
  return palette.warning
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `pnpm vitest run src/lib/state-color.test.ts`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add src/lib/state-color.ts src/lib/state-color.test.ts
git commit -m "feat(tokens): add sanctioned coroutine-state colour mapping"
```

---

### Task 6: Verify the whole app, not just the tests

A green suite does not prove the app renders. Confirm it visually before calling this done — the project has been burned by exactly this gap before.

- [ ] **Step 1: Run the full test suite**

Run: `pnpm test`
Expected: all tests pass. Any failure here is a regression from Tasks 1–5, not a pre-existing condition — investigate before continuing.

- [ ] **Step 2: Lint**

Run: `pnpm lint`
Expected: clean. `require('@tailwindcss/typography')` inside a `.ts` config may trip `@typescript-eslint/no-require-imports`; if so, convert it to a top-level `import typography from '@tailwindcss/typography'`.

- [ ] **Step 3: Build**

Run: `pnpm build`
Expected: `tsc` passes and Vite emits `dist/`.

- [ ] **Step 4: Look at it**

Run: `pnpm dev` and open the app.

Confirm by eye, and report what you see:
- the page ground is near-black (`#0a0a0b`), not white
- buttons and active nav items are vizcore blue (`#006fee`), not indigo
- body text renders in Inter, not the system fallback — the difference is visible in the headings
- a session id or `file:line` reference set in `.font-mono-viz` renders in JetBrains Mono
- HeroUI `Card` surfaces sit slightly lighter than the page ground rather than white-on-black

- [ ] **Step 5: Commit any fixes and close out**

```bash
git add -A
git commit -m "chore(tokens): verification pass"
```

---

## Definition of done

- `pnpm test`, `pnpm lint` and `pnpm build` all pass.
- The app renders dark, in Inter, with `#006fee` as the accent, confirmed visually.
- `#6366f1` appears nowhere in `frontend/`. Verify: `grep -rn "6366f1" frontend/ --exclude-dir=node_modules --exclude-dir=dist` returns nothing.
- `src/styles/palette.ts` is the only place a vizcore colour is defined; `tokens.css` mirrors it under test.

## What this deliberately does not do

- No component is restyled. Existing components keep their current markup and pick up the new theme through HeroUI. Restyling belongs to the workspace rebuild (sub-project 3).
- No light/dark toggle.
- No changes to the IntelliJ plugin, which has its own JBUI palette until sub-project 5.
