import { describe, it, expect } from 'vitest'
import { renderHook } from '@testing-library/react'
import { useRecordedSpan } from './use-recorded-span'
import type { VizEvent } from '@/types/api'

const ev = (tsNanos: number) => ({ seq: 1, tsNanos, sessionId: 's', kind: 'CoroutineCreated' }) as unknown as VizEvent

describe('useRecordedSpan', () => {
  it('spans everything the page has held, even after the live list is cleared (Pause view)', () => {
    const stored = [ev(0), ev(2e9)]
    const { result, rerender } = renderHook(({ live }) => useRecordedSpan(stored, live), {
      initialProps: { live: [ev(5e9), ev(12e9)] },
    })
    expect(result.current).toBe(12e9)
    rerender({ live: [] })
    expect(result.current).toBe(12e9)
  })

  it('grows as later events arrive', () => {
    const { result, rerender } = renderHook(({ live }) => useRecordedSpan([], live), {
      initialProps: { live: [ev(1e9), ev(3e9)] },
    })
    expect(result.current).toBe(2e9)
    rerender({ live: [ev(1e9), ev(3e9), ev(4e9)] })
    expect(result.current).toBe(3e9)
  })

  it('is unknown until two distinct times have been seen', () => {
    const { result } = renderHook(() => useRecordedSpan([ev(1e9)]))
    expect(result.current).toBeNull()
  })
})
