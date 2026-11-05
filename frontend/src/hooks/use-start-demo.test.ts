import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'

const navigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({ useNavigate: () => navigate }))

const mutateAsync = vi.fn()
vi.mock('@/hooks/use-sessions', () => ({ useCreateSession: () => ({ mutateAsync }) }))

import { useStartDemo } from './use-start-demo'
import type { Scenario } from '@/types/api'

const nested = { id: 'nested', name: 'Nested: Product Page', description: '' } as Scenario

beforeEach(() => {
  vi.clearAllMocks()
  mutateAsync.mockResolvedValue({ sessionId: 'sid-1' })
})

describe('useStartDemo', () => {
  it('mints a scenario- session and opens it with the demo to run', async () => {
    const { result } = renderHook(() => useStartDemo())
    await act(() => result.current.start(nested))

    expect(mutateAsync).toHaveBeenCalledWith('scenario-Nested: Product Page')
    expect(navigate).toHaveBeenCalledWith({
      to: '/sessions/$sessionId',
      params: { sessionId: 'sid-1' },
      search: { scenarioId: 'nested', scenarioName: 'Nested: Product Page' },
    })
  })

  it('calls onStarted before it navigates', async () => {
    const order: string[] = []
    const onStarted = vi.fn(() => order.push('started'))
    navigate.mockImplementation(() => order.push('navigate'))
    const { result } = renderHook(() => useStartDemo(onStarted))
    await act(() => result.current.start(nested))
    expect(order).toEqual(['started', 'navigate'])
  })

  it('marks the demo as preparing while the session is created', async () => {
    let finish: (v: { sessionId: string }) => void = () => {}
    mutateAsync.mockReturnValue(new Promise((resolve) => (finish = resolve)))
    const { result } = renderHook(() => useStartDemo())

    let pending: Promise<void> = Promise.resolve()
    act(() => {
      pending = result.current.start(nested)
    })
    expect(result.current.preparing).toBe('nested')
    await act(async () => {
      finish({ sessionId: 'sid-1' })
      await pending
    })
    expect(result.current.preparing).toBeNull()
  })

  it('keeps a failed start as an error instead of rejecting, and clears it on the next start', async () => {
    const failure = new Error('rate limited')
    mutateAsync.mockRejectedValueOnce(failure)
    const { result } = renderHook(() => useStartDemo())

    await act(() => result.current.start(nested))
    expect(result.current.startError).toEqual({ scenario: 'Nested: Product Page', error: failure })
    expect(navigate).not.toHaveBeenCalled()

    await act(() => result.current.start(nested))
    expect(result.current.startError).toBeNull()
  })
  it('ignores a second start while one is in flight, so a slow first start cannot open the wrong demo', async () => {
    const parallel = { id: 'parallel', name: 'Parallel: Dashboard', description: '' } as Scenario
    let finish: (v: { sessionId: string }) => void = () => {}
    mutateAsync.mockReturnValueOnce(new Promise((resolve) => (finish = resolve)))
    const { result } = renderHook(() => useStartDemo())

    let first: Promise<void> = Promise.resolve()
    act(() => {
      first = result.current.start(nested)
    })
    await act(() => result.current.start(parallel))
    expect(mutateAsync).toHaveBeenCalledTimes(1)

    await act(async () => {
      finish({ sessionId: 'sid-1' })
      await first
    })
    expect(navigate).toHaveBeenCalledTimes(1)
    expect(navigate).toHaveBeenCalledWith(expect.objectContaining({ search: expect.objectContaining({ scenarioId: 'nested' }) }))
  })
  it('does not navigate once its page is gone, so an abandoned start cannot pull the reader away', async () => {
    let finish: (v: { sessionId: string }) => void = () => {}
    mutateAsync.mockReturnValueOnce(new Promise((resolve) => (finish = resolve)))
    const onStarted = vi.fn()
    const { result, unmount } = renderHook(() => useStartDemo(onStarted))

    let first: Promise<void> = Promise.resolve()
    act(() => {
      first = result.current.start(nested)
    })
    unmount()
    await act(async () => {
      finish({ sessionId: 'sid-1' })
      await first
    })
    expect(navigate).not.toHaveBeenCalled()
    expect(onStarted).not.toHaveBeenCalled()
  })
})
