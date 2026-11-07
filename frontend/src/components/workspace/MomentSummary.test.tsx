import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { MomentSummary } from './MomentSummary'

const getMetrics = vi.fn()
vi.mock('@/lib/api-client', () => ({ apiClient: { getMetrics: (...a: unknown[]) => getMetrics(...a) } }))

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={new QueryClient()}>{children}</QueryClientProvider>
}

const metrics = { active: 3, unknown: 0, peak: 5, throughputPerSec: 2.5, dispatcherUtilization: { 'Dispatchers.IO': 3 } }

describe('MomentSummary', () => {
  it('shows the metrics of the moment, named as such, and fetches nothing', () => {
    render(
      <MomentSummary metrics={metrics} moment={{ kind: 'replay', atNanos: 4.2e9, index: 9, total: 20 }} coroutineCount={7} incomplete={false} />,
      { wrapper },
    )
    expect(screen.getByTestId('moment-note')).toHaveTextContent('At 4.2 s · event 10 of 20')
    expect(screen.getByTestId('moment-summary')).toHaveTextContent('7 coroutines at this moment')
    expect(screen.getByText('Active').closest('dl')).toHaveTextContent('3')
    expect(screen.getByText('Peak').closest('dl')).toHaveTextContent('5')
    expect(screen.getByText(/Dispatchers\.IO: 3/)).toBeInTheDocument()
    expect(screen.getByText(/long-lived coroutines are not evaluated at a moment/i)).toBeInTheDocument()
    expect(screen.getByText(/on the app's clock/i)).toBeInTheDocument()
    expect(getMetrics).not.toHaveBeenCalled()
  })

  it('says peak and throughput cover the replayed events only when the history is incomplete, and counts unknown states', () => {
    render(
      <MomentSummary metrics={{ ...metrics, unknown: 2 }} moment={{ kind: 'loaded', seq: 40 }} coroutineCount={7} incomplete />,
      { wrapper },
    )
    expect(screen.getByText(/in the kept events only/i)).toBeInTheDocument()
    expect(screen.getByText(/2 with their state unknown/i)).toBeInTheDocument()
  })
})
