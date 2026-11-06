import { useMemo } from 'react'
import { useSessionEvents } from '@/hooks/use-sessions'
import { projectSync, type SyncProjection } from '@/lib/projections/project-sync'
import type { VizEvent } from '@/types/api'

export type { Holder, MutexState, SemaphoreHolder, SemaphoreState } from '@/lib/projections/project-sync'

export type UseSyncEventsResult = SyncProjection

/**
 * Mutex and semaphore state, wait queues and deadlock warnings.
 *
 * Reads `events` when the caller passes them — the workspace passes the events
 * the page shows, so the facts follow the live stream and the replay cursor —
 * else the stored history, which live updates never invalidate. It subscribes
 * to that history either way, so mounting it changes no request (#124).
 */
export function useSyncEvents(sessionId: string, events?: readonly VizEvent[]): UseSyncEventsResult {
  const { data: stored } = useSessionEvents(sessionId)
  const source = events ?? stored
  return useMemo(() => projectSync(source), [source])
}
