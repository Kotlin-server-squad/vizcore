import { describe, it, expect, beforeEach } from 'vitest'
import {
  DOCK_FIRST_VIEW_MIN_PX,
  DOCK_MIN_PX,
  DOCK_STORAGE_KEY,
  MAIN_MIN_PX,
  clampDockHeight,
  dockMaxHeight,
  initialDockHeight,
  readDockPrefs,
  startingDockHeight,
  writeDockPrefs,
} from './dock-layout'

beforeEach(() => localStorage.clear())

describe('dock size', () => {
  it('starts at 40 % of the window (S-1)', () => {
    expect(initialDockHeight(800)).toBe(320)
  })

  it('starts at a look’s own share when it brings one, and at 40 % without', () => {
    expect(initialDockHeight(900, 0.2)).toBe(180)
    expect(initialDockHeight(900, undefined)).toBe(360)
  })

  it('leaves the main view at least 320 px of the space it shares with the dock', () => {
    expect(dockMaxHeight(900)).toBe(900 - MAIN_MIN_PX - 12)
  })

  it('has no upper limit until the space is measured', () => {
    expect(dockMaxHeight(0)).toBe(Number.POSITIVE_INFINITY)
  })

  it('never goes below the smallest useful dock, even in a tiny window', () => {
    expect(dockMaxHeight(300)).toBe(DOCK_MIN_PX)
    expect(clampDockHeight(10, 600)).toBe(DOCK_MIN_PX)
  })

  it('clamps a height to the space available', () => {
    expect(clampDockHeight(1_000, 900)).toBe(900 - MAIN_MIN_PX - 12)
    expect(clampDockHeight(300, 900)).toBe(300)
    expect(clampDockHeight(300.6, 0)).toBe(301)
  })
})

describe('remembered dock prefs', () => {
  it('reads nothing when nothing was stored', () => {
    expect(readDockPrefs()).toEqual({ height: null, folded: false })
  })

  it('round-trips the height and the fold', () => {
    writeDockPrefs({ height: 280, folded: true })
    expect(readDockPrefs()).toEqual({ height: 280, folded: true })
  })

  it('ignores a stored value it cannot read', () => {
    localStorage.setItem(DOCK_STORAGE_KEY, '{not json')
    expect(readDockPrefs()).toEqual({ height: null, folded: false })
    localStorage.setItem(DOCK_STORAGE_KEY, JSON.stringify({ height: 'tall', folded: 'yes' }))
    expect(readDockPrefs()).toEqual({ height: null, folded: false })
  })
})

describe('starting dock height and the graph’s first view', () => {
  it('starts at the look’s share until the graph has measured', () => {
    expect(startingDockHeight(312, null)).toBe(312)
  })

  it('keeps the share when the graph has room to spare', () => {
    expect(startingDockHeight(312, 400)).toBe(312)
  })

  it('gives way so the root and its first row fit (A on a 779 px window: 312 px share, room for 270)', () => {
    expect(startingDockHeight(312, 270)).toBe(270)
  })

  it('never gives way below the tab row and a few lines', () => {
    expect(startingDockHeight(312, 40)).toBe(DOCK_FIRST_VIEW_MIN_PX)
    expect(startingDockHeight(312, -200)).toBe(DOCK_FIRST_VIEW_MIN_PX)
  })

  it('keeps a share already below that floor (C: 17 % of a short window) rather than growing it', () => {
    expect(startingDockHeight(132, 40)).toBe(132)
  })
})
