import { describe, it, expect } from 'vitest'
import { momentErrorText } from './moment-errors'
import { ApiError, RateLimitedError } from './api-errors'

describe('momentErrorText', () => {
  it('names each failure of a read bound to a moment honestly', () => {
    expect(momentErrorText(new ApiError(409, 'x', 'stale'))).toEqual({ text: 'This view has moved on since the link was loaded.', action: 'reload' })
    expect(momentErrorText(new RateLimitedError(2500))).toEqual({ text: 'Too many requests: try again in 3 s.', action: 'retry' })
    expect(momentErrorText(new ApiError(404, 'x'))).toEqual({ text: 'Not known at this moment.', action: null })
    expect(momentErrorText(new ApiError(503, 'x', 'summaries-unavailable')).text).toBe('Details unavailable: this session keeps no summaries.')
    expect(momentErrorText(new ApiError(410, 'x')).text).toBe('This link has expired.')
    expect(momentErrorText(new Error('boom'))).toEqual({ text: 'This moment could not be read.', action: 'retry' })
  })
})
