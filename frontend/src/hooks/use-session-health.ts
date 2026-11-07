import { useQuery } from '@tanstack/react-query'
import { apiClient } from '@/lib/api-client'
import { ApiError } from '@/lib/api-errors'
import { readAt } from '@/lib/moment-read'
import type { SessionHealth } from '@/types/health'
import type { MomentView } from './use-moment'
import { useMomentQuery } from './use-moment-query'

/** The live health's query key: the stream's throttled refresh invalidates it with the other read models (P-1). */
export const sessionHealthKey = (sessionId: string) => ['session-health', sessionId] as const

/** Where the page's health comes from (P-1). */
export type HealthMode =
  /**
   * The live (or paused) view: `GET …/health`, refreshed by the stream; [pollMs] only while the stream is on but its
   * connection is down (the fallback poll).
   */
  | { kind: 'live'; pollMs?: number | false }
  /** A replay: the health at the cursor, through the moment's binding (session or shared token). */
  | { kind: 'moment'; view: MomentView }
  /** A shared link as loaded: the payload's health, or why it has none. */
  | { kind: 'loaded'; health: SessionHealth | null; unavailable: string | null }

export interface SessionHealthResult {
  health: SessionHealth | undefined
  status: 'pending' | 'ready' | 'updating' | 'error'
  error: unknown
  retry: () => void
  /** When the live copy was last read (epoch ms), 0 otherwise. */
  updatedAt: number
  /** When the live read last failed (epoch ms), 0 otherwise: a quiet refresh retries from it. */
  failedAt: number
  /** The last refresh failed while an older result is still shown: say so rather than show it as current. */
  refreshError: unknown
}

/**
 * The session's health at the page's moment (Part 7, P-1): what the Problems tab, the tab's badge, the Inspector's
 * order and the cards' badges present. No problem is derived here (D-8). Not polled: the live view's copy is refreshed
 * by the stream (events and watermark frames, at most every 5 s), so a paused view keeps its "as of".
 */
export function useSessionHealth({ sessionId, mode }: { sessionId: string; mode: HealthMode }): SessionHealthResult {
  const live = useQuery({
    queryKey: sessionHealthKey(sessionId),
    queryFn: () => apiClient.getHealthAt({ source: { kind: 'session', sessionId }, binding: { kind: 'current' } }),
    enabled: mode.kind === 'live',
    staleTime: Infinity,
    // Every visit reads it again: a cached health from an earlier visit is not this visit's.
    refetchOnMount: 'always',
    refetchInterval: mode.kind === 'live' ? (mode.pollMs ?? false) : false,
  })

  const view = mode.kind === 'moment' ? mode.view : null
  const source = view?.read.source
  const sourceKey = source?.kind === 'shared' ? ['shared', source.token] : ['sessions', sessionId]
  const atMoment = useMomentQuery<SessionHealth>({
    enabled: view !== null,
    identity: 'health',
    keyAt: seq => [...sourceKey, 'health', 'atSeq', seq],
    fetchAt: seq => apiClient.getHealthAt(readAt(view!.read, seq)),
    cursorSeq: view?.cursorSeq ?? 0,
    // Any event can change the health: a read at one seq never stands for another.
    changesBetween: (a, b) => a !== b,
    settleMs: view?.settleMs ?? 0,
    hold: view?.hold ?? false,
  })

  if (mode.kind === 'loaded') {
    return mode.health
      ? { health: mode.health, status: 'ready', error: null, retry: noop, updatedAt: 0, failedAt: 0, refreshError: null }
      : {
          health: undefined,
          status: 'error',
          error: new ApiError(503, 'Health unavailable', mode.unavailable ?? undefined),
          retry: noop,
          updatedAt: 0,
          failedAt: 0,
          refreshError: null,
        }
  }
  if (mode.kind === 'moment') {
    return {
      health: atMoment.status === 'ready' ? atMoment.data : undefined,
      status: atMoment.status,
      error: atMoment.error,
      retry: atMoment.retry,
      updatedAt: 0,
      failedAt: 0,
      refreshError: null,
    }
  }
  return {
    health: live.data,
    status: live.isError && !live.data ? 'error' : live.data ? 'ready' : 'pending',
    error: live.error,
    retry: () => void live.refetch(),
    updatedAt: live.dataUpdatedAt,
    failedAt: live.errorUpdatedAt,
    refreshError: live.isError && live.data ? live.error : null,
  }
}

function noop() {}
