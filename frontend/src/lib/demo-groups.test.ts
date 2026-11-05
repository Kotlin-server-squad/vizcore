import { describe, it, expect } from 'vitest'
import { groupDemos } from './demo-groups'
// What GET /api/scenarios serves; a backend test keeps this copy equal to it.
import catalog from '@/test/demo-catalog.json'
import type { Scenario } from '@/types/api'

describe('groupDemos', () => {
  it('groups the full catalog in server order, labelled with groupLabel', () => {
    const groups = groupDemos(catalog.scenarios as Scenario[])
    expect(groups.map((g) => g.label)).toEqual([
      'Real life', 'Basics', 'Channels', 'Flow', 'Patterns', 'Sync: Mutex', 'Sync: Semaphore', 'Combined',
    ])
    expect(groups.reduce((n, g) => n + g.demos.length, 0)).toBe(32)
  })

  it('falls back to category labels, real life first, for a server without groups', () => {
    const groups = groupDemos([
      { id: 'nested', name: 'Nested', category: 'basic' } as Scenario,
      { id: 'order', name: 'Order', category: 'realistic' } as Scenario,
    ])
    expect(groups.map((g) => g.label)).toEqual(['Real life', 'Basics'])
  })
})
