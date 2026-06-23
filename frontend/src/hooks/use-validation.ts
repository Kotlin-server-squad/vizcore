import { useMutation } from '@tanstack/react-query'
import { apiClient } from '@/lib/api-client'
import type { ValidationResponse } from '@/types/api'

/**
 * Calls POST /api/validate/session/{id} via apiClient.validateSession().
 * Returns loading/error/data states using TanStack Query mutation.
 */
export function useValidation(sessionId: string) {
  const mutation = useMutation<ValidationResponse, Error>({
    mutationFn: () => apiClient.validateSession(sessionId),
  })

  return {
    validate: mutation.mutate,
    validateAsync: mutation.mutateAsync,
    data: mutation.data ?? null,
    isLoading: mutation.isPending,
    error: mutation.error,
    isError: mutation.isError,
    isSuccess: mutation.isSuccess,
    reset: mutation.reset,
  }
}
