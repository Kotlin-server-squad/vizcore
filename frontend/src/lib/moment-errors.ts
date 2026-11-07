import { ApiError, RateLimitedError } from './api-errors'

/** What a part says when a read bound to a moment failed (Part 8, M-4, M-11), and what it offers. */
export interface MomentErrorText {
  text: string
  /** Reload the page (a shared view that moved on: the payload, its health and cut come back together), or retry. */
  action: 'reload' | 'retry' | null
}

export function momentErrorText(error: unknown): MomentErrorText {
  if (error instanceof RateLimitedError) {
    return { text: `Too many requests: try again in ${Math.ceil(error.retryAfterMs / 1000)} s.`, action: 'retry' }
  }
  if (error instanceof ApiError) {
    if (error.status === 409 && error.code === 'stale') {
      return { text: 'This view has moved on since the link was loaded.', action: 'reload' }
    }
    if (error.status === 404) return { text: 'Not known at this moment.', action: null }
    if (error.code === 'history-trimmed') {
      return { text: "Details unavailable: this session's early events were trimmed.", action: null }
    }
    if (error.code === 'summaries-unavailable') {
      return { text: 'Details unavailable: this session keeps no summaries.', action: null }
    }
    if (error.status === 410) return { text: 'This link has expired.', action: null }
  }
  return { text: 'This moment could not be read.', action: 'retry' }
}
