import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { apiClient } from '@/lib/api-client'
import { pollInterval } from '@/lib/poll-interval'

export function useSessions() {
  return useQuery({
    queryKey: ['sessions'],
    queryFn: () => apiClient.listSessions(),
  })
}

/**
 * The session snapshot. Never polls by default: while the live stream is
 * connected, SSE-driven invalidation refreshes it (use-event-stream.ts); with
 * the stream off it is a snapshot the user refreshes. The workspace passes
 * `pollMs` only as the fallback while the stream is on but SSE is down (#124).
 */
export function useSession(
  sessionId: string | undefined,
  { pollMs = false }: { pollMs?: number | false } = {},
) {
  return useQuery({
    queryKey: ['sessions', sessionId],
    queryFn: () => apiClient.getSession(sessionId!),
    enabled: !!sessionId,
    refetchInterval: pollInterval(pollMs),
  })
}

export function useSessionEvents(sessionId: string | undefined) {
  return useQuery({
    queryKey: ['sessions', sessionId, 'events'],
    queryFn: () => apiClient.getSessionEvents(sessionId!),
    enabled: !!sessionId,
  })
}

export function useCreateSession() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (name?: string) => apiClient.createSession(name),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['sessions'] })
    },
  })
}

export function useDeleteSession() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (sessionId: string) => apiClient.deleteSession(sessionId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['sessions'] })
    },
  })
}

