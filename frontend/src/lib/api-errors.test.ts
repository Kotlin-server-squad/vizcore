import { describe, it, expect } from 'vitest'
import { ApiError, describeApiError } from './api-errors'

describe('describeApiError', () => {
  it('says the server is busy when the demo limit refuses a demo, with its reason', () => {
    const described = describeApiError(
      new ApiError(503, 'Server busy: too many demos running, try again in a few seconds', 'demo-limit'),
      'The scenario',
    )
    expect(described.title).toBe('Server busy')
    expect(described.message).toBe('Server busy: too many demos running, try again in a few seconds')
    expect(described.retryable).toBe(true)
  })

  it('says a demo stopped at its time limit was stopped, not that the server is busy', () => {
    const described = describeApiError(
      new ApiError(503, 'The demo ran past its time limit and was stopped', 'demo-time-limit'),
    )
    expect(described.title).toBe('Demo stopped')
    expect(described.message).toBe('The demo ran past its time limit and was stopped')
  })

  it.each(['HTTP 503 Service Unavailable', 'Service Unavailable', 'no healthy upstream'])(
    'keeps a 503 without a demo code (%s), as a proxy sends for a stopped backend, a server error',
    (message) => {
      expect(describeApiError(new ApiError(503, message)).title).toBe('Server error')
    },
  )

  it('still reports any other 5xx as a server error', () => {
    const described = describeApiError(new ApiError(500, 'boom'))
    expect(described.title).toBe('Server error')
    expect(described.message).toContain('boom')
  })
})
