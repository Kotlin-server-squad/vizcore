import { readFileSync, existsSync, readdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'

const looksDir = dirname(fileURLToPath(import.meta.url))
const folders = readdirSync(looksDir).filter(name => existsSync(join(looksDir, name, 'palette.json')))

/** The parts Part 8 (one moment everywhere) adds to the session page: one markup, styled by each look. */
const PARTS = ['replay-bar', 'replay-time', 'moment']

describe('Part 8’s new parts in every trial look', () => {
  it.each(folders)('%s styles each of them', folder => {
    const id = (JSON.parse(readFileSync(join(looksDir, folder, 'palette.json'), 'utf8')) as { id: string }).id
    const css = readFileSync(join(looksDir, folder, 'tokens.css'), 'utf8')
    for (const part of PARTS) {
      expect(css, `${folder}: ${part}`).toContain(`[data-look='${id}'] [data-look-part='${part}']`)
    }
  })
})
