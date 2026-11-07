import { describe, it, expect } from 'vitest'
import { decideEntry, pickWeighted } from './assign'

/** A small seeded generator (mulberry32), so the fairness check is repeatable. */
function seeded(seed: number) {
  let a = seed
  return () => {
    a |= 0
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

const even = { classic: 25, a: 25, b: 25, c: 25 }

describe('pickWeighted', () => {
  it('splits 40,000 visitors within 2 points of the weights', () => {
    const random = seeded(7)
    const counts: Record<string, number> = { classic: 0, a: 0, b: 0, c: 0 }
    for (let i = 0; i < 40_000; i++) counts[pickWeighted(even, random)]!++
    for (const look of Object.keys(even))
      expect(Math.abs(counts[look]! / 40_000 - 0.25)).toBeLessThan(0.02)
  })

  it('follows uneven weights and never gives a look with weight 0', () => {
    const random = seeded(3)
    const counts: Record<string, number> = { classic: 0, a: 0, b: 0, c: 0 }
    for (let i = 0; i < 20_000; i++)
      counts[pickWeighted({ classic: 0, a: 10, b: 0, c: 30 }, random)]!++
    expect(counts.classic).toBe(0)
    expect(counts.b).toBe(0)
    expect(Math.abs(counts.c! / 20_000 - 0.75)).toBeLessThan(0.02)
  })

  it('ignores unknown looks and falls back to Classic when nothing has weight', () => {
    expect(pickWeighted({ zzz: 100 }, () => 0.5)).toBe('classic')
    expect(pickWeighted({}, () => 0.5)).toBe('classic')
  })

  it('covers the edges of the random range', () => {
    expect(pickWeighted(even, () => 0)).toBe('classic')
    expect(pickWeighted(even, () => 0.999999)).toBe('c')
  })
})

describe('decideEntry', () => {
  it('a link wins and counts as chose', () => {
    expect(decideEntry({ link: 'b', choice: 'c', weights: even, random: () => 0 })).toEqual({
      look: 'b',
      kind: 'chose',
    })
  })

  it('an unknown link is Classic, chosen', () => {
    expect(decideEntry({ link: 'zzz', choice: null, weights: even, random: () => 0.9 })).toEqual({
      look: 'classic',
      kind: 'chose',
    })
  })

  it('a look the browser already picked is kept and counts as chose', () => {
    expect(decideEntry({ choice: 'a', weights: even, random: () => 0.9 })).toEqual({
      look: 'a',
      kind: 'chose',
    })
  })

  it('otherwise the weights assign one', () => {
    expect(decideEntry({ choice: null, weights: even, random: () => 0.6 })).toEqual({
      look: 'b',
      kind: 'assigned',
    })
  })
})
