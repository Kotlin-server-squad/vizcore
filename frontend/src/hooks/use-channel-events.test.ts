import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import type { VizEvent } from '@/types/api'

vi.mock('@/hooks/use-sessions', () => ({
  useSessionEvents: vi.fn(),
}))

import { useChannelEvents } from './use-channel-events'
import { useSessionEvents } from '@/hooks/use-sessions'

const mockedUseSessionEvents = vi.mocked(useSessionEvents)

describe('useChannelEvents', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('reads the events it is given, not the stored history, when given some', () => {
    mockedUseSessionEvents.mockReturnValue({ data: [] } as unknown as ReturnType<typeof useSessionEvents>)
    const live = [
      { kind: 'ChannelCreated', sessionId: 's1', seq: 1, tsNanos: 1, channelId: 'ch-1', name: 'orders', capacity: 0, channelType: 'RENDEZVOUS' },
    ] as unknown as VizEvent[]

    const { result } = renderHook(() => useChannelEvents('s1', live))

    expect(result.current.channels.map(c => c.channelId)).toEqual(['ch-1'])
  })

  it('returns empty result when no events', () => {
    mockedUseSessionEvents.mockReturnValue({ data: [] } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useChannelEvents('session-1'))

    expect(result.current.channelEvents).toEqual([])
    expect(result.current.channels).toEqual([])
    expect(result.current.eventsByChannel.size).toBe(0)
    expect(result.current.bufferStates.size).toBe(0)
  })

  it('returns empty result when events is undefined', () => {
    mockedUseSessionEvents.mockReturnValue({ data: undefined } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useChannelEvents('session-1'))

    expect(result.current.channelEvents).toEqual([])
    expect(result.current.channels).toEqual([])
    expect(result.current.eventsByChannel.size).toBe(0)
    expect(result.current.bufferStates.size).toBe(0)
  })

  it('tracks channel creation with metadata', () => {
    mockedUseSessionEvents.mockReturnValue({
      data: [
        {
          kind: 'ChannelCreated',
          sessionId: 's1',
          seq: 1,
          tsNanos: 1000,
          channelId: 'ch1',
          name: 'messages',
          capacity: 64,
          channelType: 'BUFFERED',
        },
        {
          kind: 'ChannelCreated',
          sessionId: 's1',
          seq: 2,
          tsNanos: 2000,
          channelId: 'ch2',
          name: 'signals',
          capacity: 0,
          channelType: 'RENDEZVOUS',
        },
      ],
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useChannelEvents('session-1'))

    expect(result.current.channels).toHaveLength(2)

    const ch1 = result.current.channels.find(c => c.channelId === 'ch1')!
    expect(ch1.name).toBe('messages')
    expect(ch1.channelType).toBe('BUFFERED')
    expect(ch1.capacity).toBe(64)
    expect(ch1.isClosed).toBe(false)

    const ch2 = result.current.channels.find(c => c.channelId === 'ch2')!
    expect(ch2.name).toBe('signals')
    expect(ch2.channelType).toBe('RENDEZVOUS')
    expect(ch2.capacity).toBe(0)
  })

  it('tracks producers and consumers', () => {
    mockedUseSessionEvents.mockReturnValue({
      data: [
        {
          kind: 'ChannelCreated',
          sessionId: 's1',
          seq: 1,
          tsNanos: 1000,
          channelId: 'ch1',
          name: 'work',
          capacity: 10,
          channelType: 'BUFFERED',
        },
        {
          kind: 'ChannelSendStarted',
          sessionId: 's1',
          seq: 2,
          tsNanos: 2000,
          channelId: 'ch1',
          coroutineId: 'producer-1',
          valueDescription: 'item-1',
        },
        {
          kind: 'ChannelSendCompleted',
          sessionId: 's1',
          seq: 3,
          tsNanos: 3000,
          channelId: 'ch1',
          coroutineId: 'producer-1',
          valueDescription: 'item-1',
        },
        {
          kind: 'ChannelSendStarted',
          sessionId: 's1',
          seq: 4,
          tsNanos: 4000,
          channelId: 'ch1',
          coroutineId: 'producer-2',
          valueDescription: 'item-2',
        },
        {
          kind: 'ChannelReceiveStarted',
          sessionId: 's1',
          seq: 5,
          tsNanos: 5000,
          channelId: 'ch1',
          coroutineId: 'consumer-1',
        },
        {
          kind: 'ChannelReceiveCompleted',
          sessionId: 's1',
          seq: 6,
          tsNanos: 6000,
          channelId: 'ch1',
          coroutineId: 'consumer-1',
          valueDescription: 'item-1',
        },
        {
          kind: 'ChannelReceiveStarted',
          sessionId: 's1',
          seq: 7,
          tsNanos: 7000,
          channelId: 'ch1',
          coroutineId: 'consumer-2',
        },
      ],
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useChannelEvents('session-1'))

    const ch = result.current.channels[0]!
    expect(ch.producers.size).toBe(2)
    expect(ch.producers.has('producer-1')).toBe(true)
    expect(ch.producers.has('producer-2')).toBe(true)
    expect(ch.consumers.size).toBe(2)
    expect(ch.consumers.has('consumer-1')).toBe(true)
    expect(ch.consumers.has('consumer-2')).toBe(true)
  })

  it('updates buffer state', () => {
    mockedUseSessionEvents.mockReturnValue({
      data: [
        {
          kind: 'ChannelCreated',
          sessionId: 's1',
          seq: 1,
          tsNanos: 1000,
          channelId: 'ch1',
          name: 'data',
          capacity: 16,
          channelType: 'BUFFERED',
        },
        {
          kind: 'ChannelBufferStateChanged',
          sessionId: 's1',
          seq: 2,
          tsNanos: 2000,
          channelId: 'ch1',
          currentSize: 5,
          capacity: 16,
        },
        {
          kind: 'ChannelBufferStateChanged',
          sessionId: 's1',
          seq: 3,
          tsNanos: 3000,
          channelId: 'ch1',
          currentSize: 12,
          capacity: 16,
        },
      ],
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useChannelEvents('session-1'))

    // Buffer state should reflect the last update
    const bufferState = result.current.bufferStates.get('ch1')
    expect(bufferState).toEqual({ currentSize: 12, capacity: 16 })

    const ch = result.current.channels[0]!
    expect(ch.currentSize).toBe(12)
  })

  it('tracks channel close', () => {
    mockedUseSessionEvents.mockReturnValue({
      data: [
        {
          kind: 'ChannelCreated',
          sessionId: 's1',
          seq: 1,
          tsNanos: 1000,
          channelId: 'ch1',
          name: 'events',
          capacity: 10,
          channelType: 'BUFFERED',
        },
        {
          kind: 'ChannelClosed',
          sessionId: 's1',
          seq: 2,
          tsNanos: 2000,
          channelId: 'ch1',
          cause: null,
        },
      ],
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useChannelEvents('session-1'))

    const ch = result.current.channels[0]!
    expect(ch.isClosed).toBe(true)
    expect(ch.closeCause).toBeNull()
  })

  it('tracks channel close with cause', () => {
    mockedUseSessionEvents.mockReturnValue({
      data: [
        {
          kind: 'ChannelCreated',
          sessionId: 's1',
          seq: 1,
          tsNanos: 1000,
          channelId: 'ch1',
          name: 'work',
          capacity: 5,
          channelType: 'BUFFERED',
        },
        {
          kind: 'ChannelClosed',
          sessionId: 's1',
          seq: 2,
          tsNanos: 2000,
          channelId: 'ch1',
          cause: 'CancellationException: Job was cancelled',
        },
      ],
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useChannelEvents('session-1'))

    const ch = result.current.channels[0]!
    expect(ch.isClosed).toBe(true)
    expect(ch.closeCause).toBe('CancellationException: Job was cancelled')
  })

  it('groups events by channel', () => {
    mockedUseSessionEvents.mockReturnValue({
      data: [
        {
          kind: 'ChannelCreated',
          sessionId: 's1',
          seq: 1,
          tsNanos: 1000,
          channelId: 'ch1',
          name: 'alpha',
          capacity: 10,
          channelType: 'BUFFERED',
        },
        {
          kind: 'ChannelCreated',
          sessionId: 's1',
          seq: 2,
          tsNanos: 2000,
          channelId: 'ch2',
          name: 'beta',
          capacity: 5,
          channelType: 'BUFFERED',
        },
        {
          kind: 'ChannelSendStarted',
          sessionId: 's1',
          seq: 3,
          tsNanos: 3000,
          channelId: 'ch1',
          coroutineId: 'c1',
          valueDescription: 'val1',
        },
        {
          kind: 'ChannelReceiveStarted',
          sessionId: 's1',
          seq: 4,
          tsNanos: 4000,
          channelId: 'ch2',
          coroutineId: 'c2',
        },
        {
          kind: 'ChannelSendStarted',
          sessionId: 's1',
          seq: 5,
          tsNanos: 5000,
          channelId: 'ch1',
          coroutineId: 'c3',
          valueDescription: 'val2',
        },
      ],
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useChannelEvents('session-1'))

    expect(result.current.eventsByChannel.size).toBe(2)
    // ch1: ChannelCreated + 2 ChannelSendStarted = 3 events
    expect(result.current.eventsByChannel.get('ch1')).toHaveLength(3)
    // ch2: ChannelCreated + 1 ChannelReceiveStarted = 2 events
    expect(result.current.eventsByChannel.get('ch2')).toHaveLength(2)
  })

  it('tracks send suspension with buffer info', () => {
    mockedUseSessionEvents.mockReturnValue({
      data: [
        {
          kind: 'ChannelCreated',
          sessionId: 's1',
          seq: 1,
          tsNanos: 1000,
          channelId: 'ch1',
          name: 'bounded',
          capacity: 2,
          channelType: 'BUFFERED',
        },
        {
          kind: 'ChannelSendSuspended',
          sessionId: 's1',
          seq: 2,
          tsNanos: 2000,
          channelId: 'ch1',
          coroutineId: 'c1',
          bufferSize: 2,
          capacity: 2,
        },
      ],
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useChannelEvents('session-1'))

    const ch = result.current.channels[0]!
    expect(ch.producers.has('c1')).toBe(true)
    expect(ch.currentSize).toBe(2)

    const bufferState = result.current.bufferStates.get('ch1')
    expect(bufferState).toEqual({ currentSize: 2, capacity: 2 })
  })

  it('ignores non-channel events', () => {
    mockedUseSessionEvents.mockReturnValue({
      data: [
        {
          kind: 'coroutine.created',
          sessionId: 's1',
          seq: 1,
          tsNanos: 1000,
          coroutineId: 'c1',
          jobId: 'j1',
          parentCoroutineId: null,
          scopeId: 'scope-1',
          label: null,
        },
        {
          kind: 'FlowCreated',
          sessionId: 's1',
          seq: 2,
          tsNanos: 2000,
          flowId: 'f1',
          coroutineId: 'c1',
          flowType: 'Cold',
          label: null,
          scopeId: null,
        },
      ],
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useChannelEvents('session-1'))

    expect(result.current.channelEvents).toEqual([])
    expect(result.current.channels).toEqual([])
  })
})
