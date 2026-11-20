import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { apiClient } from '@/lib/api-client'
import { pollInterval } from '@/lib/poll-interval'
import type { SessionInfo } from '@/types/api'

/** How often an open sessions list re-reads itself; a hidden tab does not. */
export const SESSIONS_REFRESH_MS = 30_000

/**
 * The sessions list. It re-reads itself when the window gets the focus back
 * and on a slow interval (never sooner than a 429's Retry-After), so an app
 * that has just connected appears without a reload.
 */
export function useSessions() {
  // Typed here: the interval function would otherwise widen the error type.
  return useQuery<SessionInfo[], Error>({
    queryKey: ['sessions'],
    queryFn: () => apiClient.listSessions(),
    refetchOnWindowFocus: true,
    refetchInterval: pollInterval(SESSIONS_REFRESH_MS),
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

/** A new session: its name, and the demo it is for (S-13), if any. */
export interface CreateSessionInput {
  name?: string
  demoId?: string
}

export function useCreateSession() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (input: CreateSessionInput = {}) => apiClient.createSession(input.name, input.demoId),
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

