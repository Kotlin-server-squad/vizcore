import { describe, it, expect } from 'vitest'
import { GUIDES } from './guides'
import { MISTAKES } from './mistakes'
import { WILL_SEE, willSeeFor } from './will-see'
import { CoroutineState } from '@/types/api'
// What GET /api/scenarios serves; a backend test keeps this copy equal to it.
import catalog from '@/test/demo-catalog.json'

const ids = new Set(catalog.scenarios.map((s) => s.id))
/** The groups whose demos all have a guide so far; the others follow as their stories are written. */
const GUIDED_GROUPS = ['basics', 'channels']
const guided = catalog.scenarios.filter((s) => GUIDED_GROUPS.includes(s.group)).map((s) => s.id)
/** The step markers in a snippet: `// 1`, `// 2` … (a duration is written `// takes 8 s`). */
const markers = (code: string) => [...code.matchAll(/\/\/\s*(\d+)\b/g)].map((m) => Number(m[1]))

describe('gallery guides', () => {
  it('has a guide for every Basics and Channels demo', () => {
    expect(guided).toHaveLength(10)
    for (const id of guided) expect(GUIDES[id], id).toBeDefined()
  })

  it('keys every guide by its own demo id, which the catalog serves', () => {
    for (const [key, guide] of Object.entries(GUIDES)) {
      expect(guide.demoId).toBe(key)
      expect(ids.has(guide.demoId), guide.demoId).toBe(true)
    }
  })

  it('numbers the code exactly 1…n for its n steps', () => {
    for (const guide of Object.values(GUIDES)) {
      expect(guide.steps.length, guide.demoId).toBeGreaterThan(0)
      const want = guide.steps.map((_, i) => i + 1)
      expect([...new Set(markers(guide.code))].sort((a, b) => a - b), guide.demoId).toEqual(want)
    }
  })

  it('names only common mistakes that exist', () => {
    const known = new Set(MISTAKES.map((m) => m.id))
    for (const guide of Object.values(GUIDES)) {
      if (guide.mistakeId) expect(known.has(guide.mistakeId), guide.demoId).toBe(true)
    }
  })

  it('pictures every guide in will-see.json, with known states and a tree shape', () => {
    for (const id of Object.keys(GUIDES)) {
      const picture = willSeeFor(id)
      expect(picture, id).toBeDefined()
      expect(picture!.moment.length, id).toBeGreaterThan(0)
      expect(picture!.atSeconds, id).toBeGreaterThan(0)
      expect(picture!.coroutines.length, id).toBeGreaterThan(0)
      expect(picture!.coroutines[0]!.depth, id).toBe(0)
      picture!.coroutines.forEach((c, i) => {
        expect(Object.values(CoroutineState), `${id} ${c.label}`).toContain(c.state)
        // A row is at most one level deeper than the row above it.
        if (i > 0) expect(c.depth, `${id} ${c.label}`).toBeLessThanOrEqual(picture!.coroutines[i - 1]!.depth + 1)
      })
    }
    expect(Object.keys(WILL_SEE).sort()).toEqual(Object.keys(GUIDES).sort())
  })
})
