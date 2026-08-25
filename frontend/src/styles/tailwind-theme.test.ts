import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'
import { palette } from './palette'
import config from '../../tailwind.config'

describe('tailwind/heroui theme', () => {
  it('exposes the vizcore font families to tailwind utilities', () => {
    const fontFamily = config.theme?.extend?.fontFamily as {
      sans: string[]
      mono: string[]
    }
    expect(fontFamily.sans[0]).toBe('Inter')
    expect(fontFamily.mono[0]).toBe('JetBrains Mono')
  })

  it('keeps darkMode class-based so the <html class="dark"> switch applies', () => {
    expect(config.darkMode).toBe('class')
  })

  it('does not reference the placeholder indigo anywhere', () => {
    // Read the file rather than the loaded object: HeroUI colours live inside
    // the plugin closure, so a JSON.stringify assertion would pass trivially.
    const here = dirname(fileURLToPath(import.meta.url))
    const source = readFileSync(resolve(here, '../../tailwind.config.ts'), 'utf8')
    expect(source).not.toContain('#6366f1')
    expect(source).toContain("from './src/styles/palette'")
  })

  it('sources semantic colours from the palette module', () => {
    expect(palette.primary).toBe('#006fee')
    expect(palette.warning).toBe('#f5a524')
  })
})
