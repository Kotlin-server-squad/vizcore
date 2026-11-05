# Light Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a light theme next to the dark one, switched by a ☾ / ☀ button in the top bar, with dark the default and the choice remembered.

**Architecture:**
- **Colours:** they already come from one module, `src/styles/palette.ts`. CSS variables in `src/styles/tokens.css` mirror it, and the HeroUI theme in `tailwind.config.ts` is built from it. We add a light set to all three.
- **Switching:** a small `useTheme` hook moves `<html>` between the `dark` and `light` classes. Both Tailwind (`darkMode: 'class'`) and HeroUI key off those classes. An inline script in `index.html` applies the saved choice before React loads.
- **Hard-coded colours:** a guard test then forces the few remaining hard-coded colours onto tokens or HeroUI classes.

**Tech Stack:** React 19, TypeScript, Tailwind 3, HeroUI 2, Vitest with Testing Library (jsdom).

**Spec:** `docs/superpowers/specs/2026-11-05-gallery-how-it-works-design.md`, section "Light mode" (PR 1 of 3).

**Working directory:** worktree `/Users/jirihermann/Documents/workspace-vizcore/vizcore-gallery`.
- Create branch `feat/light-mode` from `docs/gallery-design`, so the spec commit ships with this PR.
- Run all frontend commands from `frontend/`.
- The worktree has no `node_modules`, so run `pnpm install` in `frontend/` before anything else.

**Gates (run before the PR):** from `frontend/`, run `pnpm vitest run`, `pnpm exec tsc --noEmit` and `pnpm lint`.

---

## File map

| File | Change | Responsibility |
|---|---|---|
| `frontend/src/styles/palette.ts` | modify | add `lightPalette` (surfaces and text for light) and `vizColors` (fixed colours that mean something in visualizations) |
| `frontend/src/styles/tokens.css` | modify | add an `html.light { … }` block with the light values |
| `frontend/src/index.css` | modify | `color-scheme` follows the theme |
| `frontend/src/styles/tokens.test.ts` | modify | check `lightPalette` against the `html.light` block |
| `frontend/src/styles/heroui-themes.ts` | create | the HeroUI colour objects for both themes, so they can be tested |
| `frontend/src/styles/heroui-themes.test.ts` | create | the light theme uses the light palette, the dark theme is unchanged |
| `frontend/tailwind.config.ts` | modify | use `heroui-themes.ts` |
| `frontend/src/styles/tailwind-theme.test.ts` | modify | its import assertion follows the move to `heroui-themes.ts` |
| `frontend/src/lib/export-theme.ts` | create | exports stay dark in light mode |
| `frontend/src/lib/export-theme.test.ts` | create | its tests |
| `frontend/src/lib/export-png.ts`, `export-svg.ts`, `record-replay.ts` | modify | use `export-theme.ts` |
| `frontend/src/hooks/use-theme.ts` | create | read, apply and save the theme |
| `frontend/src/hooks/use-theme.test.ts` | create | hook behaviour |
| `frontend/src/components/ThemeSwitch.tsx` | create | the ☾ / ☀ button |
| `frontend/src/components/ThemeSwitch.test.tsx` | create | the button's label and toggling |
| `frontend/src/components/Layout.tsx` | modify | put `ThemeSwitch` at the right of the navbar |
| `frontend/src/components/Layout.test.tsx` | modify | the navbar has the switch |
| `frontend/index.html` | modify | the inline script that applies the saved theme before first paint |
| `frontend/src/styles/theme-applied.test.ts` | modify | run that script against a fake `localStorage` and `document` |
| `frontend/src/styles/no-hardcoded-colours.test.ts` | create | the guard: no gray-family Tailwind classes and no raw hex colours in `.tsx` outside `src/styles/` |
| 7 components (Task 7) | modify | move hard-coded colours to HeroUI classes or `vizColors` |
| `CHANGELOG.md` | modify | an Unreleased entry |

---

### Task 1: The light palette and CSS variables

**Files:**
- Modify: `frontend/src/styles/palette.ts`
- Modify: `frontend/src/styles/tokens.css`
- Modify: `frontend/src/index.css` (the `color-scheme: dark;` line inside `:root`)
- Test: `frontend/src/styles/tokens.test.ts`

- [ ] **Step 1: Write the failing test.** Add to `tokens.test.ts`, changing the import to `import { palette, lightPalette } from './palette'`:

```ts
/** The text of the first `selector { … }` block in `source`, or '' if there is none. */
function block(source: string, selector: string): string {
  const start = source.indexOf(`${selector} {`)
  if (start < 0) return ''
  return source.slice(start, source.indexOf('}', start))
}

describe('light tokens', () => {
  const light = block(css, 'html.light')

  it('declares every light palette colour in the html.light block', () => {
    expect(light, 'tokens.css has no html.light block').not.toBe('')
    for (const [name, value] of Object.entries(lightPalette)) {
      expect(light, `missing or mismatched --${name} in html.light`).toContain(`--${name}: ${value};`)
    }
  })

  it('overrides exactly the surface and text colours; semantic colours are shared', () => {
    expect(Object.keys(lightPalette).sort()).toEqual(
      ['bg', 'border', 'border-2', 'surface', 'surface-2', 'surface-3', 'text', 'text-faint', 'text-muted'].sort(),
    )
  })

  it('switches the browser colour scheme with the theme', () => {
    const indexCss = readFileSync(resolve(here, '../index.css'), 'utf8')
    expect(block(indexCss, 'html.light')).toContain('color-scheme: light;')
  })
})
```

- [ ] **Step 2: Run the test and see it fail.**
Run: `pnpm vitest run src/styles/tokens.test.ts`
Expected: FAIL. The import of `lightPalette` is undefined or does not exist.

- [ ] **Step 3: Add `lightPalette` and `vizColors`** at the end of `palette.ts`, above `export type PaletteKey`:

```ts
/**
 * The light theme's surfaces and text. Semantic colours (primary, success,
 * warning, danger, created) are shared with the dark theme, so a state reads
 * the same in both. Applied by the `html.light` block in `tokens.css`.
 */
export const lightPalette = {
  bg: '#fafafa',
  surface: '#ffffff',
  'surface-2': '#f4f4f5',
  'surface-3': '#e4e4e7',
  border: '#e4e4e7',
  'border-2': '#d4d4d8',
  text: '#18181b',
  'text-muted': '#52525b',
  'text-faint': '#71717a',
} as const

/**
 * Fixed colours that carry meaning inside a visualization, the same in both
 * themes. They are not CSS variables, so `tokens.test.ts` does not look for them.
 */
export const vizColors = {
  // flow operators (FlowParticlePath)
  operatorTransform: '#6366f1',
  operatorFilter: '#f59e0b',
  operatorTerminal: '#10b981',
  operatorCombine: '#a855f7',
  operatorSource: '#94a3b8',
  particleFiltered: '#f59e0b',
  particleDefault: '#6366f1',
  // deadlock graph (DeadlockVisualization)
  deadlockEdge: '#ef4444',
  deadlockCoroutineFill: '#fecaca',
  deadlockCoroutineStroke: '#ef4444',
  deadlockLockFill: '#fde68a',
  deadlockLockStroke: '#f59e0b',
} as const
```

- [ ] **Step 4: Add the light block** at the end of `tokens.css`:

```css
/* Light theme: surfaces and text only; semantic colours are shared. */
html.light {
  --bg: #fafafa;
  --surface: #ffffff;
  --surface-2: #f4f4f5;
  --surface-3: #e4e4e7;
  --border: #e4e4e7;
  --border-2: #d4d4d8;
  --text: #18181b;
  --text-muted: #52525b;
  --text-faint: #71717a;
  --shadow: 0 8px 30px rgba(0, 0, 0, 0.08);
}
```

Then add this after the `:root { … }` block in `index.css`:

```css
html.light {
  color-scheme: light;
}
```

- [ ] **Step 5: Run the test and see it pass.**
Run: `pnpm vitest run src/styles/tokens.test.ts`
Expected: PASS, 5 tests.

- [ ] **Step 6: Commit.**

```bash
git add frontend/src/styles/palette.ts frontend/src/styles/tokens.css frontend/src/index.css frontend/src/styles/tokens.test.ts
git commit -m "feat(frontend): light palette and CSS variables"
```

---

### Task 2: The HeroUI light theme

**Files:**
- Create: `frontend/src/styles/heroui-themes.ts`
- Create: `frontend/src/styles/heroui-themes.test.ts`
- Modify: `frontend/tailwind.config.ts` (the `semantic` object and the `heroui({ themes })` call)
- Modify: `frontend/src/styles/tailwind-theme.test.ts` (line 28)

- [ ] **Step 1: Write the failing test** in `heroui-themes.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { darkThemeColors, lightThemeColors } from './heroui-themes'
import { palette, lightPalette } from './palette'

describe('HeroUI themes', () => {
  it('builds the light theme from the light palette', () => {
    expect(lightThemeColors).toMatchObject({
      background: lightPalette.bg,
      foreground: lightPalette.text,
      content1: lightPalette.surface,
      content2: lightPalette['surface-2'],
      content3: lightPalette['surface-3'],
      divider: lightPalette.border,
    })
  })

  it('keeps the dark theme as it was', () => {
    expect(darkThemeColors).toMatchObject({
      background: palette.bg,
      foreground: palette.text,
      content1: palette.surface,
      content2: palette['surface-2'],
      content3: palette['surface-3'],
      divider: palette.border,
    })
  })

  it('shares the semantic colours between the themes', () => {
    for (const key of ['primary', 'success', 'warning', 'danger', 'focus'] as const) {
      expect(lightThemeColors[key]).toEqual(darkThemeColors[key])
    }
    expect(lightThemeColors.primary).toEqual({ DEFAULT: palette.primary, foreground: '#ffffff' })
  })
})
```

- [ ] **Step 2: Run the test and see it fail.**
Run: `pnpm vitest run src/styles/heroui-themes.test.ts`
Expected: FAIL. It cannot resolve `./heroui-themes`.

- [ ] **Step 3: Create `heroui-themes.ts`.** Move the `semantic` object and its NOTE comment from `tailwind.config.ts` verbatim:

```ts
import { palette, lightPalette } from './palette'

/** Semantic colours, shared by both themes so a state reads the same in each. */
const semantic = {
  primary: { DEFAULT: palette.primary, foreground: '#ffffff' },
  // NOTE: `secondary` is deliberately left at the HeroUI default. vizcore's
  // palette has no secondary hue, but `secondary` is load-bearing across ~15
  // components (79 references) and `coroutine-state-colors` maps
  // WAITING_FOR_CHILDREN onto it — neutralising it here silently flattens that
  // state into the same grey as CREATED and CANCELLED. Retiring `secondary`
  // is a component-level decision, not a token-level one; see sub-project 3.
  success: { DEFAULT: palette.success, foreground: '#00110a' },
  warning: { DEFAULT: palette.warning, foreground: '#1a1200' },
  danger: { DEFAULT: palette.danger, foreground: '#ffffff' },
  focus: palette.primary,
}

export const darkThemeColors = {
  ...semantic,
  background: palette.bg,
  foreground: palette.text,
  content1: palette.surface,
  content2: palette['surface-2'],
  content3: palette['surface-3'],
  divider: palette.border,
}

export const lightThemeColors = {
  ...semantic,
  background: lightPalette.bg,
  foreground: lightPalette.text,
  content1: lightPalette.surface,
  content2: lightPalette['surface-2'],
  content3: lightPalette['surface-3'],
  divider: lightPalette.border,
}
```

- [ ] **Step 4: Use the new module in `tailwind.config.ts`.**
  - Delete the `semantic` object and the `palette` import.
  - Add `import { darkThemeColors, lightThemeColors } from './src/styles/heroui-themes'`.
  - Change the themes to `themes: { light: { colors: lightThemeColors }, dark: { colors: darkThemeColors } }`.
  - Replace the comment above it with:

```ts
/**
 * Both HeroUI themes are real: dark is the default (the `dark` class on
 * <html>), light is chosen with the switch in the top bar (`useTheme`).
 * Colours come from `src/styles/heroui-themes.ts`.
 */
```

- [ ] **Step 5: Update the existing source assertion and run the tests and the type check.**
`tailwind-theme.test.ts` line 28 asserts that `tailwind.config.ts` contains `from './src/styles/palette'`, and Step 4 removed that import.
  - Change the assertion to `expect(source).toContain("from './src/styles/heroui-themes'")`.
  - Add an assertion that `heroui-themes.ts`, read the same way, contains `from './palette'`.

Run: `pnpm vitest run src/styles/ && pnpm exec tsc --noEmit`
Expected: the tests PASS and tsc reports no errors.

- [ ] **Step 6: Check that the CSS still builds.**
Run: `pnpm build`
Expected: the build succeeds.

- [ ] **Step 7: Commit.**

```bash
git add frontend/src/styles/heroui-themes.ts frontend/src/styles/heroui-themes.test.ts frontend/src/styles/tailwind-theme.test.ts frontend/tailwind.config.ts
git commit -m "feat(frontend): HeroUI light theme from the light palette"
```

---

### Task 3: The `useTheme` hook

**Files:**
- Create: `frontend/src/hooks/use-theme.ts`
- Test: `frontend/src/hooks/use-theme.test.ts`

- [ ] **Step 1: Write the failing test:**

```ts
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { THEME_STORAGE_KEY, readStoredTheme, useTheme } from './use-theme'

const html = () => document.documentElement

beforeEach(() => {
  localStorage.clear()
  html().className = 'dark'
  vi.restoreAllMocks()
})

describe('useTheme', () => {
  it('defaults to dark', () => {
    const { result } = renderHook(() => useTheme())
    expect(result.current.theme).toBe('dark')
    expect(html().classList.contains('dark')).toBe(true)
    expect(html().classList.contains('light')).toBe(false)
  })

  it('switches to light, puts the light class on <html> and remembers it', () => {
    const { result } = renderHook(() => useTheme())
    act(() => result.current.toggle())
    expect(result.current.theme).toBe('light')
    expect(html().classList.contains('light')).toBe(true)
    expect(html().classList.contains('dark')).toBe(false)
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('light')
  })

  it('starts from the saved choice', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'light')
    const { result } = renderHook(() => useTheme())
    expect(result.current.theme).toBe('light')
  })

  it('treats an unknown saved value as dark', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'purple')
    expect(readStoredTheme()).toBe('dark')
  })

  it('still works when storage is unavailable', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    const { result } = renderHook(() => useTheme())
    expect(result.current.theme).toBe('dark')
    act(() => result.current.toggle())
    expect(html().classList.contains('light')).toBe(true)
  })
})
```

- [ ] **Step 2: Run the test and see it fail.**
Run: `pnpm vitest run src/hooks/use-theme.test.ts`
Expected: FAIL. It cannot resolve `./use-theme`.

- [ ] **Step 3: Implement it:**

```ts
import { useCallback, useEffect, useState } from 'react'

export type Theme = 'dark' | 'light'

/** Also read by the inline script in index.html; keep the two in step. */
export const THEME_STORAGE_KEY = 'vizcore-theme'

/** The saved theme; dark when nothing (or nothing valid) is saved, or storage is blocked. */
export function readStoredTheme(): Theme {
  try {
    return localStorage.getItem(THEME_STORAGE_KEY) === 'light' ? 'light' : 'dark'
  } catch {
    return 'dark'
  }
}

function applyTheme(theme: Theme) {
  const root = document.documentElement
  root.classList.remove('dark', 'light')
  root.classList.add(theme)
}

/** The current theme and a toggle; the choice is applied to <html> and saved. */
export function useTheme() {
  const [theme, setTheme] = useState<Theme>(readStoredTheme)

  useEffect(() => {
    applyTheme(theme)
    try {
      localStorage.setItem(THEME_STORAGE_KEY, theme)
    } catch {
      // Storage blocked (private mode, policy): the theme still applies for this visit.
    }
  }, [theme])

  const toggle = useCallback(() => setTheme((t) => (t === 'dark' ? 'light' : 'dark')), [])
  return { theme, toggle }
}
```

- [ ] **Step 4: Run the test and see it pass.**
Run: `pnpm vitest run src/hooks/use-theme.test.ts`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit.**

```bash
git add frontend/src/hooks/use-theme.ts frontend/src/hooks/use-theme.test.ts
git commit -m "feat(frontend): useTheme switches and remembers dark or light"
```

---

### Task 4: The switch in the top bar

**Files:**
- Create: `frontend/src/components/ThemeSwitch.tsx`
- Create: `frontend/src/components/ThemeSwitch.test.tsx`
- Modify: `frontend/src/components/Layout.tsx`
- Modify: `frontend/src/components/Layout.test.tsx`

- [ ] **Step 1: Write the failing tests.** In `ThemeSwitch.test.tsx`:

```tsx
import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeSwitch } from './ThemeSwitch'

beforeEach(() => {
  localStorage.clear()
  document.documentElement.className = 'dark'
})

describe('ThemeSwitch', () => {
  it('offers the light theme while dark is on', () => {
    render(<ThemeSwitch />)
    expect(screen.getByRole('button', { name: 'Switch to light theme' })).toBeInTheDocument()
  })

  it('switches the page to light and then offers dark', async () => {
    render(<ThemeSwitch />)
    await userEvent.click(screen.getByRole('button', { name: 'Switch to light theme' }))
    expect(document.documentElement.classList.contains('light')).toBe(true)
    expect(screen.getByRole('button', { name: 'Switch to dark theme' })).toBeInTheDocument()
  })
})
```

Then add to `Layout.test.tsx`, inside `describe('Layout nav', …)`:

```tsx
  it('has the theme switch', () => {
    renderLayout()
    expect(screen.getByRole('button', { name: /switch to (light|dark) theme/i })).toBeInTheDocument()
  })
```

Check that `@testing-library/user-event` is installed with `grep user-event package.json`. If it isn't, use `fireEvent.click` from `@testing-library/react` instead, and drop the `await`.

- [ ] **Step 2: Run the tests and see them fail.**
Run: `pnpm vitest run src/components/ThemeSwitch.test.tsx src/components/Layout.test.tsx`
Expected: FAIL. It cannot resolve `./ThemeSwitch`, and Layout has no switch button.

- [ ] **Step 3: Implement `ThemeSwitch.tsx`:**

```tsx
import { Button } from '@heroui/react'
import { FiMoon, FiSun } from 'react-icons/fi'
import { useTheme } from '@/hooks/use-theme'

/** ☾ / ☀ in the top bar: shows the theme you would switch to. */
export function ThemeSwitch() {
  const { theme, toggle } = useTheme()
  const next = theme === 'dark' ? 'light' : 'dark'
  return (
    <Button isIconOnly variant="light" aria-label={`Switch to ${next} theme`} onPress={toggle}>
      {next === 'light' ? <FiSun className="h-5 w-5" /> : <FiMoon className="h-5 w-5" />}
    </Button>
  )
}
```

- [ ] **Step 4: Put it in `Layout.tsx`.**
  - Import `NavbarContent` and `NavbarItem` from `@heroui/react`, and `ThemeSwitch` from `./ThemeSwitch`.
  - After `</NavbarBrand>`, add:

```tsx
        <NavbarContent justify="end">
          <NavbarItem>
            <ThemeSwitch />
          </NavbarItem>
        </NavbarContent>
```

  - Leave the doc comment about D-1/D-2/D-3 as it is. It changes in PR 2, when the Sessions and Gallery links arrive.

- [ ] **Step 5: Run the tests and see them pass.**
Run: `pnpm vitest run src/components/ThemeSwitch.test.tsx src/components/Layout.test.tsx`
Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
git add frontend/src/components/ThemeSwitch.tsx frontend/src/components/ThemeSwitch.test.tsx frontend/src/components/Layout.tsx frontend/src/components/Layout.test.tsx
git commit -m "feat(frontend): theme switch in the top bar"
```

---

### Task 5: No flash on load

Without this, a reader who chose light sees one dark frame on every load, until React runs `useTheme`.

**Files:**
- Modify: `frontend/index.html` (the `<head>`)
- Test: `frontend/src/styles/theme-applied.test.ts`

- [ ] **Step 1: Write the failing test.** Add to `theme-applied.test.ts`:

```ts
import { THEME_STORAGE_KEY } from '../hooks/use-theme'

/** The inline script that applies the saved theme, found by its marker comment. */
function themeScript(): string {
  const body = html.match(/<script>\s*\/\* vizcore-theme \*\/([\s\S]*?)<\/script>/)?.[1]
  if (body === undefined) throw new Error('index.html has no inline theme script')
  return body
}

/** Runs the script against a fake <html> class list and storage; returns the final classes. */
function runWith(saved: string | null, storageThrows = false): string[] {
  const classes = new Set(['dark'])
  const document = {
    documentElement: {
      classList: {
        remove: (...c: string[]) => c.forEach((x) => classes.delete(x)),
        add: (...c: string[]) => c.forEach((x) => classes.add(x)),
      },
    },
  }
  const localStorage = {
    getItem: (key: string) => {
      if (storageThrows) throw new Error('blocked')
      return key === THEME_STORAGE_KEY ? saved : null
    },
  }
  new Function('document', 'localStorage', themeScript())(document, localStorage)
  return [...classes]
}

describe('theme before first paint', () => {
  it('applies a saved light theme', () => {
    expect(runWith('light')).toEqual(['light'])
  })

  it('stays dark with nothing saved, an unknown value, or blocked storage', () => {
    expect(runWith(null)).toEqual(['dark'])
    expect(runWith('purple')).toEqual(['dark'])
    expect(runWith('light', true)).toEqual(['dark'])
  })

  it('reads the same storage key as useTheme', () => {
    expect(themeScript()).toContain(`'${THEME_STORAGE_KEY}'`)
  })
})
```

- [ ] **Step 2: Run the test and see it fail.**
Run: `pnpm vitest run src/styles/theme-applied.test.ts`
Expected: FAIL with "index.html has no inline theme script".

- [ ] **Step 3: Add the script** to `index.html`, right after `<meta name="theme-color" … />`:

```html
    <script>
      /* vizcore-theme */
      try {
        if (localStorage.getItem('vizcore-theme') === 'light') {
          document.documentElement.classList.remove('dark')
          document.documentElement.classList.add('light')
        }
      } catch (e) {
        /* storage blocked: stay dark */
      }
    </script>
```

Keep `class="dark"` on `<html>`. The existing tests in this file still need it, since dark stays the default.

- [ ] **Step 4: Run the test and see it pass.**
Run: `pnpm vitest run src/styles/theme-applied.test.ts`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit.**

```bash
git add frontend/index.html frontend/src/styles/theme-applied.test.ts
git commit -m "feat(frontend): apply the saved theme before first paint"
```

---

### Task 6: The colour guard (red)

**Files:**
- Create: `frontend/src/styles/no-hardcoded-colours.test.ts`

- [ ] **Step 1: Write the guard:**

```ts
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative, resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'

const src = resolve(dirname(fileURLToPath(import.meta.url)), '..')

/** Every .tsx file under src/, excluding tests and src/styles/. */
function componentFiles(dir = src): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return path.endsWith('/styles') ? [] : componentFiles(path)
    return path.endsWith('.tsx') && !path.includes('.test.') ? [path] : []
  })
}

/** Drops // and /* *\/ comments so issue references like #139 are never read as colours. */
function withoutComments(code: string): string {
  return code.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:'"`])\/\/.*$/gm, '$1')
}

const GRAY_CLASS = /\b(?:[a-z]+:)*(?:bg|text|border|from|via|to|ring|fill|stroke|divide)-(?:gray|zinc|slate|neutral|stone)-\d{2,3}\b/g
const HEX_COLOUR = /(?<=['"`:=]\s*)#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3,4})\b/g

function offenders(pattern: RegExp): string[] {
  return componentFiles().flatMap((file) => {
    const found = withoutComments(readFileSync(file, 'utf8')).match(pattern) ?? []
    return found.map((hit) => `${relative(src, file)}: ${hit}`)
  })
}

describe('colours come from the theme', () => {
  it('uses no fixed gray Tailwind classes; they do not follow the theme', () => {
    expect(offenders(GRAY_CLASS)).toEqual([])
  })

  it('uses no raw hex colours outside src/styles/', () => {
    expect(offenders(HEX_COLOUR)).toEqual([])
  })

  it('does not mistake an issue reference for a colour', () => {
    expect(withoutComments('// see: #139\nconst a = 1').match(HEX_COLOUR)).toBeNull()
    expect("fill='#ef4444'".match(HEX_COLOUR)).toEqual(['#ef4444'])
  })
})
```

- [ ] **Step 2: Run it and see the first two tests fail.**
Run: `pnpm vitest run src/styles/no-hardcoded-colours.test.ts`
Expected:
  - The first two tests FAIL, listing the offenders. That should be 74 gray classes in 5 files (EnhancedCoroutineTreeNode, JobInfoCard, JobStatusDisplay, ScenarioBuilder, WaitingIndicator) and 14 hex colours in FlowParticlePath and DeadlockVisualization.
  - The third test PASSES.
  - If the list names files other than these, include them in Task 7 too.

- [ ] **Step 3: Commit the red guard.**

```bash
git add frontend/src/styles/no-hardcoded-colours.test.ts
git commit -m "test(frontend): guard against colours that ignore the theme (red)"
```

---

### Task 7: Move the hard-coded colours to the theme (green)

**Files:**
- Modify: `frontend/src/components/JobInfoCard.tsx`
- Modify: `frontend/src/components/EnhancedCoroutineTreeNode.tsx`
- Modify: `frontend/src/components/JobStatusDisplay.tsx`
- Modify: `frontend/src/components/WaitingIndicator.tsx`
- Modify: `frontend/src/components/ScenarioBuilder.tsx`
- Modify: `frontend/src/components/flow/FlowParticlePath.tsx`
- Modify: `frontend/src/components/sync/DeadlockVisualization.tsx`

Replace each light/dark pair with one HeroUI semantic class, which follows the theme by itself:

| Today | Becomes |
|---|---|
| `text-gray-900 dark:text-gray-100` | `text-foreground` |
| `text-gray-500/600/700 dark:text-gray-300/400`, `text-gray-400`, `text-gray-600` | `text-default-500` (`text-default-600` where it was a 700) |
| `text-gray-400 dark:text-gray-600` (dimmed) | `text-default-400` |
| `bg-white dark:bg-gray-800`, `bg-white` | `bg-content1` |
| `bg-gray-50` | `bg-content2` |
| `border-gray-200 dark:border-gray-700` | `border-divider` |
| `hover:border-gray-300 dark:hover:border-gray-600` | `hover:border-default-300` |
| `bg-gray-100 … dark:bg-gray-700/800` (chips) | `bg-default-100` |
| `bg-gray-200 dark:bg-gray-700` (bars, neutral badges) | `bg-default-200` |
| `bg-gray-300 dark:bg-gray-600` (connector line) | `bg-default-300` |
| `bg-gray-900 text-white dark:bg-gray-100 dark:text-gray-900` (tooltip) | `bg-foreground text-background` |
| ScenarioBuilder `bg-blue-500/600 text-white hover:bg-blue-600/700` | `bg-primary text-primary-foreground hover:opacity-90` |
| ScenarioBuilder `bg-green-500 text-white hover:bg-green-600` | `bg-success text-success-foreground hover:opacity-90` |
| ScenarioBuilder `bg-gray-600 text-white hover:bg-gray-700` | `bg-default-500 text-white hover:opacity-90` |
| ScenarioBuilder `bg-gray-300 text-gray-700 hover:bg-gray-400` | `bg-default-200 text-foreground hover:bg-default-300` |
| `disabled:bg-gray-400` | `disabled:bg-default-300` |
| anything else in the gray family, e.g. `dark:text-gray-500`, `hover:bg-gray-700`, `dark:bg-gray-600` | the nearest row above; one `default-*` class with no `dark:` pair |

For the hex colours, import `vizColors` from `@/styles/palette` and replace each literal with its key:

- **FlowParticlePath:**
  - `#6366f1` → `vizColors.operatorTransform`
  - `#f59e0b` → `vizColors.operatorFilter`
  - `#10b981` → `vizColors.operatorTerminal`
  - `#a855f7` → `vizColors.operatorCombine`
  - `#94a3b8` → `vizColors.operatorSource`
  - the filtered particle's `fill="#f59e0b"` (line ~136) → `vizColors.particleFiltered`
  - the default particle's `fill="#6366f1"` (line ~164) → `vizColors.particleDefault`
- **DeadlockVisualization:**
  - arrow `#ef4444` → `vizColors.deadlockEdge`
  - coroutine fill `#fecaca` → `vizColors.deadlockCoroutineFill`
  - coroutine stroke `#ef4444` → `vizColors.deadlockCoroutineStroke`
  - lock fill `#fde68a` → `vizColors.deadlockLockFill`
  - lock stroke `#f59e0b` → `vizColors.deadlockLockStroke`
- JSX attributes become expressions, e.g. `fill={vizColors.deadlockEdge}`. Keep the existing trailing comments such as `// indigo`.

- [ ] **Step 1: Convert JobInfoCard, EnhancedCoroutineTreeNode, JobStatusDisplay and WaitingIndicator** using the table. Then run their tests and the guard:
Run: `pnpm vitest run src/components/JobInfoCard src/components/EnhancedCoroutineTreeNode src/components/JobStatusDisplay src/components/WaitingIndicator src/styles/no-hardcoded-colours.test.ts`
Expected:
  - The component tests PASS. If one asserts an old class name, update it to the new class.
  - The guard's gray list now names only ScenarioBuilder.

- [ ] **Step 2: Convert ScenarioBuilder** using the table.
Run: `pnpm vitest run src/components/ScenarioBuilder src/styles/no-hardcoded-colours.test.ts`
Expected: the gray test in the guard PASSES.

- [ ] **Step 3: Move the hex colours** in FlowParticlePath and DeadlockVisualization to `vizColors`.
Run: `pnpm vitest run src/components/flow src/components/sync src/styles/no-hardcoded-colours.test.ts`
Expected: all PASS, including the hex test in the guard.

- [ ] **Step 4: Run the whole suite and the gates.**
Run: `pnpm vitest run && pnpm exec tsc --noEmit && pnpm lint`
Expected: all pass. Lint shows 0 errors; the 5 existing warnings are fine.

- [ ] **Step 5: Commit.**

```bash
git add frontend/src/components
git commit -m "feat(frontend): components take their colours from the theme"
```

---

### Task 8: Exports stay dark

The spec keeps exported PNG and SVG, and the recorded replay video, in today's dark look. Each of
these reads the live, themed page:

- `export-svg.ts` (`inlineStyles`) copies `getComputedStyle` values into the file;
- `export-png.ts` (line ~75) and `record-replay.ts` (line ~200) render the live DOM through
  html2canvas.

In light mode both would produce a light or mixed picture.

**Files:**
- Create: `frontend/src/lib/export-theme.ts`
- Test: `frontend/src/lib/export-theme.test.ts`
- Modify: `frontend/src/lib/export-svg.ts`, `frontend/src/lib/export-png.ts`, `frontend/src/lib/record-replay.ts`
- Test: the existing `export-png.test.ts` / `export-svg.test.ts` (and `record-replay` test if one mocks html2canvas)

- [ ] **Step 1: Write the failing test** in `export-theme.test.ts`:

```ts
import { describe, it, expect, beforeEach } from 'vitest'
import { asDarkClone, withDarkTheme } from './export-theme'

const root = () => document.documentElement

beforeEach(() => {
  root().className = 'light'
})

describe('export theme', () => {
  it('reads styles as dark while the export runs, then restores light', () => {
    let during = ''
    withDarkTheme(() => {
      during = root().className
    })
    expect(during).toBe('dark')
    expect(root().className).toBe('light')
  })

  it('restores light even when the export throws', () => {
    expect(() =>
      withDarkTheme(() => {
        throw new Error('boom')
      }),
    ).toThrow('boom')
    expect(root().className).toBe('light')
  })

  it('leaves dark alone', () => {
    root().className = 'dark'
    expect(withDarkTheme(() => root().className)).toBe('dark')
    expect(root().className).toBe('dark')
  })

  it('turns an html2canvas clone dark', () => {
    const clone = document.implementation.createHTMLDocument('clone')
    clone.documentElement.className = 'light'
    asDarkClone(clone)
    expect(clone.documentElement.classList.contains('dark')).toBe(true)
    expect(clone.documentElement.classList.contains('light')).toBe(false)
  })
})
```

- [ ] **Step 2: Run the test and see it fail.**
Run: `pnpm vitest run src/lib/export-theme.test.ts`
Expected: FAIL. It cannot resolve `./export-theme`.

- [ ] **Step 3: Implement it:**

```ts
/**
 * Exports keep the dark look in both themes (spec: "Exported PNG and SVG keep
 * today's dark look").
 */

/**
 * Runs `read` with <html> switched to dark. The switch and the restore happen
 * in one synchronous task, so the browser never paints the dark state.
 */
export function withDarkTheme<T>(read: () => T): T {
  const root = document.documentElement
  if (!root.classList.contains('light')) return read()
  root.classList.replace('light', 'dark')
  try {
    return read()
  } finally {
    root.classList.replace('dark', 'light')
  }
}

/** html2canvas `onclone` hook: renders the clone in the dark theme. */
export function asDarkClone(doc: Document): void {
  doc.documentElement.classList.remove('light')
  doc.documentElement.classList.add('dark')
}
```

- [ ] **Step 4: Run the test and see it pass.**
Run: `pnpm vitest run src/lib/export-theme.test.ts`
Expected: PASS, 4 tests.

- [ ] **Step 5: Use it.**
  - **export-svg.ts:** wrap the `inlineStyles(svg, clone)` call in `exportToSvg` as `withDarkTheme(() => inlineStyles(svg, clone))`.
  - **export-png.ts and record-replay.ts:** add `onclone: asDarkClone` to both html2canvas option objects, and also run the call inside `withDarkTheme`:
    ```ts
    const canvas = await withDarkTheme(() => html2canvas(el, { …, onclone: asDarkClone }))
    ```
    Both are needed. html2canvas 1.4.1 copies the live computed styles inline onto every cloned SVG element while it clones, before `onclone` runs, and inline styles beat the theme's CSS rules. It clones synchronously, before its first `await`, so `withDarkTheme` makes that copy read dark styles and restores `light` within the same task, with no visible flash.
  - **Tests:** extend the existing export tests (they mock html2canvas) to check that the options include `onclone: asDarkClone`, and that `<html>` had the `dark` class when html2canvas was called. Record the class inside the mock. `export-png.test.ts:55` checks the options with an exact `toHaveBeenCalledWith(mockElement, {…})`, so it will fail until it gets `onclone`. For SVG, check that the exported file carries the dark text colour while `<html>` has the `light` class. If jsdom's `getComputedStyle` cannot resolve the theme's CSS, assert that `inlineStyles` ran while the class was `dark`, using a spy on `getComputedStyle` that records `document.documentElement.className`.

Run: `pnpm vitest run src/lib/`
Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
git add frontend/src/lib/export-theme.ts frontend/src/lib/export-theme.test.ts frontend/src/lib/export-svg.ts frontend/src/lib/export-png.ts frontend/src/lib/record-replay.ts frontend/src/lib/*.test.ts
git commit -m "feat(frontend): exports keep the dark look in light mode"
```

---

### Task 9: Check in the browser, changelog, PR

- [ ] **Step 1: Run the app.**
  - From `backend/`, start the backend on a free port, e.g. `PORT=18291 CORS_ALLOWED_ORIGINS=http://localhost:13291 ./gradlew :run`. Set `JAVA_HOME=/Users/jirihermann/Library/Java/JavaVirtualMachines/azul-21/Contents/Home`.
  - From `frontend/`, start the frontend with `VITE_PROXY_TARGET=http://localhost:18291 pnpm dev --port 13291`.
  - Avoid ports 3000, 5173, 8080, 13001 and 18081, which are in use.

- [ ] **Step 2: Look at both themes.** In each theme, open:
  - the sessions home;
  - the New demo session dialog;
  - a running Basics demo (the tree, graph, timeline and threads views);
  - `/scenarios/builder`, if it is still reachable;
  - a shared link page, if one is available;
  - a PNG export, an SVG export and a recorded replay taken in light mode, all of which must come
    out dark;
  - the places that still use `bg-white`, `bg-black` or `text-black`, which the guard does not
    cover: SkipNavigation, CoroutineTimelineView, ThreadLanesView, ChannelTimeline and
    ExceptionPropagationOverlay.

  Check that:
  - the text is readable;
  - no panel stays dark in light mode, or light in dark mode;
  - the switch survives a reload without a dark flash;
  - the state colours read the same in both themes.

  Take one screenshot per theme of a running demo for the PR description. Write down anything that looks wrong and fix it in this PR if it's small. Otherwise list it in the PR under "Known gaps".

- [ ] **Step 3: Add a CHANGELOG entry.** Under `## [Unreleased]` → `### Added` in `CHANGELOG.md`:

```markdown
- Light mode: a ☾ / ☀ switch in the top bar changes between the dark and the new light theme;
  dark stays the default and the choice is remembered in the browser
```

- [ ] **Step 4: Run all the gates one last time.**
Run (from `frontend/`): `pnpm vitest run && pnpm exec tsc --noEmit && pnpm lint && pnpm build`
Expected: all pass.

- [ ] **Step 5: Commit and push.**

```bash
git add CHANGELOG.md
git commit -m "docs: changelog for light mode"
git push -u origin feat/light-mode
```

- [ ] **Step 6: Open the PR.** Title: `feat(frontend): light mode with a switch in the top bar`. The body links the spec and lists the changes, the tests, the browser check with both screenshots, and any known gaps. End the body with the attribution line given in the session.

- [ ] **Step 7: Review rounds.** Run up to 3 Codex review rounds:
`codex exec -s read-only -C <worktree> -o <scratchpad>/codex-cr-light-mode-roundN.md "<prompt>" < /dev/null`

Fix each real finding with a test, push, wait for CI to pass, and update the PR description through the REST API. `gh pr edit` fails on this repo.
