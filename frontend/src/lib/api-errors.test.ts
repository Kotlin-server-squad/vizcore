import { describe, it, expect } from 'vitest'
import { ApiError, describeApiError } from './api-errors'

describe('describeApiError', () => {
  it('says the server is busy when the demo limit refuses a demo (503), with its reason', () => {
    const described = describeApiError(
      new ApiError(503, 'Server busy: too many demos running, try again in a few seconds'),
      'The scenario',
    )
    expect(described.title).toBe('Server busy')
    expect(described.message).toBe('Server busy: too many demos running, try again in a few seconds')
    expect(described.retryable).toBe(true)
  })

  it.each(['HTTP 503 Service Unavailable', 'Service Unavailable'])(
    'keeps a bare 503 (%s), as a proxy sends for a stopped backend, a server error',
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
