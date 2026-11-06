import { describe, it, expect, beforeEach } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { useDockLayout } from './use-dock-layout'
import { DOCK_MIN_PX, readDockPrefs, writeDockPrefs } from '@/lib/dock-layout'

beforeEach(() => localStorage.clear())

describe('useDockLayout', () => {
  it('opens at 40 % of the window, unfolded and not maximised', () => {
    const { result } = renderHook(() => useDockLayout())
    expect(result.current.height).toBe(Math.round(window.innerHeight * 0.4))
    expect(result.current.folded).toBe(false)
    expect(result.current.maximised).toBe(false)
  })

  it('opens at a look’s own starting share when it brings one', () => {
    const { result } = renderHook(() => useDockLayout(0.2))
    expect(result.current.height).toBe(Math.round(window.innerHeight * 0.2))
  })

  it('opens at 40 % when the look brings no share (Classic, A and B today)', () => {
    const { result } = renderHook(() => useDockLayout(undefined))
    expect(result.current.height).toBe(Math.round(window.innerHeight * 0.4))
  })

  it('keeps the size the user left the dock at, whatever the look’s share', () => {
    writeDockPrefs({ height: 260, folded: false })
    const { result } = renderHook(() => useDockLayout(0.2))
    expect(result.current.height).toBe(260)
  })

  it('follows a change of look while the page is open, until the user resizes', () => {
    const { result, rerender } = renderHook(({ share }: { share?: number }) => useDockLayout(share), {
      initialProps: { share: undefined as number | undefined },
    })
    expect(result.current.height).toBe(Math.round(window.innerHeight * 0.4))
    rerender({ share: 0.2 })
    expect(result.current.height).toBe(Math.round(window.innerHeight * 0.2))
    rerender({ share: undefined })
    expect(result.current.height).toBe(Math.round(window.innerHeight * 0.4))
    act(() => result.current.resize(300))
    rerender({ share: 0.2 })
    expect(result.current.height).toBe(300)
  })

  it('folding stores no size, so one look’s start never becomes another’s', () => {
    const { result, unmount } = renderHook(() => useDockLayout(0.2))
    act(() => result.current.toggleFold())
    act(() => result.current.toggleFold())
    expect(readDockPrefs()).toEqual({ height: null, folded: false })
    unmount()
    const classic = renderHook(() => useDockLayout())
    expect(classic.result.current.height).toBe(Math.round(window.innerHeight * 0.4))
  })

  it('opens at the remembered size and fold', () => {
    writeDockPrefs({ height: 260, folded: true })
    const { result } = renderHook(() => useDockLayout())
    expect(result.current.height).toBe(260)
    expect(result.current.folded).toBe(true)
  })

  it('remembers a resize, and resizing unfolds the dock', () => {
    writeDockPrefs({ height: 260, folded: true })
    const { result } = renderHook(() => useDockLayout())
    act(() => result.current.resize(400))
    expect(result.current.height).toBe(400)
    expect(result.current.folded).toBe(false)
    expect(readDockPrefs()).toEqual({ height: 400, folded: false })
  })

  it('never resizes below the smallest dock', () => {
    const { result } = renderHook(() => useDockLayout())
    act(() => result.current.resize(10))
    expect(result.current.height).toBe(DOCK_MIN_PX)
  })

  it('remembers the fold', () => {
    const { result } = renderHook(() => useDockLayout())
    act(() => result.current.toggleFold())
    expect(result.current.folded).toBe(true)
    expect(readDockPrefs().folded).toBe(true)
  })

  it('maximises for this visit only', () => {
    const { result } = renderHook(() => useDockLayout())
    act(() => result.current.toggleMaximise())
    expect(result.current.maximised).toBe(true)
    expect(localStorage.getItem('vizcore.dock')).toBeNull()
  })
})
