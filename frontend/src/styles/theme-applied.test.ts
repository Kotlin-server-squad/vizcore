import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'
import { THEME_STORAGE_KEY } from '../hooks/use-theme'

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
