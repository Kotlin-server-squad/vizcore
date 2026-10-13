import { useMemo } from 'react'
import { useSessionEvents } from '@/hooks/use-sessions'
import type { VizEvent } from '@/types/api'
import {
  CHANNEL_EVENT_KINDS,
  FLOW_EVENT_KINDS,
  SYNC_EVENT_KINDS,
  JOB_EVENT_KINDS,
} from '@/types/api'

export interface EventCategories {
  hasChannels: boolean
  hasFlowOps: boolean
  hasSyncPrimitives: boolean
  hasJobs: boolean
  hasValidation: boolean
}

/**
 * Returns which event categories are present in a session's events.
 * Scans the session event list — plus, when given, the events streamed live
 * since it was fetched — and checks each event's `kind` against the known
 * category sets exported from api.ts.
 *
 * The stored list is fetched once (it is never re-downloaded while the stream
 * is connected, #137), so without `liveEvents` a real app that starts using a
 * wrapper after the page opened would keep its old rung (#145).
 */
export function useEventCategories(
  sessionId: string,
  liveEvents?: readonly VizEvent[],
): EventCategories {
  const { data: stored } = useSessionEvents(sessionId)

  return useMemo(() => {
    const result: EventCategories = {
      hasChannels: false,
      hasFlowOps: false,
      hasSyncPrimitives: false,
      hasJobs: false,
      hasValidation: true, // Validation tab is always available
    }

    for (const event of eventsOf(stored, liveEvents)) {
      const kind = event.kind
      if (!result.hasChannels && CHANNEL_EVENT_KINDS.has(kind)) {
        result.hasChannels = true
      }
      if (!result.hasFlowOps && FLOW_EVENT_KINDS.has(kind)) {
        result.hasFlowOps = true
      }
      if (!result.hasSyncPrimitives && SYNC_EVENT_KINDS.has(kind)) {
        result.hasSyncPrimitives = true
      }
      if (!result.hasJobs && JOB_EVENT_KINDS.has(kind)) {
        result.hasJobs = true
      }
      // Early exit if all categories found
      if (result.hasChannels && result.hasFlowOps && result.hasSyncPrimitives && result.hasJobs) {
        break
      }
    }

    return result
  }, [stored, liveEvents])
}

function* eventsOf(
  stored: readonly VizEvent[] | undefined,
  live: readonly VizEvent[] | undefined,
): Generator<VizEvent> {
  if (stored) yield* stored
  if (live) yield* live
}
