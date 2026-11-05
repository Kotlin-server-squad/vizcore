import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HeroUIProvider } from '@heroui/react'
import { ApiError } from '@/lib/api-errors'
import type { SessionSnapshot } from '@/types/api'

vi.mock('@tanstack/react-router', () => ({ useNavigate: () => vi.fn() }))

const runScenario = vi.fn()
vi.mock('@/hooks/use-scenarios', () => ({
  useRunScenario: () => ({ mutateAsync: runScenario, isPending: false }),
}))
vi.mock('@/hooks/use-sessions', () => ({
  useCreateSession: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useDeleteSession: () => ({ mutateAsync: vi.fn(), isPending: false }),
}))

import { ScenarioControls } from './ScenarioControls'

const notStarted = { sessionId: 's-1', coroutineCount: 0, coroutines: [] } as unknown as SessionSnapshot

const renderControls = (scenarioId: string) =>
  render(
    <HeroUIProvider>
      <ScenarioControls
        sessionId="s-1"
        scenarioId={scenarioId}
        session={notStarted}
        onClearEvents={vi.fn()}
        onRefetch={vi.fn()}
      />
    </HeroUIProvider>,
  )

beforeEach(() => vi.clearAllMocks())

describe('ScenarioControls', () => {
  it('runs the demo the session was opened for, in that session', async () => {
    runScenario.mockResolvedValue({ sessionId: 's-1' })
    renderControls('sync/mutex/deadlock-demo')
    await userEvent.click(screen.getByRole('button', { name: /run scenario/i }))
    expect(runScenario).toHaveBeenCalledWith({ scenarioId: 'sync/mutex/deadlock-demo', sessionId: 's-1' })
  })

  it('tells the visitor the server is busy when the demo limit is reached', async () => {
    runScenario.mockRejectedValue(
      new ApiError(503, 'Server busy: too many demos running, try again in a few seconds', 'demo-limit'),
    )
    renderControls('flow/simple')
    await userEvent.click(screen.getByRole('button', { name: /run scenario/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Could not run the scenario: Server busy: too many demos running, try again in a few seconds',
    )
  })

  const finishedWith = (...states: string[]) =>
    ({
      sessionId: 's-1',
      coroutineCount: states.length,
      coroutines: states.map((state, i) => ({
        id: `c${i}`, jobId: `j${i}`, parentId: null, scopeId: 's', label: `c${i}`, state,
      })),
    }) as unknown as SessionSnapshot

  const renderWith = (session: SessionSnapshot) =>
    render(
      <HeroUIProvider>
        <ScenarioControls
          sessionId="s-1"
          scenarioId="exception-propagation"
          session={session}
          onClearEvents={vi.fn()}
          onRefetch={vi.fn()}
        />
      </HeroUIProvider>,
    )

  it('reads Finished when every coroutine completed or was cancelled', () => {
    renderWith(finishedWith('COMPLETED', 'CANCELLED'))
    expect(screen.getByText('Scenario finished')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /scenario finished/i })).toBeDisabled()
  })

  it('reads Failed in red — never green Completed — when any coroutine failed', () => {
    renderWith(finishedWith('COMPLETED', 'FAILED', 'CANCELLED'))
    expect(screen.getByText('Scenario failed: 1 coroutine failed')).toBeInTheDocument()
    const button = screen.getByRole('button', { name: /scenario failed/i })
    expect(button).toBeDisabled()
    expect(button.className).toMatch(/danger/)
    expect(button.className).not.toMatch(/success/)
    expect(screen.queryByText(/completed/i)).toBeNull()
  })

  it('reads Failed as soon as a coroutine failed, even while others still run', () => {
    renderWith(finishedWith('FAILED', 'ACTIVE'))
    expect(screen.getByText('Scenario failed: 1 coroutine failed')).toBeInTheDocument()
    expect(screen.queryByText('Scenario is running')).toBeNull()

    renderWith(finishedWith('FAILED', 'WAITING_FOR_CHILDREN'))
    expect(screen.getAllByText('Scenario failed: 1 coroutine failed')).toHaveLength(2)
  })

  it('counts several failures in the plural', () => {
    renderWith(finishedWith('FAILED', 'FAILED'))
    expect(screen.getByText('Scenario failed: 2 coroutines failed')).toBeInTheDocument()
  })
})
