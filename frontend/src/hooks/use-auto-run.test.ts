import { describe, it, expect, vi } from 'vitest'
import { renderHook } from '@testing-library/react'
import { useAutoRun } from './use-auto-run'

const setup = (over: Partial<Parameters<typeof useAutoRun>[0]> = {}) => {
  const props = { requested: true, outcome: 'notStarted' as const, run: vi.fn(() => Promise.resolve()), onConsumed: vi.fn(), ...over }
  return { props, hook: renderHook(p => useAutoRun(p), { initialProps: props }) }
}

describe('useAutoRun — Run again opens a session that starts its own run (S-13)', () => {
  it('drops the request from the address, then starts the run, once', () => {
    const order: string[] = []
    const run = vi.fn(() => {
      order.push('run')
      return Promise.resolve()
    })
    const onConsumed = vi.fn(() => order.push('consumed'))
    const { hook, props } = setup({ run, onConsumed })
    hook.rerender({ ...props, run, onConsumed })
    hook.rerender({ ...props, run, onConsumed })
    expect(order).toEqual(['consumed', 'run'])
  })

  it('waits until the session has loaded', () => {
    const { hook, props } = setup({ outcome: null })
    expect(props.run).not.toHaveBeenCalled()
    expect(props.onConsumed).not.toHaveBeenCalled()
    hook.rerender({ ...props, outcome: 'notStarted' })
    expect(props.run).toHaveBeenCalledTimes(1)
  })

  it('never starts a run that has already started (a reload of an old address)', () => {
    const { props } = setup({ outcome: 'running' })
    expect(props.run).not.toHaveBeenCalled()
    expect(props.onConsumed).toHaveBeenCalledTimes(1)
  })

  it('does nothing when not asked', () => {
    const { props } = setup({ requested: false })
    expect(props.run).not.toHaveBeenCalled()
    expect(props.onConsumed).not.toHaveBeenCalled()
  })
})
