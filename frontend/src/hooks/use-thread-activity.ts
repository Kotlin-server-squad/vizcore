/**
 * React Query hooks for Thread Activity API
 *
 * `useThreadActivity` returns the REAL wire shape of
 * GET /sessions/{id}/threads: `ThreadActivity` (Map<threadId, ThreadEvent[]>).
 * The derived lane/dispatcher view model (`ThreadActivityResponse`) is built
 * client-side via `buildThreadLanes` (src/lib/thread-lanes.ts) and consumed
 * by the lane-oriented hooks below.
 */

import { useQuery } from '@tanstack/react-query'
import { apiClient } from '@/lib/api-client'
import { useMemo } from 'react'
import { buildThreadLanes } from '@/lib/thread-lanes'
import { pollInterval } from '@/lib/poll-interval'
import type { ThreadActivityResponse, ThreadLaneData } from '@/types/api'

/**
 * Fetch thread activity for a session (wire shape: ThreadActivity).
 *
 * @param sessionId - the session to fetch thread data for
 * @param pollMs    - fallback poll interval, or `false` (default) for none.
 *                    Nothing polls while the live stream is connected — the
 *                    SSE-driven invalidation of ['thread-activity', sessionId]
 *                    (use-event-stream.ts) refreshes it — and nothing polls
 *                    with the stream off (#124). Only the workspace's own
 *                    observer passes an interval, as the fallback while the
 *                    stream is on but SSE is down. TanStack Query runs one
 *                    timer PER OBSERVER, so every other observer of the key
 *                    must leave this `false`.
 * @param enabled   - Read-only shared view (Plan 06): the thread snapshot is
 *                    fed from the public `getSharedSession` payload, so the
 *                    protected `GET /sessions/{id}/threads` fetch is disabled.
 */
export function useThreadActivity(
  sessionId: string | undefined,
  pollMs: number | false = false,
  enabled = true,
) {
  return useQuery({
    queryKey: ['thread-activity', sessionId],
    queryFn: () => apiClient.getThreadActivity(sessionId!),
    enabled: !!sessionId && enabled,
    refetchInterval: enabled ? pollInterval(pollMs) : false,
    staleTime: 1000, // Consider data stale after 1 second
  })
}

/**
 * Get thread lanes grouped by dispatcher.
 *
 * Derives the lane view model from the wire shape via buildThreadLanes.
 * External contract unchanged:
 * `{ ...query, data: Map<dispatcherName, ThreadLaneData[]>, dispatcherInfo }`.
 *
 * A pure observer: it never polls (see useThreadActivity), it re-renders when
 * the workspace's refreshes land in the shared query cache.
 */
export function useThreadLanesByDispatcher(
  sessionId: string | undefined,
  enabled = true,
) {
  const { data: activity, ...query } = useThreadActivity(sessionId, false, enabled)

  const lanes = useMemo(
    () => (activity ? buildThreadLanes(activity) : undefined),
    [activity],
  )

  const grouped = useMemo(() => {
    if (!lanes) {
      return new Map<string, ThreadLaneData[]>()
    }

    const result = new Map<string, ThreadLaneData[]>()

    lanes.dispatcherInfo.forEach(dispatcher => {
      // Lanes without a dispatcherName carry dispatcherId null but group
      // under the 'Unknown' DispatcherInfo entry.
      const threads = lanes.threads.filter(
        t => (t.dispatcherId ?? 'Unknown') === dispatcher.id,
      )
      result.set(dispatcher.name, threads)
    })

    return result
  }, [lanes])

  return {
    ...query,
    data: grouped,
    dispatcherInfo: lanes?.dispatcherInfo || []
  }
}

/**
 * Get thread utilization statistics (operates on the derived view model,
 * which ThreadLanesView passes in).
 */
export function useThreadUtilizationStats(activity: ThreadActivityResponse | undefined) {
  return useMemo(() => {
    if (!activity?.threads || activity.threads.length === 0) {
      return {
        avgUtilization: 0,
        maxUtilization: 0,
        minUtilization: 0,
        byDispatcher: new Map<string, number>()
      }
    }

    const utilizations = activity.threads.map(t => t.utilization)
    const avgUtilization = utilizations.reduce((sum, u) => sum + u, 0) / utilizations.length
    const maxUtilization = Math.max(...utilizations)
    const minUtilization = Math.min(...utilizations)

    // Calculate average by dispatcher
    const byDispatcher = new Map<string, number>()
    activity.dispatcherInfo?.forEach(dispatcher => {
      const dispatcherThreads = activity.threads.filter(t => t.dispatcherId === dispatcher.id)
      if (dispatcherThreads.length > 0) {
        const avgUtil = dispatcherThreads.reduce((sum, t) => sum + t.utilization, 0) / dispatcherThreads.length
        byDispatcher.set(dispatcher.name, avgUtil)
      }
    })

    return {
      avgUtilization,
      maxUtilization,
      minUtilization,
      byDispatcher
    }
  }, [activity])
}

/**
 * Get active coroutines per thread.
 *
 * A coroutine is active on a thread iff its derived segment is still open
 * (`endNanos == null`), i.e. an ASSIGNED event without a matching RELEASED.
 *
 * A pure observer — it never polls (see useThreadActivity).
 */
export function useActiveCoroutinesPerThread(sessionId: string | undefined) {
  const { data: activity } = useThreadActivity(sessionId)

  const activeCoroutines = useMemo(() => {
    if (!activity) return new Map<number, string[]>()

    const lanes = buildThreadLanes(activity)
    const result = new Map<number, string[]>()

    lanes.threads.forEach(thread => {
      const active = thread.segments
        .filter(seg => seg.endNanos == null)
        .map(seg => seg.coroutineId)

      if (active.length > 0) {
        result.set(thread.threadId, active)
      }
    })

    return result
  }, [activity])

  return activeCoroutines
}
