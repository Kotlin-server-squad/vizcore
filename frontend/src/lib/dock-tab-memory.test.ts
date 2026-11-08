import { describe, it, expect, beforeEach } from 'vitest'
import { DOCK_TABS_KEY, openingDockTab, rememberDockTab, rememberedDockTab } from './dock-tab-memory'

beforeEach(() => localStorage.clear())

describe('openingDockTab (S-5)', () => {
  const base = { startedAsDemo: false, outcome: null, attached: false, problems: false } as const

  it('opens a demo started with its scenario on Learn while it has not finished', () => {
    expect(openingDockTab({ ...base, startedAsDemo: true, outcome: 'notStarted' })).toBe('learn')
    expect(openingDockTab({ ...base, startedAsDemo: true, outcome: 'running', problems: true })).toBe('learn')
  })

  it('opens a finished demo on the Inspector, and one with a detected problem on Problems', () => {
    expect(openingDockTab({ ...base, startedAsDemo: true, outcome: 'finished' })).toBe('inspector')
    expect(openingDockTab({ ...base, startedAsDemo: true, outcome: 'failed', problems: true })).toBe('problems')
    expect(openingDockTab({ ...base, outcome: 'failed', problems: true })).toBe('problems')
  })

  it('opens an attached app on Problems', () => {
    expect(openingDockTab({ ...base, attached: true })).toBe('problems')
  })

  it('opens anything else on the Inspector', () => {
    expect(openingDockTab(base)).toBe('inspector')
  })
})

describe('remembered tab', () => {
  it('is per session', () => {
    rememberDockTab('s-1', 'events')
    expect(rememberedDockTab('s-1')).toBe('events')
    expect(rememberedDockTab('s-2')).toBeNull()
  })

  it('keeps the 50 most recent sessions', () => {
    for (let i = 0; i < 55; i++) rememberDockTab(`s-${i}`, 'learn')
    expect(rememberedDockTab('s-0')).toBeNull()
    expect(rememberedDockTab('s-54')).toBe('learn')
    expect(JSON.parse(localStorage.getItem(DOCK_TABS_KEY)!)).toHaveLength(50)
  })

  it('ignores what it cannot read, including a tab that no longer exists', () => {
    localStorage.setItem(DOCK_TABS_KEY, JSON.stringify([['s-1', 'timeline'], ['s-2', 'events'], ['s-3', 'problems']]))
    expect(rememberedDockTab('s-1')).toBeNull()
    expect(rememberedDockTab('s-2')).toBe('events')
    expect(rememberedDockTab('s-3')).toBe('problems')
    localStorage.setItem(DOCK_TABS_KEY, 'nope')
    expect(rememberedDockTab('s-2')).toBeNull()
  })
})
