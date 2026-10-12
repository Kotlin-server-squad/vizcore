/**
 * React Query hook for the per-session metrics API (RCO-07).
 *
 * `useSessionMetrics` returns the wire shape of GET /sessions/{id}/metrics
 * (`MetricsResponse`: active/peak/throughput/dispatcher-utilization/leaks).
 * It does not poll by default (#124): while the live stream is connected the
 * SSE-driven invalidation in use-event-stream.ts refreshes it, and with the
 * stream off the panel shows a snapshot. Only the workspace's own observer
 * passes `pollMs`, as the fallback while the stream is on but SSE is down —
 * every other observer of the key must leave it `false`, or its timer would
 * poll on its own.
 */

import { useQuery } from '@tanstack/react-query'
import { apiClient } from '@/lib/api-client'
import { pollInterval } from '@/lib/poll-interval'

/**
 * Metrics query for a session.
 *
 * @param sessionId - the session to fetch metrics for
 * @param pollMs    - fallback poll interval, or `false` (default) for none
 * @param enabled   - read-only shared view parity (T-08-08): the shared shell
 *                    carries no Bearer, so the protected /metrics fetch must be
 *                    disabled there (mirrors useThreadActivity).
 */
export function useSessionMetrics(
  sessionId: string | undefined,
  pollMs: number | false = false,
  enabled = true,
) {
  return useQuery({
    queryKey: ['session-metrics', sessionId],
    queryFn: () => apiClient.getMetrics(sessionId!),
    enabled: !!sessionId && enabled,
    refetchInterval: enabled ? pollInterval(pollMs) : false,
    staleTime: 1000,
  })
}
