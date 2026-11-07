/**
 * One round's hierarchy, read on demand for a group's creation site (dock S-12): the read replays the session's
 * events on the server, so it is fetched only while a group is selected, never refreshed live, and read again only
 * when the group's members change (their ids are part of the key).
 */
import { useQuery } from '@tanstack/react-query'
import { apiClient } from '@/lib/api-client'

export function useRoundHierarchy(sessionId: string, roundId: string | null | undefined, memberIds: readonly string[]) {
  return useQuery({
    queryKey: ['session-round-hierarchy', sessionId, roundId, memberIds.join(',')],
    queryFn: () => apiClient.getRoundHierarchy(sessionId, roundId!),
    enabled: !!roundId,
    staleTime: Infinity,
    gcTime: 60_000,
  })
}
