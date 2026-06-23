import { useQuery } from '@tanstack/react-query'
import { apiClient } from '@/lib/api-client'

/**
 * Server capability flags from /health. Notably `sharingEnabled`, which is false
 * in memory mode (storage.type=memory) where the share routes are absent. The UI
 * gates the Share affordance on this so it never offers an action that 404s.
 *
 * Cached generously — capabilities are fixed for the lifetime of the server.
 */
export function useCapabilities() {
  return useQuery({
    queryKey: ['capabilities'],
    queryFn: () => apiClient.getCapabilities(),
    staleTime: Infinity,
  })
}
