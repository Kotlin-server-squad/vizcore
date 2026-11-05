import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { JobPanel } from './JobPanel'
import { JobStateBadge } from './JobStateBadge'
import { JobHierarchyView } from './JobHierarchyView'
import { WaitingForChildrenCard } from './WaitingForChildrenCard'
import { apiClient } from '@/lib/api-client'
import type { VizEvent, WaitingForChildrenEvent } from '@/types/api'

vi.mock('@/lib/api-client', () => ({
  apiClient: {
    getSessionEvents: vi.fn(),
  },
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

function makeJobEvent(kind: string, seq: number, overrides: Record<string, unknown> = {}): VizEvent {
  return {
    sessionId: 'session-1',
    seq,
    tsNanos: seq * 1_000_000,
    kind: kind as VizEvent['kind'],
    coroutineId: 'c1',
    jobId: 'job-1',
    parentCoroutineId: null,
    scopeId: 'scope-1',
    label: null,
    ...overrides,
  } as unknown as VizEvent
}

describe('JobPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('reads the events it is given, so it follows the live stream', () => {
    mockedApiClient.getSessionEvents.mockResolvedValue([])
    const events = [
      makeJobEvent('JobStateChanged', 1, {
        jobId: 'job-1', coroutineId: 'c1', label: 'MainJob', isActive: true, isCompleted: false, isCancelled: false, childrenCount: 0,
      }),
    ]
    render(<JobPanel sessionId="session-1" events={events} />, { wrapper: createWrapper() })
    expect(screen.getByTestId('job-panel')).toBeInTheDocument()
  })

  it('renders empty state when no job events exist', async () => {
    mockedApiClient.getSessionEvents.mockResolvedValue([])

    render(<JobPanel sessionId="session-1" />, {
      wrapper: createWrapper(),
    })

    const emptyPanel = await screen.findByTestId('jobs-empty')
    expect(emptyPanel).toBeInTheDocument()
    expect(screen.getByText('No Job Activity')).toBeInTheDocument()
  })

  it('renders job panel with job events', async () => {
    const events = [
      makeJobEvent('JobStateChanged', 1, {
        jobId: 'job-1',
        coroutineId: 'c1',
        label: 'MainJob',
        isActive: true,
        isCompleted: false,
        isCancelled: false,
        childrenCount: 2,
      }),
      makeJobEvent('JobStateChanged', 2, {
        jobId: 'job-2',
        coroutineId: 'c2',
        parentCoroutineId: 'c1',
        label: 'ChildJob',
        isActive: true,
        isCompleted: false,
        isCancelled: false,
        childrenCount: 0,
      }),
    ]

    mockedApiClient.getSessionEvents.mockResolvedValue(events)

    render(<JobPanel sessionId="session-1" />, {
      wrapper: createWrapper(),
    })

    const panel = await screen.findByTestId('job-panel')
    expect(panel).toBeInTheDocument()
    expect(screen.getAllByText('MainJob').length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText('ChildJob').length).toBeGreaterThanOrEqual(1)
  })

  it('shows structured concurrency section for WaitingForChildren', async () => {
    const events = [
      makeJobEvent('JobStateChanged', 1, {
        jobId: 'job-1',
        coroutineId: 'c1',
        label: 'Parent',
        isActive: true,
        isCompleted: false,
        isCancelled: false,
        childrenCount: 1,
      }),
      makeJobEvent('WaitingForChildren', 2, {
        coroutineId: 'c1',
        activeChildrenCount: 1,
        activeChildrenIds: ['c2'],
        label: 'Parent',
      }),
    ]

    mockedApiClient.getSessionEvents.mockResolvedValue(events)

    render(<JobPanel sessionId="session-1" />, {
      wrapper: createWrapper(),
    })

    const panel = await screen.findByTestId('job-panel')
    expect(panel).toBeInTheDocument()
    expect(screen.getByText('Structured Concurrency')).toBeInTheDocument()
  })
})

describe('JobStateBadge', () => {
  it('renders Active badge with success color', () => {
    render(<JobStateBadge status="Active" />)
    const badge = screen.getByTestId('job-state-badge')
    expect(badge).toHaveTextContent('Active')
  })

  it('renders Cancelled badge', () => {
    render(<JobStateBadge status="Cancelled" />)
    const badge = screen.getByTestId('job-state-badge')
    expect(badge).toHaveTextContent('Cancelled')
  })

  it('renders Completed badge', () => {
    render(<JobStateBadge status="Completed" />)
    const badge = screen.getByTestId('job-state-badge')
    expect(badge).toHaveTextContent('Completed')
  })
})

describe('JobHierarchyView', () => {
  it('renders "No job hierarchy" when empty', () => {
    render(<JobHierarchyView jobs={[]} roots={[]} />)
    expect(screen.getByTestId('no-hierarchy')).toBeInTheDocument()
  })

  it('renders tree nodes with blocking indicators', () => {
    const jobs = [
      {
        jobId: 'job-1',
        coroutineId: 'c1',
        label: 'Root',
        parentCoroutineId: null,
        status: 'Active' as const,
        isActive: true,
        isCompleted: false,
        isCancelled: false,
        childrenCount: 1,
        waitingForChildren: ['c2'],
        activeChildrenCount: 1,
        joiners: [],
        cancellationRequested: false,
        cancellationCause: null,
      },
      {
        jobId: 'job-2',
        coroutineId: 'c2',
        label: 'Child',
        parentCoroutineId: 'c1',
        status: 'Active' as const,
        isActive: true,
        isCompleted: false,
        isCancelled: false,
        childrenCount: 0,
        waitingForChildren: [],
        activeChildrenCount: 0,
        joiners: [],
        cancellationRequested: false,
        cancellationCause: null,
      },
    ]
    const roots = [jobs[0]!]

    render(<JobHierarchyView jobs={jobs} roots={roots} />)

    expect(screen.getByTestId('job-hierarchy-view')).toBeInTheDocument()
    const nodes = screen.getAllByTestId('job-tree-node')
    expect(nodes.length).toBeGreaterThanOrEqual(2)
    expect(screen.getByTestId('blocking-indicator')).toBeInTheDocument()
  })
})

describe('WaitingForChildrenCard', () => {
  it('renders parent and children info', () => {
    const event: WaitingForChildrenEvent = {
      sessionId: 's1',
      seq: 1,
      tsNanos: 1000000,
      kind: 'WaitingForChildren',
      coroutineId: 'c1',
      jobId: 'j1',
      parentCoroutineId: null,
      scopeId: 'scope-1',
      label: 'ParentCo',
      activeChildrenCount: 2,
      activeChildrenIds: ['c2', 'c3'],
    }

    render(<WaitingForChildrenCard event={event} />)
    expect(screen.getByTestId('waiting-for-children-card')).toBeInTheDocument()
    expect(screen.getByText(/ParentCo/)).toBeInTheDocument()
    expect(screen.getByText('c2')).toBeInTheDocument()
    expect(screen.getByText('c3')).toBeInTheDocument()
  })
})
