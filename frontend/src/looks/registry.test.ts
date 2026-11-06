import { describe, it, expect } from 'vitest'
import { DEFAULT_LOOK, LOOKS, parseLook } from './registry'

// Registry-driven, so deleting a look needs no change here; each look's own
// facts are tested in its folder.
describe('the look registry', () => {
  it('starts with Classic, the default and the only look not on trial', () => {
    expect(LOOKS[0]).toMatchObject({ id: 'classic', label: 'Classic', trial: false })
    expect(DEFAULT_LOOK).toBe('classic')
    expect(LOOKS.slice(1).every((look) => look.trial)).toBe(true)
  })

  it('gives every look its own id and label', () => {
    expect(new Set(LOOKS.map((l) => l.id)).size).toBe(LOOKS.length)
    expect(new Set(LOOKS.map((l) => l.label)).size).toBe(LOOKS.length)
  })

  it('reads a known look id as it is', () => {
    for (const { id } of LOOKS) expect(parseLook(id)).toBe(id)
  })

  it('falls back to Classic for anything else', () => {
    for (const value of ['z', '', 'A', ' a', 'classic ', null, undefined, 1, {}, ['a']]) {
      expect(parseLook(value)).toBe('classic')
    }
  })
})
