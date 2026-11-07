/**
 * The session's rounds (triage C-3, T-4; dock S-12), for the round picker and "find in all rounds".
 *
 * Neither query is part of the live refresh (use-event-stream.ts re-reads three endpoints every 5 s at most, #124):
 * the workspace re-reads the round list on its own slower rule (Part 6 plan, "Requests"), and a find runs once per
 * submit.
 */
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { apiClient } from '@/lib/api-client'
import type { RoundEntry } from '@/types/api'

/** Rounds the picker lists at first, and how many more each "Show more" asks for. */
export const ROUNDS_PAGE = 50
/** The API's largest page: the picker lists at most this many; older rounds are reached through Find. */
export const ROUNDS_MAX = 200
/** Matching rounds one find reads (the API's largest page). */
export const FIND_LIMIT = 200

/**
 * The picker's rounds: ONE request whatever is listed (Part 6 plan, "Requests"). "Show more" raises the limit
 * (50, 100, 150, 200) instead of loading further pages, so re-reading the list never costs more than one read.
 */
export function useRounds(
  sessionId: string,
  { includeVizcore, enabled }: { includeVizcore: boolean; enabled: boolean },
) {
  const [limit, setLimit] = useState(ROUNDS_PAGE)
  const query = useQuery({
    queryKey: ['session-rounds', sessionId, includeVizcore, limit],
    queryFn: () => apiClient.getRounds(sessionId, { limit, includeVizcore }),
    enabled,
    // Re-read only when the workspace asks (refetch), never on its own.
    staleTime: Infinity,
    // A larger limit or the vizcore toggle keeps the list it had until the new one arrives.
    placeholderData: keepPreviousData,
  })
  const rounds: RoundEntry[] = query.data?.items ?? []
  const hasMore = !!query.data?.nextCursor
  return {
    rounds,
    /** Rounds the server has in all (with the toggle's rule). */
    total: query.data?.total ?? 0,
    /** The page's "as of" (H-2): the app time of the last event it reflects. */
    asOfAppNanos: query.data?.asOfAppNanos ?? null,
    asOfSeq: query.data?.asOfSeq ?? null,
    status: query.status,
    /** The server has rounds beyond those listed. */
    hasMore,
    /** "Show more" can list more of them (the limit is below the API's largest page). */
    canLoadMore: hasMore && limit < ROUNDS_MAX,
    loadMore: () => {
      if (hasMore && limit < ROUNDS_MAX) setLimit(l => Math.min(ROUNDS_MAX, l + ROUNDS_PAGE))
    },
    refetch: query.refetch,
    isFetching: query.isFetching,
    dataUpdatedAt: query.dataUpdatedAt,
    errorUpdatedAt: query.errorUpdatedAt,
  }
}

/**
 * A submitted find: its text, the vizcore rule it was submitted with, and when (a new submit of the same text reads
 * again). Changing the toggle afterwards does not search again: one request per submit.
 */
export interface SubmittedFind {
  text: string
  at: number
  includeVizcore: boolean
}

export function useRoundSearch(sessionId: string, { q }: { q: SubmittedFind | null }) {
  return useQuery({
    queryKey: ['session-rounds-find', sessionId, q?.includeVizcore, q?.text, q?.at],
    queryFn: () => apiClient.getRounds(sessionId, { limit: FIND_LIMIT, q: q!.text, includeVizcore: q!.includeVizcore }),
    enabled: !!q,
    staleTime: Infinity,
    gcTime: 0,
  })
}
