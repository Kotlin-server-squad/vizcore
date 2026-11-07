/**
 * React Query hooks for Timeline API
 * 
 * Provides access to detailed timeline data for individual coroutines
 * including suspension points, dispatcher switches, and computed durations.
 */

import { useQuery } from '@tanstack/react-query'
import { apiClient } from '@/lib/api-client'
import { useMemo } from 'react'
import { readAt, timelineKey } from '@/lib/moment-read'
import { useMomentQuery } from './use-moment-query'
import { useMomentView } from './use-moment'
import type { CoroutineTimeline } from '@/types/api'

/** How often an unfinished coroutine's timeline is re-read while the inspector shows it. */
export const OPEN_PHASE_REFETCH_MS = 2000

/**
 * A coroutine's timeline query, live or bound to the page's moment. The live result is React Query's own; at a moment
 * (Part 8) `moment` says whether the timeline is valid at the cursor (`ready`), being read (`updating`) or failed.
 */
export interface TimelineQuery {
  data: CoroutineTimeline | undefined
  isLoading: boolean
  isError: boolean
  error: unknown
  dataUpdatedAt: number
  refetch: () => unknown
  isSuccess?: boolean
  isFetching?: boolean
  moment?: { status: 'ready' | 'updating' | 'error'; dataSeq: number | null }
}

/**
 * Fetch timeline for a specific coroutine. Inside a moment (`MomentContext`: a replay cursor or a shared link's loaded
 * cut) it reads the timeline of that moment instead, through `useMomentQuery` (Part 8, M-3, M-4), and never shows an
 * event after the cursor.
 */
export function useCoroutineTimeline(
  sessionId: string | undefined,
  coroutineId: string | undefined,
  /**
   * Re-read the timeline while the coroutine is unfinished: a suspend and resume between two
   * snapshots leaves its state unchanged, the inspector counts the open phase on (#218), and a
   * reconnecting app brings the open phase back. One observer per view asks for it, since every
   * observer with an interval runs its own timer.
   */
  options: { followOpenPhase?: boolean } = {},
): TimelineQuery {
  const view = useMomentView()
  const live = useQuery({
    queryKey: ['sessions', sessionId, 'coroutines', coroutineId, 'timeline'],
    queryFn: () => apiClient.getCoroutineTimeline(sessionId!, coroutineId!),
    enabled: !view && !!sessionId && !!coroutineId,
    staleTime: 5000,
    refetchInterval: options.followOpenPhase && !view
      ? (query) => (query.state.data && query.state.data.totalDuration == null ? OPEN_PHASE_REFETCH_MS : false)
      : false,
  })
  const read = view?.read
  const atMoment = useMomentQuery<CoroutineTimeline>({
    enabled: !!view && !!sessionId && !!coroutineId,
    identity: `${coroutineId ?? ''}`,
    keyAt: seq => (read ? timelineKey(readAt(read, seq), coroutineId ?? '') : ['no-moment']),
    fetchAt: seq => apiClient.getTimelineAt(readAt(read!, seq), coroutineId!),
    cursorSeq: view?.cursorSeq ?? 0,
    changesBetween: (a, b) => (view ? view.changesBetween(coroutineId ?? '', a, b) : true),
    settleMs: view?.settleMs ?? 0,
    hold: view?.hold ?? false,
  })
  const cursorSeq = view?.cursorSeq
  const data = useMemo(() => {
    const timeline = atMoment.data
    if (!timeline || cursorSeq === undefined) return timeline
    // A second guard: whatever a read returns, nothing after the cursor is shown.
    const events = timeline.events.filter(e => e.seq <= cursorSeq)
    return events.length === timeline.events.length ? timeline : { ...timeline, events }
  }, [atMoment.data, cursorSeq])
  if (!view) return live
  return {
    data,
    isLoading: atMoment.status === 'updating',
    isError: atMoment.status === 'error',
    error: atMoment.error,
    dataUpdatedAt: 0,
    refetch: atMoment.retry,
    moment: { status: atMoment.status, dataSeq: atMoment.dataSeq },
  }
}

/**
 * Calculate timeline statistics
 */
export function useTimelineStats(timeline: CoroutineTimeline | undefined) {
  return useMemo(() => {
    if (!timeline) {
      return {
        totalDuration: 0,
        activeTime: 0,
        suspendedTime: 0,
        activePercent: 0,
        suspendedPercent: 0,
        suspensionCount: 0,
        dispatcherSwitches: 0,
        threadSwitches: 0,
        avgSuspensionDuration: 0
      }
    }

    const suspensionEvents = timeline.events.filter(e => e.kind === 'coroutine.suspended')
    const dispatcherEvents = timeline.events.filter(e => e.kind === 'DispatcherSelected')
    const threadEvents = timeline.events.filter(e => e.kind === 'thread.assigned')

    // Per-event durations are a deferred timeline-projection stub (D-02): the
    // source-only DTO carries no per-event `duration`, so we report 0 rather than
    // fabricate a value.
    const avgSuspensionDuration = 0

    // The view-model output keys (activeTime/suspendedTime) are sourced from the
    // reconciled generated fields (activeDuration/suspendedDuration), which are
    // nullable on the source-only DTO.
    const totalDuration = timeline.totalDuration ?? 0
    const activeTime = timeline.activeDuration ?? 0
    const suspendedTime = timeline.suspendedDuration ?? 0

    const activePercent = totalDuration > 0
      ? (activeTime / totalDuration) * 100
      : 0

    const suspendedPercent = totalDuration > 0
      ? (suspendedTime / totalDuration) * 100
      : 0

    return {
      totalDuration,
      activeTime,
      suspendedTime,
      activePercent,
      suspendedPercent,
      suspensionCount: suspensionEvents.length,
      dispatcherSwitches: dispatcherEvents.length,
      threadSwitches: threadEvents.length,
      avgSuspensionDuration
    }
  }, [timeline])
}

/**
 * Get suspension points with details
 */
export function useSuspensionPoints(sessionId: string | undefined, coroutineId: string | undefined) {
  const { data: timeline } = useCoroutineTimeline(sessionId, coroutineId)

  const suspensionPoints = useMemo(() => {
    if (!timeline?.events) return []

    return timeline.events
      .filter(e => e.kind === 'coroutine.suspended' && e.suspensionPoint)
      .map(e => ({
        ...e.suspensionPoint!,
        eventSeq: e.seq,
        tsNanos: e.tsNanos
      }))
  }, [timeline])

  return suspensionPoints
}

/**
 * Get dispatcher switches over time
 */
export function useDispatcherSwitches(sessionId: string | undefined, coroutineId: string | undefined) {
  const { data: timeline } = useCoroutineTimeline(sessionId, coroutineId)

  const switches = useMemo(() => {
    if (!timeline?.events) return []

    // The source-only DTO carries no per-event dispatcherId/threadId/timestamp
    // (deferred timeline projection, D-02). Map to the generated fields that exist
    // — do not fabricate dispatcher data.
    return timeline.events
      .filter(e => e.kind === 'DispatcherSelected')
      .map(e => ({
        seq: e.seq,
        tsNanos: e.tsNanos,
        dispatcherName: e.dispatcherName,
        threadName: e.threadName
      }))
  }, [timeline])

  return switches
}

/**
 * Format timeline data for visualization
 * Converts events to a format suitable for timeline charts
 */
export function useTimelineVisualizationData(timeline: CoroutineTimeline | undefined) {
  return useMemo(() => {
    if (!timeline?.events || timeline.events.length === 0) return []

    const baseTime = timeline.events[0]!.tsNanos
    const data: Array<{
      seq: number
      relativeTime: number
      kind: string
      state: 'active' | 'suspended' | 'transition'
      duration?: number
      metadata?: any
    }> = []

    timeline.events.forEach((event, _index) => {
      const relativeTime = event.tsNanos - baseTime

      let state: 'active' | 'suspended' | 'transition' = 'transition'
      if (event.kind === 'coroutine.started' || event.kind === 'coroutine.resumed') {
        state = 'active'
      } else if (event.kind === 'coroutine.suspended') {
        state = 'suspended'
      }

      data.push({
        seq: event.seq,
        relativeTime,
        kind: event.kind,
        state,
        // Per-event duration is a deferred projection stub (D-02) — not on the wire.
        duration: undefined,
        metadata: {
          threadName: event.threadName,
          dispatcherName: event.dispatcherName,
          suspensionPoint: event.suspensionPoint
        }
      })
    })

    return data
  }, [timeline])
}

