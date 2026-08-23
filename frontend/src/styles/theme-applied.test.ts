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
