import { readFileSync, existsSync, readdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'

const looksDir = dirname(fileURLToPath(import.meta.url))
const folders = readdirSync(looksDir).filter(name => existsSync(join(looksDir, name, 'palette.json')))

/** The parts Part 7 (Problems) adds to the session page: one markup, styled by each look. */
const PARTS = [
  'problems',
  'problems-headline',
  'problems-line',
  'problem',
  'problem-show',
  'observations',
  'checks-status',
  'connection',
  'connection-facts',
  'dock-tab-badge',
  'exception-card',
  'clue-card',
]

describe('Part 7’s new parts in every trial look', () => {
  it.each(folders)('%s styles each of them', folder => {
    const id = (JSON.parse(readFileSync(join(looksDir, folder, 'palette.json'), 'utf8')) as { id: string }).id
    const css = readFileSync(join(looksDir, folder, 'tokens.css'), 'utf8')
    for (const part of PARTS) {
      expect(css, `${folder}: ${part}`).toContain(`[data-look='${id}'] [data-look-part='${part}']`)
    }
  })
})

describe('Part 7’s card facts in every trial look', () => {
  it.each(folders)('%s keeps the round context after the state visible (it hides only the running mark)', folder => {
    const css = readFileSync(join(looksDir, folder, 'card.css'), 'utf8')
    expect(css).not.toMatch(/\[data-look-part='node-state'\] > span \{/)
    expect(css).toMatch(/\[data-look-part='node-state'\] > span:not\(\[data-look-part\]\)/)
  })
})
