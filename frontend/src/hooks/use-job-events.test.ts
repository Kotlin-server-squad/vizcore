import { describe, it, expect, vi } from 'vitest'
import { renderHook } from '@testing-library/react'
import type { VizEvent } from '@/types/api'

vi.mock('@/hooks/use-sessions', () => ({ useSessionEvents: vi.fn(() => ({ data: [] })) }))

import { useJobEvents } from './use-job-events'

describe('useJobEvents', () => {
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
