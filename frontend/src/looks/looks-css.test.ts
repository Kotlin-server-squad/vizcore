import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs'
import { join, relative, resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'
import { LOOKS } from './registry'

const looksDir = dirname(fileURLToPath(import.meta.url))
const src = resolve(looksDir, '..')

/** Every look folder that carries CSS, keyed by the look id its palette.json names. */
const folders = readdirSync(looksDir)
  .filter(name => existsSync(join(looksDir, name, 'palette.json')))
  .map(name => {
    const palette = JSON.parse(readFileSync(join(looksDir, name, 'palette.json'), 'utf8')) as {
      id: string
    }
    return { name, id: palette.id, css: readFileSync(join(looksDir, name, 'tokens.css'), 'utf8') }
  })

/** The custom properties declared in the `selector { … }` block of `css`, name → value. */
function declarations(css: string, selector: string): Map<string, string> {
  const start = css.indexOf(`${selector} {`)
  if (start < 0) return new Map()
  const body = css.slice(start, css.indexOf('}', start))
  return new Map([...body.matchAll(/(--[\w-]+):\s*([^;]+);/g)].map(m => [m[1]!, m[2]!.trim()]))
}

/** The root font size a look sets in its `selector { … }` block, or null. */
function rootFontSize(css: string, selector: string): string | null {
  const start = css.indexOf(`${selector} {`)
  if (start < 0) return null
  const body = css.slice(start, css.indexOf('}', start))
  return /(?:^|[\s;{])font-size:\s*([^;]+);/.exec(body)?.[1]?.trim() ?? null
}

/** "H S% L%" → relative luminance (WCAG). */
function luminance(triplet: string): number {
  const [h = 0, s = 0, l = 0] = triplet.split(/\s+/).map(part => parseFloat(part))
  const a = (s / 100) * Math.min(l / 100, 1 - l / 100)
  const channel = (n: number) => {
    const k = (n + h / 30) % 12
    const c = l / 100 - a * Math.max(-1, Math.min(k - 3, 9 - k, 1))
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
  }
  return 0.2126 * channel(0) + 0.7152 * channel(8) + 0.0722 * channel(4)
}

function contrast(a: string, b: string): number {
  const [la, lb] = [luminance(a), luminance(b)]
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)
}

/** Every .css file under src/, except the look folders. */
function cssOutsideLooks(dir = src): string[] {
  return readdirSync(dir).flatMap(name => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return path === looksDir ? [] : cssOutsideLooks(path)
    return path.endsWith('.css') ? [path] : []
  })
}

describe('the looks’ CSS', () => {
  it('gives every trial look in the registry a folder with tokens, and Classic none', () => {
    const trial = LOOKS.filter(look => look.trial).map(look => look.id)
    expect(folders.map(f => f.id).sort()).toEqual([...trial].sort())
  })

  it.each(folders)('$name scopes every rule to its own look', ({ id, css }) => {
    const selectors = css
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .split('}')
      .map(rule => (rule.split('{')[0] ?? '').replace(/@import[^;]+;/g, '').trim())
      .filter(Boolean)
    for (const selector of selectors.flatMap(s => s.split(','))) {
      expect(selector, selector).toContain(`[data-look='${id}']`)
    }
  })

  it.each(folders)('$name sets the same tokens for dark and light', ({ id, css }) => {
    const dark = declarations(css, `html.dark[data-look='${id}']`)
    const light = declarations(css, `html.light[data-look='${id}']`)
    expect(dark.size).toBeGreaterThan(50)
    expect([...light.keys()].sort()).toEqual([...dark.keys()].sort())
    // Everything tokens.css gives Classic, the look overrides.
    for (const name of [
      '--bg',
      '--surface',
      '--border',
      '--text',
      '--text-muted',
      '--primary',
      '--danger',
    ]) {
      expect(dark.has(name), name).toBe(true)
    }
  })

  it.each(folders)(
    '$name keeps text on solid buttons and chips readable (WCAG AA)',
    ({ id, css }) => {
      for (const theme of ['dark', 'light']) {
        const tokens = declarations(css, `html.${theme}[data-look='${id}']`)
        for (const role of ['primary', 'secondary', 'success', 'warning', 'danger']) {
          const fill = tokens.get(`--heroui-${role}`)!
          const text = tokens.get(`--heroui-${role}-foreground`)!
          expect(contrast(fill, text), `${theme} ${role}`).toBeGreaterThanOrEqual(4.5)
        }
      }
    }
  )

  it.each(folders)('$name keeps 12 px between the main view and the dock', ({ id, css }) => {
    // lib/dock-layout.ts sizes the dock with that gap (Tailwind's gap-3, 0.75 rem).
    // A look may pin it to 12px, never change it; a look with another root size must pin it.
    const bodyGaps = [...css.matchAll(/data-look-part='body'\][^{]*\{([^}]*)\}/g)]
      .flatMap(m => [...(m[1] ?? '').matchAll(/\bgap:\s*([^;]+);/g)])
      .map(m => m[1]!.trim())
    expect(
      bodyGaps.every(gap => gap === '12px'),
      bodyGaps.join()
    ).toBe(true)
    const rootSize = rootFontSize(css, `html[data-look='${id}']`)
    if (rootSize !== null && rootSize !== '16px') expect(bodyGaps).toContain('12px')
  })

  it.each(folders)(
    '$name keeps body and secondary text readable on the page and on cards (WCAG AA)',
    ({ id, css }) => {
      for (const theme of ['dark', 'light']) {
        const tokens = declarations(css, `html.${theme}[data-look='${id}']`)
        for (const text of ['--heroui-foreground', '--heroui-default-500']) {
          for (const surface of ['--heroui-background', '--heroui-content1']) {
            const ratio = contrast(tokens.get(text)!, tokens.get(surface)!)
            expect(ratio, `${theme} ${text} on ${surface}`).toBeGreaterThanOrEqual(4.5)
          }
        }
      }
    }
  )

  it('has no look rules outside the look folders, so Classic has none at all', () => {
    const offenders = cssOutsideLooks()
      .filter(path => readFileSync(path, 'utf8').includes('data-look'))
      .map(path => relative(src, path))
    expect(offenders).toEqual([])
  })
})
