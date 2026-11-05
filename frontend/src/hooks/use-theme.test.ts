import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { THEME_STORAGE_KEY, readStoredTheme, useTheme } from './use-theme'

const html = () => document.documentElement

beforeEach(() => {
  localStorage.clear()
  html().className = 'dark'
  delete html().dataset.vizTheme
  vi.restoreAllMocks()
})

describe('useTheme', () => {
  it('defaults to dark', () => {
    const { result } = renderHook(() => useTheme())
    expect(result.current.theme).toBe('dark')
    expect(html().classList.contains('dark')).toBe(true)
    expect(html().classList.contains('light')).toBe(false)
  })

  it('switches to light, puts the light class on <html> and remembers it', () => {
    const { result } = renderHook(() => useTheme())
    act(() => result.current.toggle())
    expect(result.current.theme).toBe('light')
    expect(html().classList.contains('light')).toBe(true)
    expect(html().classList.contains('dark')).toBe(false)
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('light')
  })

  it('starts from the saved choice', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'light')
    const { result } = renderHook(() => useTheme())
    expect(result.current.theme).toBe('light')
  })

  it('treats an unknown saved value as dark', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'purple')
    expect(readStoredTheme()).toBe('dark')
  })

  it('still works when storage is unavailable', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    const { result } = renderHook(() => useTheme())
    expect(result.current.theme).toBe('dark')
    act(() => result.current.toggle())
    expect(html().classList.contains('light')).toBe(true)
  })

  it('keeps a choice made while storage is blocked when the page remounts the switch', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    const first = renderHook(() => useTheme())
    act(() => first.result.current.toggle())
    first.unmount()

    // Every route renders its own Layout, so navigating mounts a fresh switch.
    const second = renderHook(() => useTheme())
    expect(second.result.current.theme).toBe('light')
    expect(html().classList.contains('light')).toBe(true)
  })

  it.each([null, 'dark'])(
    'keeps this visit\'s choice when saving fails but storage still reads %s',
    (stale) => {
      if (stale) localStorage.setItem(THEME_STORAGE_KEY, stale)
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new Error('quota')
      })
      const first = renderHook(() => useTheme())
      act(() => first.result.current.toggle())
      first.unmount()

      const second = renderHook(() => useTheme())
      expect(second.result.current.theme).toBe('light')
      expect(html().classList.contains('light')).toBe(true)
    },
  )
})
