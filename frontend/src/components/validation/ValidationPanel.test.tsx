import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { ValidationPanel } from './ValidationPanel'
import {
  ValidationPassCard,
  ValidationErrorCard,
} from './ValidationResultCard'
import { TimingReportView } from './TimingReportView'
import { apiClient } from '@/lib/api-client'
import type { ValidationResponse, ValidationRuleResult, BackendTimingReport } from '@/types/api'

vi.mock('@/lib/api-client', () => ({
  apiClient: {
    validateSession: vi.fn(),
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
      mutations: {
        retry: false,
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

describe('ValidationPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders empty state with run button initially', () => {
    render(<ValidationPanel sessionId="session-1" />, {
      wrapper: createWrapper(),
    })

    expect(screen.getByTestId('validation-panel')).toBeInTheDocument()
    expect(screen.getByTestId('run-validation-btn')).toBeInTheDocument()
    expect(screen.getByTestId('validation-empty')).toBeInTheDocument()
  })

  it('calls validation API when button is clicked', async () => {
    const result: ValidationResponse = {
      sessionId: 'session-1',
      results: [
        { type: 'Fail', ruleName: 'NO_ORPHAN_EVENTS', message: 'Event 5 has no parent' },
        { type: 'Pass', ruleName: 'LIFECYCLE_ORDER', message: 'All lifecycle events in order' },
      ],
      timing: {
        coroutineDurations: { 'main-coroutine': 150, 'child-1': 80 },
        suspensionDurations: { 'main-coroutine': [10, 25, 5] },
        totalDuration: 230,
      },
    }

    mockedApiClient.validateSession.mockResolvedValue(result)

    render(<ValidationPanel sessionId="session-1" />, {
      wrapper: createWrapper(),
    })

    const button = screen.getByTestId('run-validation-btn')
    fireEvent.click(button)

    await waitFor(() => {
      expect(mockedApiClient.validateSession).toHaveBeenCalledWith('session-1')
    })

    // Should show summary
    await waitFor(() => {
      expect(screen.getByTestId('validation-summary')).toBeInTheDocument()
    })
    expect(screen.getByText(/1 Rule.*Failed/)).toBeInTheDocument()
  })

  it('shows error state when validation API fails', async () => {
    mockedApiClient.validateSession.mockRejectedValue(new Error('Server error'))

    render(<ValidationPanel sessionId="session-1" />, {
      wrapper: createWrapper(),
    })

    fireEvent.click(screen.getByTestId('run-validation-btn'))

    await waitFor(() => {
      expect(screen.getByTestId('validation-error')).toBeInTheDocument()
    })
    expect(screen.getByText(/Server error/)).toBeInTheDocument()
  })

  it('renders Failures section for Fail results', async () => {
    const result: ValidationResponse = {
      sessionId: 'session-1',
      results: [
        { type: 'Fail', ruleName: 'NO_ORPHAN_EVENTS', message: 'Event 5 has no parent' },
      ],
      timing: {
        coroutineDurations: { 'coroutine-1': 100 },
        suspensionDurations: {},
        totalDuration: 100,
      },
    }

    mockedApiClient.validateSession.mockResolvedValue(result)

    render(<ValidationPanel sessionId="session-1" />, {
      wrapper: createWrapper(),
    })

    fireEvent.click(screen.getByTestId('run-validation-btn'))

    await waitFor(() => {
      expect(screen.getByTestId('validation-error-card')).toBeInTheDocument()
    })
    expect(screen.getByText('NO_ORPHAN_EVENTS')).toBeInTheDocument()
  })

  it('renders Passes section as compact list for Pass results', async () => {
    const result: ValidationResponse = {
      sessionId: 'session-1',
      results: [
        { type: 'Pass', ruleName: 'LIFECYCLE_ORDER', message: 'All lifecycle events in order' },
      ],
      timing: {
        coroutineDurations: { 'coroutine-1': 100 },
        suspensionDurations: {},
        totalDuration: 100,
      },
    }

    mockedApiClient.validateSession.mockResolvedValue(result)

    render(<ValidationPanel sessionId="session-1" />, {
      wrapper: createWrapper(),
    })

    fireEvent.click(screen.getByTestId('run-validation-btn'))

    await waitFor(() => {
      expect(screen.getByText('All Rules Passed')).toBeInTheDocument()
    })
    expect(screen.getByText('LIFECYCLE_ORDER')).toBeInTheDocument()
  })
})

describe('ValidationPassCard', () => {
  it('renders pass state with green check when no failures', () => {
    render(<ValidationPassCard failCount={0} totalCount={21} />)
    expect(screen.getByTestId('validation-summary')).toBeInTheDocument()
    expect(screen.getByText('All Rules Passed')).toBeInTheDocument()
    expect(screen.getByText('21 rules checked')).toBeInTheDocument()
  })

  it('renders fail state with failure count', () => {
    render(<ValidationPassCard failCount={3} totalCount={21} />)
    expect(screen.getByTestId('validation-summary')).toBeInTheDocument()
    expect(screen.getByText(/3 Rule.*Failed/)).toBeInTheDocument()
    expect(screen.getByText(/3 of 21 rules failed/)).toBeInTheDocument()
  })
})

describe('ValidationErrorCard', () => {
  it('renders fail result with ruleName and message', () => {
    const result: ValidationRuleResult = {
      type: 'Fail',
      ruleName: 'NO_ORPHAN_EVENTS',
      message: 'Event 5 has no parent',
    }

    render(<ValidationErrorCard error={result} />)
    expect(screen.getByTestId('validation-error-card')).toBeInTheDocument()
    expect(screen.getByText('NO_ORPHAN_EVENTS')).toBeInTheDocument()
    expect(screen.getByText('Event 5 has no parent')).toBeInTheDocument()
  })

  it('renders details when present', () => {
    const result: ValidationRuleResult = {
      type: 'Fail',
      ruleName: 'NO_ORPHAN_EVENTS',
      message: 'Event 5 has no parent',
      details: 'Expected parent event seq 4 but found none',
    }

    render(<ValidationErrorCard error={result} />)
    expect(screen.getByText('Expected parent event seq 4 but found none')).toBeInTheDocument()
  })
})

describe('TimingReportView', () => {
  it('renders timing metrics with coroutine duration rows', () => {
    const timing: BackendTimingReport = {
      coroutineDurations: { 'main-coroutine': 150, 'child-1': 80 },
      suspensionDurations: { 'main-coroutine': [10, 25, 5] },
      totalDuration: 230,
    }

    render(<TimingReportView timing={timing} />)
    expect(screen.getByTestId('timing-report')).toBeInTheDocument()
    expect(screen.getByText('230ms')).toBeInTheDocument() // total duration
    expect(screen.getByText('Total Duration')).toBeInTheDocument()
    // Coroutine duration rows — one per entry in coroutineDurations
    const rows = screen.getAllByTestId('timing-coroutine-row')
    expect(rows).toHaveLength(2)
  })

  it('renders suspension durations section when data is present', () => {
    const timing: BackendTimingReport = {
      coroutineDurations: { 'coroutine-1': 1000 },
      suspensionDurations: { 'coroutine-1': [500, 200] },
      totalDuration: 1000,
    }

    render(<TimingReportView timing={timing} />)
    expect(screen.getByText('Suspension Durations')).toBeInTheDocument()
    expect(screen.getByText(/2 suspensions, max: 500ms/)).toBeInTheDocument()
  })

  it('formats duration >= 1000ms as seconds', () => {
    const timing: BackendTimingReport = {
      coroutineDurations: {},
      suspensionDurations: {},
      totalDuration: 2500,
    }

    render(<TimingReportView timing={timing} />)
    expect(screen.getByText('2.50s')).toBeInTheDocument()
  })
})
