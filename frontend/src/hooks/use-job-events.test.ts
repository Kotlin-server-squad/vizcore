import { describe, it, expect, vi } from 'vitest'
import { renderHook } from '@testing-library/react'
import type { VizEvent } from '@/types/api'

vi.mock('@/hooks/use-sessions', () => ({ useSessionEvents: vi.fn(() => ({ data: [] })) }))

import { useJobEvents } from './use-job-events'

describe('useJobEvents', () => {
  it('reduces in seq order, whatever order the events arrived in', () => {
    const base = { sessionId: 's1', coroutineId: 'c-1', jobId: 'j-1', parentCoroutineId: null, scopeId: 'sc', label: 'a', childrenCount: 0 }
    const active = { ...base, seq: 1, tsNanos: 1, kind: 'JobStateChanged', isActive: true, isCompleted: false, isCancelled: false }
    const done = { ...base, seq: 2, tsNanos: 2, kind: 'JobStateChanged', isActive: false, isCompleted: true, isCancelled: false }

    const { result } = renderHook(() => useJobEvents('s1', [done, active] as unknown as VizEvent[]))

    expect(result.current.jobs[0]?.status).toBe('Completed')
  })

  it('reads the events it is given, not the stored history, when given some', () => {
    const live = [
      {
        sessionId: 's1', seq: 1, tsNanos: 1, kind: 'JobStateChanged', coroutineId: 'c-1', jobId: 'j-1',
        parentCoroutineId: null, scopeId: 'sc', label: 'a', isActive: true, isCompleted: false, isCancelled: false,
        childrenCount: 0,
      },
    ] as unknown as VizEvent[]

    const { result } = renderHook(() => useJobEvents('s1', live))

    expect(result.current.jobs.map(j => j.jobId)).toEqual(['j-1'])
  })
})
