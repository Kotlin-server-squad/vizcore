import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { apiClient } from '@/lib/api-client'
import { LookExperimentResultsPage } from './looks'

vi.mock('@/components/Layout', () => ({ Layout: ({ children }: { children: ReactNode }) => <>{children}</> }))

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <LookExperimentResultsPage />
    </QueryClientProvider>
  )
}

afterEach(() => vi.restoreAllMocks())

describe('/experiments/looks', () => {
  it('shows the results from the server', async () => {
    vi.spyOn(apiClient, 'getLookExperimentResults').mockResolvedValue({
      enabled: true,
      storage: 'database',
      startedAt: null,
      day: null,
      rule: { minVotesPerLook: 30, maxDays: 14, clearlyWorseSwitchAwayPoints: 10, tooCloseRating: 0.3 },
      decision: { status: 'NOT_STARTED', text: 'Not started: nobody has seen a look in the experiment yet.', winner: null, keep: [] },
      tooLittleData: true,
      warning: 'Too little data yet: these numbers can still change a lot.',
      looks: [],
    })
    renderPage()
    expect(await screen.findByRole('status')).toHaveTextContent('Not started')
  })

  it('says when they cannot be loaded', async () => {
    vi.spyOn(apiClient, 'getLookExperimentResults').mockRejectedValue(new Error('HTTP 500'))
    renderPage()
    expect(await screen.findByRole('alert')).toHaveTextContent('HTTP 500')
  })
})
