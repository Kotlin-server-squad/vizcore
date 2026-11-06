import { describe, it, expect } from 'vitest'
import { DEFAULT_LOOK, LOOKS, parseLook } from './registry'

describe('the look registry', () => {
  it('lists Classic first, then the three trial looks', () => {
    expect(LOOKS.map((l) => l.id)).toEqual(['classic', 'a', 'b', 'c'])
    expect(LOOKS.map((l) => l.label)).toEqual(['Classic', 'A · Compact', 'B · Calm', 'C · Bold'])
    expect(LOOKS.map((l) => l.trial)).toEqual([false, true, true, true])
    expect(DEFAULT_LOOK).toBe('classic')
  })

  it('reads a known look id as it is', () => {
    for (const id of ['classic', 'a', 'b', 'c']) expect(parseLook(id)).toBe(id)
  })

  it('falls back to Classic for anything else', () => {
    for (const value of ['z', '', 'A', ' a', 'classic ', null, undefined, 1, {}]) {
      expect(parseLook(value)).toBe('classic')
    }
  })
})
