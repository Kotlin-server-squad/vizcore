import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, act } from '@testing-library/react'
import { ErrorState } from './ErrorState'
import { ApiError, NetworkError, RateLimitedError } from '@/lib/api-errors'

afterEach(() => {
  vi.useRealTimers()
})

describe('ErrorState (#139)', () => {
  it('retries a rate-limited read by itself once Retry-After has passed', () => {
    vi.useFakeTimers()
    const onRetry = vi.fn()
    render(<ErrorState error={new RateLimitedError(5_000)} onRetry={onRetry} />)

    expect(screen.getByRole('alert')).toHaveTextContent(/retrying in 5s/i)
    act(() => {
      vi.advanceTimersByTime(4_000)
    })
    expect(onRetry).not.toHaveBeenCalled()
    act(() => {
      vi.advanceTimersByTime(2_000)
    })
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('offers Retry for transient failures but not for a 404', () => {
    const { rerender } = render(<ErrorState error={new NetworkError()} onRetry={vi.fn()} />)
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()

    rerender(<ErrorState error={new ApiError(404, 'nope')} subject="This session" onRetry={vi.fn()} />)
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent(/this session does not exist/i)
  })
})
