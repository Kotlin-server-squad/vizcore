import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { apiClient } from './api-client'
import { getToken, setToken, clearToken } from './auth-store'
import { registerNavigator } from './navigation'

const mockFetch = vi.fn()

beforeEach(() => {
  mockFetch.mockReset()
  vi.stubGlobal('fetch', mockFetch)
  clearToken()
})

afterEach(() => {
  vi.unstubAllGlobals()
  clearToken()
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

  describe('auth: Bearer header injection (D-07/D-08)', () => {
    it('attaches Authorization: Bearer <jwt> when a token is present', async () => {
      setToken('jwt-123')
      mockFetch.mockResolvedValue(mockJsonResponse([]))

      await apiClient.listSessions()

      const headers = mockFetch.mock.calls[0]![1]!.headers as Record<string, string>
      expect(headers.Authorization).toBe('Bearer jwt-123')
    })

    it('sends NO Authorization header when no token is set (auth-off invisibility, D-07)', async () => {
      mockFetch.mockResolvedValue(mockJsonResponse([]))

      await apiClient.listSessions()

      const headers = mockFetch.mock.calls[0]![1]!.headers as Record<string, string>
      expect(headers.Authorization).toBeUndefined()
    })
  })

  describe('auth: 401 interception (D-05)', () => {
    it('clears the token and navigates to /login on a 401, then rethrows', async () => {
      setToken('jwt-expired')
      const navSpy = vi.fn()
      registerNavigator(navSpy)
      mockFetch.mockResolvedValue({
        ok: false,
        status: 401,
        json: () => Promise.resolve({ error: 'Unauthorized' }),
      })

      await expect(apiClient.listSessions()).rejects.toThrow()

      expect(getToken()).toBeNull()
      expect(navSpy).toHaveBeenCalledWith('/login')
    })
  })

  describe('createEventSource: token-aware SSE (locked ?token= contract)', () => {
    let constructedUrls: string[]

    beforeEach(() => {
      constructedUrls = []
      class FakeEventSource {
        url: string
        constructor(url: string) {
          this.url = url
          constructedUrls.push(url)
        }
      }
      vi.stubGlobal('EventSource', FakeEventSource)
    })

    it('appends ?token=<jwt> when a token is present', () => {
      setToken('sse-jwt')
      apiClient.createEventSource('session-1')

      const url = constructedUrls[0]!
      expect(url).toContain('/api/sessions/session-1/stream')
      expect(url).toContain('token=sse-jwt')
    })

    it('omits the token query param when no token is set (auth-off)', () => {
      apiClient.createEventSource('session-1')

      const url = constructedUrls[0]!
      expect(url).toBe('/api/sessions/session-1/stream')
    })
  })

  describe('share methods (typed surface for Plan 06)', () => {
    it('createShare POSTs {expiresIn} to /api/sessions/:id/share', async () => {
      const response = { token: 'tok-1', url: 'http://x/shared/tok-1', expiresAt: null }
      mockFetch.mockResolvedValue(mockJsonResponse(response, 201))

      const result = await apiClient.createShare('session-1', '7d')

      const [url, init] = mockFetch.mock.calls[0]!
      expect(url).toBe('/api/sessions/session-1/share')
      expect(init!.method).toBe('POST')
      expect(JSON.parse(init!.body as string)).toEqual({ expiresIn: '7d' })
      expect(result).toEqual(response)
    })

    it('listShares GETs /api/sessions/:id/shares', async () => {
      const rows = [{ token: 't', expiresAt: null, accessCount: 0, lastAccessedAt: null }]
      mockFetch.mockResolvedValue(mockJsonResponse(rows))

      const result = await apiClient.listShares('session-1')

      expect(mockFetch.mock.calls[0]![0]).toBe('/api/sessions/session-1/shares')
      expect(result).toEqual(rows)
    })

    it('revokeShare DELETEs /api/sessions/:id/shares/:token', async () => {
      mockFetch.mockResolvedValue({ ok: true, status: 204, json: () => Promise.resolve({}) })

      await apiClient.revokeShare('session-1', 'tok-1')

      const [url, init] = mockFetch.mock.calls[0]!
      expect(url).toBe('/api/sessions/session-1/shares/tok-1')
      expect(init!.method).toBe('DELETE')
    })

    it('getSharedSession returns {status: ok, data} on 200', async () => {
      const body = { session: { sessionId: 's' }, events: [] }
      mockFetch.mockResolvedValue(mockJsonResponse(body))

      const result = await apiClient.getSharedSession('tok-1')

      expect(mockFetch.mock.calls[0]![0]).toBe('/api/shared/tok-1')
      expect(result).toEqual({ status: 'ok', data: body })
    })

    it('getSharedSession maps 410 to {status: expired}', async () => {
      mockFetch.mockResolvedValue({ ok: false, status: 410, json: () => Promise.resolve({}) })

      const result = await apiClient.getSharedSession('tok-1')
      expect(result).toEqual({ status: 'expired' })
    })

    it('getSharedSession maps 404 to {status: not-found}', async () => {
      mockFetch.mockResolvedValue({ ok: false, status: 404, json: () => Promise.resolve({}) })

      const result = await apiClient.getSharedSession('tok-1')
      expect(result).toEqual({ status: 'not-found' })
    })

    it('getSharedSession maps 429 to {status: rate-limited}', async () => {
      mockFetch.mockResolvedValue({ ok: false, status: 429, json: () => Promise.resolve({}) })

      const result = await apiClient.getSharedSession('tok-1')
      expect(result).toEqual({ status: 'rate-limited' })
    })

    it('getSharedSession does NOT attach a Bearer token (the share token is the credential)', async () => {
      setToken('some-jwt')
      mockFetch.mockResolvedValue(mockJsonResponse({ session: {}, events: [] }))

      await apiClient.getSharedSession('tok-1')

      const headers = mockFetch.mock.calls[0]![1]!.headers as Record<string, string>
      expect(headers.Authorization).toBeUndefined()
    })
  })
})
