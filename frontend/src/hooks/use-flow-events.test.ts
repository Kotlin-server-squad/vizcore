import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import type { VizEvent } from '@/types/api'

vi.mock('@/hooks/use-sessions', () => ({
  useSessionEvents: vi.fn(),
}))

import { useFlowEvents } from './use-flow-events'
import { useSessionEvents } from '@/hooks/use-sessions'

const mockedUseSessionEvents = vi.mocked(useSessionEvents)

describe('useFlowEvents', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('returns empty result when no events', () => {
    mockedUseSessionEvents.mockReturnValue({ data: [] } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useFlowEvents('session-1'))

    expect(result.current.flowEvents).toEqual([])
    expect(result.current.flows).toEqual([])
    expect(result.current.eventsByFlow.size).toBe(0)
    expect(result.current.hasBackpressure).toBe(false)
  })

  it('returns empty result when events is undefined', () => {
    mockedUseSessionEvents.mockReturnValue({ data: undefined } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useFlowEvents('session-1'))

    expect(result.current.flowEvents).toEqual([])
    expect(result.current.flows).toEqual([])
    expect(result.current.hasBackpressure).toBe(false)
  })

  it('reads the events it is given, not the stored history, when given some', () => {
    mockedUseSessionEvents.mockReturnValue({ data: [] } as unknown as ReturnType<typeof useSessionEvents>)
    const live = [
      { kind: 'FlowCreated', sessionId: 's1', seq: 1, tsNanos: 1, flowId: 'f-1', coroutineId: 'c1', flowType: 'Cold', label: 'f', scopeId: null },
    ] as unknown as VizEvent[]

    const { result } = renderHook(() => useFlowEvents('s1', live))

    expect(result.current.flows.map(f => f.flowId)).toEqual(['f-1'])
  })

  it('tracks flow creation and type', () => {
    mockedUseSessionEvents.mockReturnValue({
      data: [
        {
          kind: 'FlowCreated',
          sessionId: 's1',
          seq: 1,
          tsNanos: 1000,
          flowId: 'f1',
          coroutineId: 'c1',
          flowType: 'Cold',
          label: 'testFlow',
          scopeId: null,
        },
        {
          kind: 'FlowCreated',
          sessionId: 's1',
          seq: 2,
          tsNanos: 2000,
          flowId: 'f2',
          coroutineId: 'c1',
          flowType: 'SharedFlow',
          label: 'sharedFlow',
          scopeId: null,
        },
      ],
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useFlowEvents('session-1'))

    expect(result.current.flows).toHaveLength(2)
    expect(result.current.flows[0]!.flowId).toBe('f1')
    expect(result.current.flows[0]!.flowType).toBe('Cold')
    expect(result.current.flows[0]!.label).toBe('testFlow')
    expect(result.current.flows[0]!.coroutineId).toBe('c1')
    expect(result.current.flows[1]!.flowId).toBe('f2')
    expect(result.current.flows[1]!.flowType).toBe('SharedFlow')
    expect(result.current.flows[1]!.label).toBe('sharedFlow')
  })

  it('builds operator chain', () => {
    mockedUseSessionEvents.mockReturnValue({
      data: [
        {
          kind: 'FlowCreated',
          sessionId: 's1',
          seq: 1,
          tsNanos: 1000,
          flowId: 'f1',
          coroutineId: 'c1',
          flowType: 'Cold',
          label: 'numbers',
          scopeId: null,
        },
        {
          kind: 'FlowOperatorApplied',
          sessionId: 's1',
          seq: 2,
          tsNanos: 2000,
          flowId: 'f1',
          sourceFlowId: 'f1',
          operatorName: 'map',
          operatorIndex: 0,
          label: null,
          coroutineId: null,
        },
        {
          kind: 'FlowOperatorApplied',
          sessionId: 's1',
          seq: 3,
          tsNanos: 3000,
          flowId: 'f1',
          sourceFlowId: 'f1',
          operatorName: 'filter',
          operatorIndex: 1,
          label: null,
          coroutineId: null,
        },
      ],
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useFlowEvents('session-1'))

    const flow = result.current.flows[0]!
    expect(flow.operators).toHaveLength(2)
    expect(flow.operators[0]!.operatorName).toBe('map')
    expect(flow.operators[0]!.operatorIndex).toBe(0)
    expect(flow.operators[1]!.operatorName).toBe('filter')
    expect(flow.operators[1]!.operatorIndex).toBe(1)
  })

  it('tracks emissions per flow', () => {
    mockedUseSessionEvents.mockReturnValue({
      data: [
        {
          kind: 'FlowCreated',
          sessionId: 's1',
          seq: 1,
          tsNanos: 1000,
          flowId: 'f1',
          coroutineId: 'c1',
          flowType: 'Cold',
          label: 'numbers',
          scopeId: null,
        },
        {
          kind: 'FlowValueEmitted',
          sessionId: 's1',
          seq: 2,
          tsNanos: 2000,
          flowId: 'f1',
          coroutineId: 'c1',
          collectorId: 'col1',
          sequenceNumber: 0,
          valuePreview: '1',
          valueType: 'Int',
        },
        {
          kind: 'FlowValueEmitted',
          sessionId: 's1',
          seq: 3,
          tsNanos: 3000,
          flowId: 'f1',
          coroutineId: 'c1',
          collectorId: 'col1',
          sequenceNumber: 1,
          valuePreview: '2',
          valueType: 'Int',
        },
      ],
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useFlowEvents('session-1'))

    const flow = result.current.flows[0]!
    expect(flow.emissions).toHaveLength(2)
    expect(flow.emissions[0]!.valuePreview).toBe('1')
    expect(flow.emissions[1]!.valuePreview).toBe('2')
  })

  it('tracks collection lifecycle', () => {
    mockedUseSessionEvents.mockReturnValue({
      data: [
        {
          kind: 'FlowCreated',
          sessionId: 's1',
          seq: 1,
          tsNanos: 1000,
          flowId: 'f1',
          coroutineId: 'c1',
          flowType: 'Cold',
          label: null,
          scopeId: null,
        },
        {
          kind: 'FlowCollectionStarted',
          sessionId: 's1',
          seq: 2,
          tsNanos: 2000,
          flowId: 'f1',
          coroutineId: 'c1',
          collectorId: 'col1',
          label: null,
        },
        {
          kind: 'FlowCollectionCompleted',
          sessionId: 's1',
          seq: 3,
          tsNanos: 3000,
          flowId: 'f1',
          coroutineId: 'c1',
          collectorId: 'col1',
          totalEmissions: 5,
          durationNanos: 1000000,
        },
        {
          kind: 'FlowCollectionStarted',
          sessionId: 's1',
          seq: 4,
          tsNanos: 4000,
          flowId: 'f1',
          coroutineId: 'c2',
          collectorId: 'col2',
          label: null,
        },
        {
          kind: 'FlowCollectionCancelled',
          sessionId: 's1',
          seq: 5,
          tsNanos: 5000,
          flowId: 'f1',
          coroutineId: 'c2',
          collectorId: 'col2',
          reason: 'timeout',
          emittedCount: 2,
        },
      ],
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useFlowEvents('session-1'))

    const flow = result.current.flows[0]!
    expect(flow.collections.started).toBe(2)
    expect(flow.collections.completed).toBe(1)
    expect(flow.collections.cancelled).toBe(1)
  })

  it('detects backpressure', () => {
    mockedUseSessionEvents.mockReturnValue({
      data: [
        {
          kind: 'FlowCreated',
          sessionId: 's1',
          seq: 1,
          tsNanos: 1000,
          flowId: 'f1',
          coroutineId: 'c1',
          flowType: 'Cold',
          label: null,
          scopeId: null,
        },
        {
          kind: 'FlowBackpressure',
          sessionId: 's1',
          seq: 2,
          tsNanos: 2000,
          flowId: 'f1',
          collectorId: 'col1',
          reason: 'slow_collector',
          pendingEmissions: 5,
          bufferCapacity: 10,
          durationNanos: 500000,
          coroutineId: null,
        },
      ],
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useFlowEvents('session-1'))

    expect(result.current.hasBackpressure).toBe(true)
    const flow = result.current.flows[0]!
    expect(flow.backpressureEvents).toHaveLength(1)
    expect(flow.backpressureEvents[0]!.reason).toBe('slow_collector')
  })

  it('tracks value transformations and filters', () => {
    mockedUseSessionEvents.mockReturnValue({
      data: [
        {
          kind: 'FlowCreated',
          sessionId: 's1',
          seq: 1,
          tsNanos: 1000,
          flowId: 'f1',
          coroutineId: 'c1',
          flowType: 'Cold',
          label: null,
          scopeId: null,
        },
        {
          kind: 'FlowValueTransformed',
          sessionId: 's1',
          seq: 2,
          tsNanos: 2000,
          flowId: 'f1',
          operatorName: 'map',
          inputValuePreview: '5',
          outputValuePreview: '25',
          inputType: 'Int',
          outputType: 'Int',
          sequenceNumber: 0,
          coroutineId: null,
          collectorId: null,
        },
        {
          kind: 'FlowValueFiltered',
          sessionId: 's1',
          seq: 3,
          tsNanos: 3000,
          flowId: 'f1',
          operatorName: 'filter',
          valuePreview: '25',
          valueType: 'Int',
          passed: true,
          sequenceNumber: 0,
          coroutineId: null,
          collectorId: null,
        },
        {
          kind: 'FlowValueFiltered',
          sessionId: 's1',
          seq: 4,
          tsNanos: 4000,
          flowId: 'f1',
          operatorName: 'filter',
          valuePreview: '3',
          valueType: 'Int',
          passed: false,
          sequenceNumber: 1,
          coroutineId: null,
          collectorId: null,
        },
      ],
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useFlowEvents('session-1'))

    const flow = result.current.flows[0]!
    expect(flow.valueTraces).toHaveLength(3)

    // Transformation trace
    expect(flow.valueTraces[0]!.inputValue).toBe('5')
    expect(flow.valueTraces[0]!.transformedValue).toBe('25')
    expect(flow.valueTraces[0]!.operatorName).toBe('map')
    expect(flow.valueTraces[0]!.filtered).toBeNull()

    // Filter that passed -> filtered = false (passed = true -> !passed = false)
    expect(flow.valueTraces[1]!.inputValue).toBe('25')
    expect(flow.valueTraces[1]!.filtered).toBe(false)

    // Filter that did not pass -> filtered = true
    expect(flow.valueTraces[2]!.inputValue).toBe('3')
    expect(flow.valueTraces[2]!.filtered).toBe(true)
  })

  it('tracks SharedFlow subscriptions', () => {
    mockedUseSessionEvents.mockReturnValue({
      data: [
        {
          kind: 'FlowCreated',
          sessionId: 's1',
          seq: 1,
          tsNanos: 1000,
          flowId: 'f1',
          coroutineId: 'c1',
          flowType: 'SharedFlow',
          label: 'events',
          scopeId: null,
        },
        {
          kind: 'SharedFlowSubscription',
          sessionId: 's1',
          seq: 2,
          tsNanos: 2000,
          flowId: 'f1',
          collectorId: 'col1',
          action: 'subscribed',
          subscriberCount: 1,
          coroutineId: null,
          label: null,
        },
        {
          kind: 'SharedFlowSubscription',
          sessionId: 's1',
          seq: 3,
          tsNanos: 3000,
          flowId: 'f1',
          collectorId: 'col2',
          action: 'subscribed',
          subscriberCount: 2,
          coroutineId: null,
          label: null,
        },
        {
          kind: 'SharedFlowEmission',
          sessionId: 's1',
          seq: 4,
          tsNanos: 4000,
          flowId: 'f1',
          valuePreview: 'event-1',
          valueType: 'String',
          subscriberCount: 2,
          replayCache: 0,
          extraBufferCapacity: 0,
          coroutineId: null,
          label: null,
        },
      ],
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useFlowEvents('session-1'))

    const flow = result.current.flows[0]!
    expect(flow.flowType).toBe('SharedFlow')
    expect(flow.subscriberCount).toBe(2)
    expect(flow.sharedEmissions).toHaveLength(1)
    expect(flow.sharedEmissions[0]!.valuePreview).toBe('event-1')
  })

  it('groups events by flow', () => {
    mockedUseSessionEvents.mockReturnValue({
      data: [
        {
          kind: 'FlowCreated',
          sessionId: 's1',
          seq: 1,
          tsNanos: 1000,
          flowId: 'f1',
          coroutineId: 'c1',
          flowType: 'Cold',
          label: 'flow1',
          scopeId: null,
        },
        {
          kind: 'FlowCreated',
          sessionId: 's1',
          seq: 2,
          tsNanos: 2000,
          flowId: 'f2',
          coroutineId: 'c1',
          flowType: 'Cold',
          label: 'flow2',
          scopeId: null,
        },
        {
          kind: 'FlowValueEmitted',
          sessionId: 's1',
          seq: 3,
          tsNanos: 3000,
          flowId: 'f1',
          coroutineId: 'c1',
          collectorId: 'col1',
          sequenceNumber: 0,
          valuePreview: 'a',
          valueType: 'String',
        },
      ],
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useFlowEvents('session-1'))

    expect(result.current.eventsByFlow.size).toBe(2)
    expect(result.current.eventsByFlow.get('f1')).toHaveLength(2) // FlowCreated + FlowValueEmitted
    expect(result.current.eventsByFlow.get('f2')).toHaveLength(1) // FlowCreated
  })

  it('ignores non-flow events', () => {
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
          kind: 'ChannelCreated',
          sessionId: 's1',
          seq: 2,
          tsNanos: 2000,
          channelId: 'ch1',
          name: 'test',
          capacity: 10,
          channelType: 'BUFFERED',
        },
      ],
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useFlowEvents('session-1'))

    expect(result.current.flowEvents).toEqual([])
    expect(result.current.flows).toEqual([])
  })
})
