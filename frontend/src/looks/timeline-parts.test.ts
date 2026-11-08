import { readFileSync, existsSync, readdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'

const looksDir = dirname(fileURLToPath(import.meta.url))
const folders = readdirSync(looksDir).filter(name => existsSync(join(looksDir, name, 'palette.json')))

/** The parts Part 10 (the Timeline) adds to the session page: one markup, styled by each look. */
const PARTS = [
  'timeline',
  'timeline-axis',
  'timeline-legend',
  'timeline-label',
  'timeline-row',
  'timeline-bar',
  'timeline-hold',
  'timeline-end',
  'timeline-lane',
  'timeline-lane-band',
  'timeline-unknown',
  'timeline-cursor',
  'timeline-future',
  'timeline-sentence',
]

/** Each state a bar can show: a look colours every one of them. */
const BAR_STATES = ['CREATED', 'ACTIVE', 'SUSPENDED', 'WAITING_FOR_CHILDREN']

describe('Part 10’s new parts in every trial look', () => {
  it.each(folders)('%s styles each of them', folder => {
    const id = (JSON.parse(readFileSync(join(looksDir, folder, 'palette.json'), 'utf8')) as { id: string }).id
    const css = readFileSync(join(looksDir, folder, 'tokens.css'), 'utf8')
    for (const part of PARTS) {
      expect(css, `${folder}: ${part}`).toContain(`[data-look='${id}'] [data-look-part='${part}']`)
    }
    for (const state of BAR_STATES) {
      expect(css, `${folder}: bar ${state}`).toContain(`[data-look='${id}'] [data-look-part='timeline-bar'][data-state='${state}']`)
    }
    // What comes after the replay cursor is outlined, and "may hold" is hatched, in every look.
    expect(css).toContain(`[data-look='${id}'] [data-look-part='timeline-bar'][data-after-cursor='true']`)
    // The hatching survives the look's own fills (a `background` shorthand would erase it).
    for (const part of ['timeline-hold', 'timeline-lane-band']) {
      const selector = `[data-look='${id}'] [data-look-part='${part}'][data-uncertain='true']`
      const start = css.indexOf(`${selector} {`)
      expect(start, `${folder}: ${part} may hold`).toBeGreaterThan(-1)
      expect(css.slice(start, css.indexOf('}', start))).toContain('repeating-linear-gradient')
    }
  })
})
