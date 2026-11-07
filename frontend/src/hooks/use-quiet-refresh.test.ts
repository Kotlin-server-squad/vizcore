import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useQuietRefresh, QUIET_REFRESH_MS } from './use-quiet-refresh'

beforeEach(() => vi.useFakeTimers())
afterEach(() => vi.useRealTimers())

function read(updatedAt: number) {
  return { updatedAt, refetch: vi.fn() }
}

describe('useQuietRefresh (Part 7, P-1)', () => {
  it('re-reads a read older than 15 s while the stream is on, each on its own age', () => {
    const t0 = Date.now()
    const snapshot = read(t0)
    const health = read(t0 + 10_000)
    renderHook(() => useQuietRefresh({ enabled: true, reads: [snapshot, health] }))
    act(() => vi.advanceTimersByTime(QUIET_REFRESH_MS))
    expect(snapshot.refetch).toHaveBeenCalledTimes(1)
    expect(health.refetch).not.toHaveBeenCalled()
  })

  it('costs nothing on a busy stream, whose reads stay fresh', () => {
    const snapshot = read(Date.now())
    const { rerender } = renderHook(({ at }) => useQuietRefresh({ enabled: true, reads: [{ ...snapshot, updatedAt: at }] }), {
      initialProps: { at: Date.now() },
    })
    for (let i = 0; i < 12; i++) {
      act(() => vi.advanceTimersByTime(5000))
      rerender({ at: Date.now() })
    }
    expect(snapshot.refetch).not.toHaveBeenCalled()
  })

  it('does nothing while paused', () => {
    const snapshot = read(0)
    renderHook(() => useQuietRefresh({ enabled: false, reads: [snapshot] }))
    act(() => vi.advanceTimersByTime(60_000))
    expect(snapshot.refetch).not.toHaveBeenCalled()
  })
})
