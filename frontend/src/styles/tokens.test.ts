import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'
import { palette, lightPalette } from './palette'

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

  it('overrides the surfaces, the text and the status colours; primary is shared', () => {
    expect(Object.keys(lightPalette).sort()).toEqual(
      [
        'bg', 'border', 'border-2', 'surface', 'surface-2', 'surface-3', 'text', 'text-faint', 'text-muted',
        'success', 'warning', 'danger',
      ].sort(),
    )
  })

  it('switches the browser colour scheme with the theme', () => {
    const indexCss = readFileSync(resolve(here, '../index.css'), 'utf8')
    expect(block(indexCss, 'html.light')).toContain('color-scheme: light;')
  })
})
