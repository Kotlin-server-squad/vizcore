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
