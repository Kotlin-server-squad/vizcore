import { describe, it, expect, vi, beforeEach } from 'vitest'
import { act, render, renderHook, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApiError } from '@/lib/api-errors'

const navigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({ useNavigate: () => navigate }))

const runScenario = vi.fn()
const deleteSession = vi.fn()
const createSession = vi.fn()
vi.mock('@/hooks/use-scenarios', () => ({
  useRunScenario: () => ({ mutateAsync: runScenario, isPending: false }),
}))
vi.mock('@/hooks/use-sessions', () => ({
  useCreateSession: () => ({ mutateAsync: createSession, isPending: false }),
  useDeleteSession: () => ({ mutateAsync: deleteSession, isPending: false }),
}))

import { useScenarioActions } from './use-scenario-actions'
import { RunScenarioButton } from './RunScenarioButton'

const args = (over = {}) => ({
  sessionId: 's-1',
  scenarioId: 'sync/mutex/deadlock-demo',
  scenarioName: 'Deadlock',
  onClearEvents: vi.fn(),
  onRefetch: vi.fn(),
  ...over,
})

beforeEach(() => vi.clearAllMocks())

describe('useScenarioActions', () => {
  it('runs the demo the session was opened for, in that session', async () => {
    runScenario.mockResolvedValue({ sessionId: 's-1' })
    const { result } = renderHook(() => useScenarioActions(args()))
    await act(() => result.current.run())
    expect(runScenario).toHaveBeenCalledWith({ scenarioId: 'sync/mutex/deadlock-demo', sessionId: 's-1' })
  })

  it('says why a run failed, e.g. the server is busy (#139)', async () => {
    runScenario.mockRejectedValue(
      new ApiError(503, 'Server busy: too many demos running, try again in a few seconds', 'demo-limit'),
    )
    const { result } = renderHook(() => useScenarioActions(args()))
    await act(() => result.current.run())
    expect(result.current.errorText).toBe(
      'Could not run the scenario: Server busy: too many demos running, try again in a few seconds',
    )
  })

  it('clears the live events without deleting the session (WR-05)', () => {
    const a = args()
    const { result } = renderHook(() => useScenarioActions(a))
    act(() => result.current.clear())
    expect(a.onClearEvents).toHaveBeenCalledTimes(1)
    expect(deleteSession).not.toHaveBeenCalled()
  })

  it('resets: deletes this session and opens a fresh one for the same demo', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    createSession.mockResolvedValue({ sessionId: 's-2' })
    const { result } = renderHook(() => useScenarioActions(args()))
    await act(() => result.current.reset())
    expect(deleteSession).toHaveBeenCalledWith('s-1')
    expect(navigate).toHaveBeenCalledWith({
      to: '/sessions/$sessionId',
      params: { sessionId: 's-2' },
      search: { scenarioId: 'sync/mutex/deadlock-demo', scenarioName: 'Deadlock' },
    })
  })

  it('does nothing on reset when the user cancels the confirmation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const { result } = renderHook(() => useScenarioActions(args()))
    await act(() => result.current.reset())
    expect(deleteSession).not.toHaveBeenCalled()
  })
})

describe('RunScenarioButton — today\'s Run button, kept until Run again (Part 9)', () => {
  it.each([
    ['notStarted', 'Run Scenario', false],
    ['running', 'Scenario Running', true],
    ['finished', 'Scenario Finished', true],
    ['failed', 'Scenario Failed', true],
  ] as const)('%s reads %s', (outcome, label, disabled) => {
    render(<RunScenarioButton outcome={outcome} isPending={false} onRun={vi.fn()} />)
    const button = screen.getByRole('button', { name: label })
    if (disabled) expect(button).toBeDisabled()
    else expect(button).toBeEnabled()
  })

  it('runs on press', async () => {
    const onRun = vi.fn()
    render(<RunScenarioButton outcome="notStarted" isPending={false} onRun={onRun} />)
    await userEvent.click(screen.getByRole('button', { name: 'Run Scenario' }))
    expect(onRun).toHaveBeenCalledTimes(1)
  })
})
