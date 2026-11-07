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
    expect(createSession).toHaveBeenCalledWith({ name: 'scenario-Deadlock', demoId: 'sync/mutex/deadlock-demo' })
    expect(navigate).toHaveBeenCalledWith({
      to: '/sessions/$sessionId',
      params: { sessionId: 's-2' },
      search: { scenarioId: 'sync/mutex/deadlock-demo', scenarioName: 'Deadlock' },
    })
  })

  it('runs it again in a new session for the same demo, which starts the run (S-13)', async () => {
    createSession.mockResolvedValue({ sessionId: 's-2' })
    const { result } = renderHook(() => useScenarioActions(args()))
    await act(() => result.current.runAgain())
    expect(createSession).toHaveBeenCalledWith({ name: 'scenario-Deadlock', demoId: 'sync/mutex/deadlock-demo' })
    expect(navigate).toHaveBeenCalledWith({
      to: '/sessions/$sessionId',
      params: { sessionId: 's-2' },
      search: { scenarioId: 'sync/mutex/deadlock-demo', scenarioName: 'Deadlock', run: true },
    })
  })

  it('leaves the old session untouched when it runs again', async () => {
    createSession.mockResolvedValue({ sessionId: 's-2' })
    const a = args()
    const { result } = renderHook(() => useScenarioActions(a))
    await act(() => result.current.runAgain())
    expect(deleteSession).not.toHaveBeenCalled()
    expect(runScenario).not.toHaveBeenCalled()
    expect(a.onClearEvents).not.toHaveBeenCalled()
  })

  it('names the new session from the session id when the address has no name', async () => {
    createSession.mockResolvedValue({ sessionId: 's-2' })
    const { result } = renderHook(() =>
      useScenarioActions(args({ sessionId: 'scenario-Nested-Product-Page-1793993830683', scenarioName: undefined, scenarioId: 'nested' })),
    )
    await act(() => result.current.runAgain())
    expect(createSession).toHaveBeenCalledWith({ name: 'scenario-Nested Product Page', demoId: 'nested' })
  })

  it('says why it could not run again, and stays', async () => {
    createSession.mockRejectedValue(new ApiError(429, 'Too many sessions created, wait a minute'))
    const { result } = renderHook(() => useScenarioActions(args()))
    await act(() => result.current.runAgain())
    expect(navigate).not.toHaveBeenCalled()
    expect(result.current.errorText).toBe('Could not run it again: Too many sessions created, wait a minute')
  })

  it('does nothing on reset when the user cancels the confirmation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const { result } = renderHook(() => useScenarioActions(args()))
    await act(() => result.current.reset())
    expect(deleteSession).not.toHaveBeenCalled()
  })
})

describe('RunScenarioButton — Run Scenario, then Run again (S-13)', () => {
  it.each([
    ['notStarted', 'Run Scenario'],
    ['running', 'Run again'],
    ['finished', 'Run again'],
    ['failed', 'Run again'],
  ] as const)('%s reads %s, enabled', (outcome, label) => {
    render(<RunScenarioButton outcome={outcome} isPending={false} onRun={vi.fn()} onRunAgain={vi.fn()} />)
    expect(screen.getByRole('button', { name: label })).toBeEnabled()
  })

  it('runs on press before the run', async () => {
    const onRun = vi.fn()
    const onRunAgain = vi.fn()
    render(<RunScenarioButton outcome="notStarted" isPending={false} onRun={onRun} onRunAgain={onRunAgain} />)
    await userEvent.click(screen.getByRole('button', { name: 'Run Scenario' }))
    expect(onRun).toHaveBeenCalledTimes(1)
    expect(onRunAgain).not.toHaveBeenCalled()
  })

  it('runs again on press once the run has started', async () => {
    const onRun = vi.fn()
    const onRunAgain = vi.fn()
    render(<RunScenarioButton outcome="finished" isPending={false} onRun={onRun} onRunAgain={onRunAgain} />)
    const button = screen.getByRole('button', { name: 'Run again' })
    expect(button).toHaveAttribute('data-look-part', 'run-again')
    await userEvent.click(button)
    expect(onRunAgain).toHaveBeenCalledTimes(1)
    expect(onRun).not.toHaveBeenCalled()
  })
})
