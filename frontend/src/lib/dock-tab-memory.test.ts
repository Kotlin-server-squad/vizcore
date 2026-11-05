import { describe, it, expect, beforeEach } from 'vitest'
import { DOCK_TABS_KEY, openingDockTab, rememberDockTab, rememberedDockTab } from './dock-tab-memory'

beforeEach(() => localStorage.clear())

describe('openingDockTab (S-5 without Problems)', () => {
  it('opens a demo started with its scenario on Learn while it has not finished', () => {
    expect(openingDockTab({ startedAsDemo: true, outcome: 'notStarted' })).toBe('learn')
    expect(openingDockTab({ startedAsDemo: true, outcome: 'running' })).toBe('learn')
  })

  it('opens a finished or failed demo on the Inspector', () => {
    expect(openingDockTab({ startedAsDemo: true, outcome: 'finished' })).toBe('inspector')
    expect(openingDockTab({ startedAsDemo: true, outcome: 'failed' })).toBe('inspector')
  })

  it('opens an attached app on the Inspector', () => {
    expect(openingDockTab({ startedAsDemo: false, outcome: null })).toBe('inspector')
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
    localStorage.setItem(DOCK_TABS_KEY, JSON.stringify([['s-1', 'problems'], ['s-2', 'events']]))
    expect(rememberedDockTab('s-1')).toBeNull()
    expect(rememberedDockTab('s-2')).toBe('events')
    localStorage.setItem(DOCK_TABS_KEY, 'nope')
    expect(rememberedDockTab('s-2')).toBeNull()
  })
})
