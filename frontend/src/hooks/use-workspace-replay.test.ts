import { describe, it, expect, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import type { VizEvent } from '@/types/api'

vi.mock('./use-event-stream', () => ({
  useEventStream: () => ({
    events: [],
    isConnected: false,
    clearEvents: vi.fn(),
    error: null,
    droppedCount: 0,
    receivedCount: 0,
    reconnect: vi.fn(),
  }),
}))

import { useWorkspaceReplay } from './use-workspace-replay'

const events = [1, 2, 3, 4, 5].map(seq => ({ seq, tsNanos: seq * 1e6, sessionId: 's', kind: 'coroutine.started', coroutineId: 'a' }) as unknown as VizEvent)

const setup = () =>
  renderHook(() => useWorkspaceReplay({ sessionId: 's', streamEnabled: false, storedEvents: events, getPanelEl: () => null }))

describe('useWorkspaceReplay — entering replay', () => {
  it('seeks to the end by default (D-03)', () => {
    const { result } = setup()
    act(() => result.current.enterReplay())
    expect(result.current.replay.currentIndex).toBe(4)
  })

  it('seeks to a given event when asked (story Reveal, plan P-8)', () => {
    const { result } = setup()
    act(() => result.current.enterReplay(events, { atSeq: 3 }))
    expect(result.current.replayActive).toBe(true)
    expect(result.current.replay.currentIndex).toBe(2)
  })

  it('re-freezes and seeks again when already replaying', () => {
    const { result } = setup()
    act(() => result.current.enterReplay(events.slice(0, 3)))
    act(() => result.current.enterReplay(events, { atSeq: 5 }))
    expect(result.current.replay.events).toHaveLength(5)
    expect(result.current.replay.currentIndex).toBe(4)
    act(() => result.current.enterReplay(events, { atSeq: 2 }))
    expect(result.current.replay.currentIndex).toBe(1)
  })
  it('keeps what the frozen events lost to shedding, apart from the live count', () => {
    const { result } = setup()
    act(() => result.current.enterReplay(events, { shedCount: 4 }))
    expect(result.current.replayShedCount).toBe(4)
    act(() => result.current.enterReplay())
    expect(result.current.replayShedCount).toBe(0)
  })

  it('keeps the frozen shed count when the same snapshot is frozen again (a recording of a paused replay)', () => {
    const { result } = setup()
    act(() => result.current.enterReplay(events, { shedCount: 4 }))
    const frozen = result.current.replaySnapshot
    act(() => result.current.enterReplay(frozen))
    expect(result.current.replayShedCount).toBe(4)
  })
})
