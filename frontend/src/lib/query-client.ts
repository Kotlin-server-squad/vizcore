import { QueryClient } from '@tanstack/react-query'
import { isNonRetryable } from './api-errors'

/**
 * Retry a failed query once — unless the failure is a 4xx. Retrying a 404 or
 * 401 cannot succeed, and retrying a 429 is exactly the retry storm the rate
 * limiter is pushing back against (#124); polls pick those up again on their
 * own interval once the cool-down has passed.
 */
export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  if (isNonRetryable(error)) return false
  return failureCount < 1
}

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 1000 * 30, // 30 seconds
      refetchOnWindowFocus: false,
      retry: shouldRetryQuery,
    },
  },
})
