import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { apiClient } from './api-client'

const mockFetch = vi.fn()

beforeEach(() => {
  mockFetch.mockReset()
  vi.stubGlobal('fetch', mockFetch)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

function mockJsonResponse(data: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(data),
  }
}

describe('ApiClient', () => {
  describe('listSessions', () => {
    it('makes GET request to /api/sessions', async () => {
      const sessions = [
        { sessionId: 'session-1', coroutineCount: 5 },
        { sessionId: 'session-2', coroutineCount: 12 },
      ]
      mockFetch.mockResolvedValue(mockJsonResponse(sessions))

      const result = await apiClient.listSessions()

      expect(mockFetch).toHaveBeenCalledWith('/api/sessions', {
        headers: { 'Content-Type': 'application/json' },
      })
      expect(result).toEqual(sessions)
    })
  })

  describe('getSession', () => {
    it('makes GET request to /api/sessions/:id', async () => {
      const snapshot = {
        sessionId: 'session-1',
        coroutineCount: 5,
        eventCount: 20,
        coroutines: [],
      }
      mockFetch.mockResolvedValue(mockJsonResponse(snapshot))

      const result = await apiClient.getSession('session-1')

      expect(mockFetch).toHaveBeenCalledWith('/api/sessions/session-1', {
        headers: { 'Content-Type': 'application/json' },
      })
      expect(result).toEqual(snapshot)
    })
  })

  describe('createSession', () => {
    it('makes POST request to /api/sessions without name', async () => {
      const response = { sessionId: 'session-new', message: 'Created' }
      mockFetch.mockResolvedValue(mockJsonResponse(response))

      const result = await apiClient.createSession()

      expect(mockFetch).toHaveBeenCalledWith('/api/sessions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
      })
      expect(result).toEqual(response)
    })

    it('includes name as query parameter when provided', async () => {
      const response = { sessionId: 'session-new', message: 'Created' }
      mockFetch.mockResolvedValue(mockJsonResponse(response))

      await apiClient.createSession('My Session')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/sessions?name=My%20Session',
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
        }
      )
    })
  })

  describe('deleteSession', () => {
    it('makes DELETE request to /api/sessions/:id', async () => {
      const response = { message: 'Deleted' }
      mockFetch.mockResolvedValue(mockJsonResponse(response))

      const result = await apiClient.deleteSession('session-1')

      expect(mockFetch).toHaveBeenCalledWith('/api/sessions/session-1', {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
      })
      expect(result).toEqual(response)
    })
  })

  describe('error handling', () => {
    it('throws error with message from error response', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 404,
        json: () => Promise.resolve({ error: 'Session not found' }),
      })

      await expect(apiClient.getSession('non-existent')).rejects.toThrow(
        'Session not found'
      )
    })

    it('throws generic HTTP error when response has no error message', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 500,
        json: () => Promise.resolve({}),
      })

      await expect(apiClient.listSessions()).rejects.toThrow('HTTP 500')
    })

    it('throws generic error when response body is not JSON', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 500,
        json: () => Promise.reject(new Error('not json')),
      })

      await expect(apiClient.listSessions()).rejects.toThrow('Unknown error')
    })
  })

  describe('getSessionEvents', () => {
    it('makes a plain GET request — the endpoint supports no pagination/filter params (WR-09)', async () => {
      const events = [{ kind: 'coroutine.created', seq: 1 }]
      mockFetch.mockResolvedValue(mockJsonResponse(events))

      const result = await apiClient.getSessionEvents('session-1')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/sessions/session-1/events',
        { headers: { 'Content-Type': 'application/json' } }
      )
      expect(result).toEqual(events)
    })
  })

  describe('getHierarchy', () => {
    it('makes GET request to hierarchy endpoint', async () => {
      mockFetch.mockResolvedValue(mockJsonResponse([]))

      await apiClient.getHierarchy('session-1')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/sessions/session-1/hierarchy',
        { headers: { 'Content-Type': 'application/json' } }
      )
    })

    it('includes scopeId when provided', async () => {
      mockFetch.mockResolvedValue(mockJsonResponse([]))

      await apiClient.getHierarchy('session-1', 'scope-123')

      const calledUrl = mockFetch.mock.calls[0]![0] as string
      expect(calledUrl).toContain('scopeId=scope-123')
    })
  })
})
