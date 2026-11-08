import { useQuery } from '@tanstack/react-query'
import { apiClient } from '@/lib/api-client'

export const checksStatusKey = (sessionId: string) => ['checks-status', sessionId] as const

/**
 * The session's last checks run (triage C-5, T-8), read once; a run in this visit invalidates it. Not polled: "N new
 * events since" is counted from the page's own events.
 */
export function useChecksStatus(sessionId: string, enabled: boolean) {
  return useQuery({
    queryKey: checksStatusKey(sessionId),
    queryFn: () => apiClient.getChecksStatus(sessionId),
    enabled,
    staleTime: Infinity,
  })
}
