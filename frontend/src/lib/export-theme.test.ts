import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it, expect, beforeEach } from 'vitest'
import { NO_TRANSITIONS_CLASS, asDarkClone, withDarkTheme } from './export-theme'

const root = () => document.documentElement

beforeEach(() => {
  root().className = 'light'
})

describe('export theme', () => {
  it('reads styles as dark while the export runs, then restores light', () => {
    let during: string[] = []
    withDarkTheme(() => {
      during = [...root().classList]
    })
    expect(during).toContain('dark')
    expect(during).not.toContain('light')
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

  it('turns colour transitions off for the swap, so styles read as dark at once', () => {
    let during: string[] = []
    withDarkTheme(() => {
      during = [...root().classList]
    })
    expect(during).toEqual(expect.arrayContaining(['dark', NO_TRANSITIONS_CLASS]))
    // Off until the light styles are back too, then on again.
    expect([...root().classList]).toEqual(['light'])
  })

  it('has a CSS rule that turns transitions off under that class', () => {
    const here = dirname(fileURLToPath(import.meta.url))
    const css = readFileSync(resolve(here, '../index.css'), 'utf8')
    expect(css).toContain(`html.${NO_TRANSITIONS_CLASS} *`)
    expect(css).toMatch(/transition:\s*none\s*!important/)
  })
})
