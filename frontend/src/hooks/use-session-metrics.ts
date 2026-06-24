/**
 * React Query hook for the per-session metrics API (RCO-07).
 *
 * `useSessionMetrics` returns the wire shape of GET /sessions/{id}/metrics
 * (`MetricsResponse`: active/peak/throughput/dispatcher-utilization/leaks).
 * It clones `useThreadActivity`'s poll-while-live semantics EXACTLY so the
 * Session metrics panel refreshes at the same cadence as the rest of the live
 * view (5s slow fallback while the SSE stream drives updates, 2s otherwise).
 */

import { useQuery } from '@tanstack/react-query'
import { apiClient } from '@/lib/api-client'

/**
 * Poll-while-live metrics query for a session.
 *
 * @param sessionId - the session to fetch metrics for
 * @param isLive    - when true the SSE stream is driving the live view: poll
 *                    on the slow 5s fallback interval (defense-in-depth) rather
 *                    than the 2s background refresh.
 * @param enabled   - read-only shared view parity (T-08-08): the shared shell
 *                    carries no Bearer, so the protected /metrics fetch + poll
 *                    must be disabled there (mirrors useThreadActivity).
 */
export function useSessionMetrics(
  sessionId: string | undefined,
  isLive = false,
  enabled = true,
) {
  return useQuery({
    queryKey: ['session-metrics', sessionId],
    queryFn: () => apiClient.getMetrics(sessionId!),
    enabled: !!sessionId && enabled,
    refetchInterval: enabled ? (isLive ? 5000 : 2000) : false,
    staleTime: 1000,
  })
}
