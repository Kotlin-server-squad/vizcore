import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { FlowPanel } from './FlowPanel'
import { FlowOperatorChain } from './FlowOperatorChain'
import { FlowBackpressureIndicator } from './FlowBackpressureIndicator'
import { FlowValueTrace } from './FlowValueTrace'
import { apiClient } from '@/lib/api-client'
import type { VizEvent, FlowBackpressure } from '@/types/api'

vi.mock('@/lib/api-client', () => ({
  apiClient: {
    getSessionEvents: vi.fn(),
  },
}))

vi.mock('@/lib/animation-throttle', () => ({
  useAnimationSlot: () => false,
}))

const mockedApiClient = vi.mocked(apiClient)

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        gcTime: 0,
      },
    },
  })
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        {children}
      </QueryClientProvider>
    )
  }
}

function makeFlowEvent(kind: string, seq: number, overrides: Record<string, unknown> = {}): VizEvent {
  return {
    sessionId: 'session-1',
    seq,
    tsNanos: seq * 1_000_000,
    kind: kind as VizEvent['kind'],
    flowId: 'flow-1',
    ...overrides,
  } as unknown as VizEvent
}

describe('FlowPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('reads the events it is given, so it follows the live stream', () => {
    mockedApiClient.getSessionEvents.mockResolvedValue([])
    const events = [
      makeFlowEvent('FlowCreated', 1, { flowId: 'flow-1', coroutineId: 'c1', flowType: 'Cold', label: 'TestFlow', scopeId: 'scope-1' }),
    ]
    render(<FlowPanel sessionId="session-1" events={events} />, { wrapper: createWrapper() })
    expect(screen.getByTestId('flow-panel')).toBeInTheDocument()
  })

  it('renders empty state when no flow events exist', async () => {
    mockedApiClient.getSessionEvents.mockResolvedValue([])

    render(<FlowPanel sessionId="session-1" />, {
      wrapper: createWrapper(),
    })

    const emptyPanel = await screen.findByTestId('flow-empty')
    expect(emptyPanel).toBeInTheDocument()
    expect(screen.getByText('No Flow Activity')).toBeInTheDocument()
  })

  it('renders flow panel with flow events', async () => {
    const events = [
      makeFlowEvent('FlowCreated', 1, {
        flowId: 'flow-1',
        coroutineId: 'c1',
        flowType: 'Cold',
        label: 'TestFlow',
        scopeId: 'scope-1',
      }),
      makeFlowEvent('FlowOperatorApplied', 2, {
        flowId: 'flow-1',
        sourceFlowId: 'flow-0',
        operatorName: 'map',
        operatorIndex: 0,
        label: null,
        coroutineId: 'c1',
      }),
      makeFlowEvent('FlowValueEmitted', 3, {
        flowId: 'flow-1',
        coroutineId: 'c1',
        collectorId: 'collector-1',
        sequenceNumber: 0,
        valuePreview: '42',
        valueType: 'Int',
      }),
    ]

    mockedApiClient.getSessionEvents.mockResolvedValue(events)

    render(<FlowPanel sessionId="session-1" />, {
      wrapper: createWrapper(),
    })

    const panel = await screen.findByTestId('flow-panel')
    expect(panel).toBeInTheDocument()
    expect(screen.getAllByText('TestFlow').length).toBeGreaterThanOrEqual(1)
  })

  it('shows backpressure count in summary', async () => {
    const events = [
      makeFlowEvent('FlowCreated', 1, {
        flowId: 'flow-1',
        coroutineId: 'c1',
        flowType: 'Cold',
        label: 'BPFlow',
        scopeId: null,
      }),
      makeFlowEvent('FlowBackpressure', 2, {
        flowId: 'flow-1',
        collectorId: 'col-1',
        reason: 'slow_collector',
        pendingEmissions: 5,
        bufferCapacity: 10,
        durationNanos: null,
        coroutineId: 'c1',
      }),
    ]

    mockedApiClient.getSessionEvents.mockResolvedValue(events)

    render(<FlowPanel sessionId="session-1" />, {
      wrapper: createWrapper(),
    })

    const panel = await screen.findByTestId('flow-panel')
    expect(panel).toBeInTheDocument()
    // Backpressure stat
    expect(screen.getByText('Backpressure')).toBeInTheDocument()
  })
})

describe('FlowOperatorChain', () => {
  it('renders "No operators applied" when empty', () => {
    render(<FlowOperatorChain operators={[]} flowLabel={null} />)
    expect(screen.getByTestId('no-operators')).toBeInTheDocument()
  })

  it('renders operator chain in order', () => {
    const operators = [
      { operatorName: 'filter', operatorIndex: 1, flowId: 'f1', sourceFlowId: 'f0', label: null },
      { operatorName: 'map', operatorIndex: 0, flowId: 'f1', sourceFlowId: 'f0', label: null },
    ]

    render(<FlowOperatorChain operators={operators} flowLabel="MyFlow" />)

    const chain = screen.getByTestId('flow-operator-chain')
    expect(chain).toBeInTheDocument()
    // Both operators present
    expect(screen.getByText('map')).toBeInTheDocument()
    expect(screen.getByText('filter')).toBeInTheDocument()
    // Source and collect
    expect(screen.getByText('source')).toBeInTheDocument()
    expect(screen.getByText('collect')).toBeInTheDocument()
  })
})

describe('FlowBackpressureIndicator', () => {
  it('renders nothing when no events', () => {
    const { container } = render(<FlowBackpressureIndicator events={[]} />)
    expect(container.firstChild).toBeNull()
  })

  it('renders warning badge with event count', () => {
    const events: FlowBackpressure[] = [
      {
        kind: 'FlowBackpressure',
        sessionId: 's1',
        seq: 1,
        tsNanos: 1000000,
        flowId: 'f1',
        collectorId: 'col-1',
        reason: 'buffer_full',
        pendingEmissions: 3,
        bufferCapacity: 10,
        durationNanos: null,
        coroutineId: 'c1',
      },
    ]

    render(<FlowBackpressureIndicator events={events} />)
    expect(screen.getByTestId('backpressure-badge')).toBeInTheDocument()
    expect(screen.getByText(/Backpressure.*1 event/)).toBeInTheDocument()
  })
})

describe('FlowValueTrace', () => {
  it('renders "No value traces" when empty', () => {
    render(<FlowValueTrace traces={[]} />)
    expect(screen.getByTestId('no-traces')).toBeInTheDocument()
  })

  it('renders traces with transformation arrows', () => {
    const traces = [
      {
        sequenceNumber: 0,
        inputValue: '1',
        transformedValue: '2',
        filtered: null,
        operatorName: 'map',
      },
    ]

    render(<FlowValueTrace traces={traces} />)
    expect(screen.getByTestId('flow-value-trace')).toBeInTheDocument()
    expect(screen.getAllByTestId('trace-row')).toHaveLength(1)
    expect(screen.getByText('1')).toBeInTheDocument()
    expect(screen.getByText('2')).toBeInTheDocument()
  })
})
