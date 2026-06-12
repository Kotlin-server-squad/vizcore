/**
 * React Query hooks for Thread Activity API
 * 
 * Provides access to thread activity data with dispatcher information
 * and timeline segments for visualization.
 */

import { useQuery } from '@tanstack/react-query'
import { apiClient } from '@/lib/api-client'
import { useMemo } from 'react'
import type { ThreadActivityResponse, ThreadLaneData } from '@/types/api'

/**
 * Fetch thread activity for a session.
 *
 * @param sessionId - the session to fetch thread data for
 * @param isLive    - when true the SSE stream is driving updates, so the
 *                    background polling interval is disabled; the view will
 *                    refresh via SSE-triggered cache invalidations instead.
 *                    Defaults to false (legacy 2s poll behaviour).
 */
export function useThreadActivity(sessionId: string | undefined, isLive = false) {
  return useQuery({
    queryKey: ['thread-activity', sessionId],
    queryFn: () => apiClient.getThreadActivity(sessionId!),
    enabled: !!sessionId,
    // Disable the 2-second polling interval while the live SSE stream is
    // driving updates.  When SSE is not active, fall back to the original
    // 2-second background refresh so the Threads view still stays current.
    refetchInterval: isLive ? false : 2000,
    staleTime: 1000, // Consider data stale after 1 second
  })
}

/**
 * Get thread lanes grouped by dispatcher
 */
export function useThreadLanesByDispatcher(sessionId: string | undefined) {
  const { data: activity, ...query } = useThreadActivity(sessionId)

  const grouped = useMemo(() => {
    if (!activity?.threads || !activity?.dispatcherInfo) {
      return new Map<string, ThreadLaneData[]>()
    }

    const result = new Map<string, ThreadLaneData[]>()

    activity.dispatcherInfo.forEach(dispatcher => {
      const threads = activity.threads.filter(t => t.dispatcherId === dispatcher.id)
      result.set(dispatcher.name, threads)
    })

    return result
  }, [activity])

  return {
    ...query,
    data: grouped,
    dispatcherInfo: activity?.dispatcherInfo || []
  }
}

/**
 * Get thread utilization statistics
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
 * Get active coroutines per thread at current time
 */
export function useActiveCoroutinesPerThread(sessionId: string | undefined) {
  const { data: activity } = useThreadActivity(sessionId)

  const activeCoroutines = useMemo(() => {
    if (!activity?.threads) return new Map<number, string[]>()

    const now = Date.now() * 1_000_000 // Convert to nanos
    const result = new Map<number, string[]>()

    activity.threads.forEach(thread => {
      const active = thread.segments
        .filter(seg => seg.startNanos <= now && (!seg.endNanos || seg.endNanos > now))
        .map(seg => seg.coroutineId)
      
      if (active.length > 0) {
        result.set(thread.threadId, active)
      }
    })

    return result
  }, [activity])

  return activeCoroutines
}

