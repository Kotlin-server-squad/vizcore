import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ValidationResponse } from '@/types/api'
import { ChecksModal, countFailedChecks } from './ChecksModal'

vi.mock('../validation/ValidationPanel', () => ({
  ValidationPanel: ({ validation }: { validation: { data: ValidationResponse | null } }) => (
    <div data-testid="validation-panel">
      results:{validation.data ? validation.data.results.length : 'none'}
    </div>
  ),
}))

function validation(data: ValidationResponse | null = null) {
  return {
    validate: vi.fn(),
    data,
    isLoading: false,
    isError: false,
    error: null,
  } as unknown as Parameters<typeof ChecksModal>[0]['validation']
}

function response(failCount: number, passCount = 1): ValidationResponse {
  return {
    results: [
      ...Array.from({ length: failCount }, (_, i) => ({
        type: 'Fail' as const,
        ruleName: `fail-${i}`,
      })),
      ...Array.from({ length: passCount }, (_, i) => ({
        type: 'Pass' as const,
        ruleName: `pass-${i}`,
      })),
    ],
  } as unknown as ValidationResponse
}

describe('countFailedChecks', () => {
  it('counts nothing before a run — an un-run check is not a passing check', () => {
    expect(countFailedChecks(null)).toBe(0)
  })

  it('counts only failures', () => {
    expect(countFailedChecks(response(3, 7))).toBe(3)
  })

  it('counts zero for a clean run', () => {
    expect(countFailedChecks(response(0, 9))).toBe(0)
  })
})

describe('ChecksModal', () => {
  it('renders nothing while closed', () => {
    render(
      <ChecksModal
        sessionId="s-1"
        isOpen={false}
        onOpenChange={vi.fn()}
        validation={validation()}
      />,
    )

    expect(screen.queryByTestId('validation-panel')).toBeNull()
  })

  it('hosts the validation panel when open', () => {
    render(
      <ChecksModal
        sessionId="s-1"
        isOpen
        onOpenChange={vi.fn()}
        validation={validation()}
      />,
    )

    expect(screen.getByTestId('validation-panel')).toBeInTheDocument()
  })

  it('feeds it results owned by the workspace, so they survive a close and reopen', async () => {
    const owned = validation(response(2))
    const { rerender } = render(
      <ChecksModal sessionId="s-1" isOpen onOpenChange={vi.fn()} validation={owned} />,
    )
    expect(screen.getByTestId('validation-panel')).toHaveTextContent('results:3')

    rerender(
      <ChecksModal sessionId="s-1" isOpen={false} onOpenChange={vi.fn()} validation={owned} />,
    )
    rerender(
      <ChecksModal sessionId="s-1" isOpen onOpenChange={vi.fn()} validation={owned} />,
    )

    // The hook lives in SessionWorkspace, not in the modal — unmounting the
    // modal must not throw the run away.
    expect(await screen.findByTestId('validation-panel')).toHaveTextContent('results:3')
  })

  it('closes on the close button', async () => {
    const onOpenChange = vi.fn()
    render(
      <ChecksModal sessionId="s-1" isOpen onOpenChange={onOpenChange} validation={validation()} />,
    )

    // HeroUI's Modal renders its own dismiss X alongside the footer button;
    // both are named "Close", so take the explicit footer one.
    const closers = screen.getAllByRole('button', { name: /close/i })
    await userEvent.click(closers[closers.length - 1]!)
    expect(onOpenChange).toHaveBeenCalled()
  })
})
