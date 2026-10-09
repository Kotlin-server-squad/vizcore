import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { apiClient, ApiError, NetworkError, RateLimitedError } from './api-client'
import { getToken, setToken, clearToken } from './auth-store'
import { registerNavigator } from './navigation'

const mockFetch = vi.fn()

beforeEach(() => {
  mockFetch.mockReset()
  vi.stubGlobal('fetch', mockFetch)
  clearToken()
  apiClient.clearRateLimits()
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

      await expect(apiClient.listSessions()).rejects.toThrow('HTTP 500')
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

  describe('revokeShare (204 No Content)', () => {
    it('resolves on a 204 instead of throwing on the empty body (F4)', async () => {
      // A real 204 has no body, so response.json() rejects with
      // "Unexpected end of JSON input". Before the fix that rejection turned a
      // successful revoke into a false failure (error toast + stale row).
      mockFetch.mockResolvedValue({
        ok: true,
        status: 204,
        json: () => Promise.reject(new SyntaxError('Unexpected end of JSON input')),
      })

      await expect(apiClient.revokeShare('session-1', 'tok-1')).resolves.toBeUndefined()
      expect(mockFetch).toHaveBeenCalledWith('/api/sessions/session-1/shares/tok-1', {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
      })
    })
  })

  describe('resolveCorrelation', () => {
    it('returns the session id when the token is bound (200)', async () => {
      mockFetch.mockResolvedValue(mockJsonResponse({ sessionId: 'order-service-42' }, 200))

      const result = await apiClient.resolveCorrelation('tok-abc')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/sessions/resolve?correlation=tok-abc',
        { headers: { 'Content-Type': 'application/json' } },
      )
      expect(result).toEqual({ sessionId: 'order-service-42' })
    })

    // Regression (live-UAT, Phase 09): the 404 "not bound yet" poll path MUST
    // resolve to null, never undefined. TanStack Query rejects an undefined
    // queryFn result ("Query data cannot be undefined"), which wedges the
    // ConnectWizard poll in an error state and breaks ONB-01 auto-navigation.
    it('returns null (not undefined) on 404 so the poll keeps running', async () => {
      mockFetch.mockResolvedValue(mockJsonResponse({ error: 'Session not found' }, 404))

      const result = await apiClient.resolveCorrelation('tok-not-bound')

      expect(result).toBeNull()
      expect(result).not.toBeUndefined()
    })

    it('url-encodes the correlation token', async () => {
      mockFetch.mockResolvedValue(mockJsonResponse({ error: 'Session not found' }, 404))

      await apiClient.resolveCorrelation('a b/c?d')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/sessions/resolve?correlation=a%20b%2Fc%3Fd',
        { headers: { 'Content-Type': 'application/json' } },
      )
    })
  })

  describe('truthful errors (#139)', () => {
    it('uses a short plain-text error body instead of "Unknown error"', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 400,
        statusText: 'Bad Request',
        text: () => Promise.resolve('Missing parameter: name'),
      })

      const error = await apiClient.listSessions().catch(e => e)

      expect(error).toBeInstanceOf(ApiError)
      expect(error.status).toBe(400)
      expect(error.message).toBe('Missing parameter: name')
    })

    it('never surfaces an HTML error page as the message', async () => {
      mockFetch.mockResolvedValue({
        ok: false,
        status: 502,
        statusText: 'Bad Gateway',
        text: () => Promise.resolve('<html><body>nginx</body></html>'),
      })

      await expect(apiClient.listSessions()).rejects.toThrow('HTTP 502 Bad Gateway')
    })

    it('throws a NetworkError when no response arrives', async () => {
      mockFetch.mockRejectedValue(new TypeError('Failed to fetch'))

      await expect(apiClient.getSession('s-1')).rejects.toBeInstanceOf(NetworkError)
    })

    it('getSharedSession distinguishes 5xx, 401 and network failure from not-found', async () => {
      mockFetch.mockResolvedValueOnce(mockJsonResponse({}, 503))
      expect(await apiClient.getSharedSession('t')).toEqual({ status: 'server-error', httpStatus: 503 })

      mockFetch.mockResolvedValueOnce(mockJsonResponse({}, 401))
      expect(await apiClient.getSharedSession('t')).toEqual({ status: 'unauthorized' })

      mockFetch.mockRejectedValueOnce(new TypeError('Failed to fetch'))
      expect(await apiClient.getSharedSession('t')).toEqual({ status: 'network-error' })

      mockFetch.mockResolvedValueOnce(mockJsonResponse({}, 404))
      expect(await apiClient.getSharedSession('t')).toEqual({ status: 'not-found' })
    })
  })

  describe('rate limiting (#124)', () => {
    function tooMany(retryAfter?: string) {
      return {
        ok: false,
        status: 429,
        headers: new Headers(retryAfter ? { 'Retry-After': retryAfter } : {}),
        json: () => Promise.reject(new Error('plain-text body')),
      }
    }

    afterEach(() => {
      vi.useRealTimers()
    })

    it('throws a typed RateLimitedError carrying Retry-After, never "Unknown error"', async () => {
      mockFetch.mockResolvedValue(tooMany('42'))

      const error = await apiClient.getSession('s-1').catch(e => e)

      expect(error).toBeInstanceOf(RateLimitedError)
      expect(error).toBeInstanceOf(ApiError)
      expect(error.status).toBe(429)
      expect(error.retryAfterMs).toBe(42_000)
    })

    it('fails fast without touching the network until Retry-After has elapsed', async () => {
      vi.useFakeTimers()
      mockFetch.mockResolvedValueOnce(tooMany('30'))
      await expect(apiClient.getSession('s-1')).rejects.toBeInstanceOf(RateLimitedError)
      expect(mockFetch).toHaveBeenCalledTimes(1)

      // Other reads inside the window are refused locally — no retry storm.
      await expect(apiClient.getMetrics('s-1')).rejects.toBeInstanceOf(RateLimitedError)
      await expect(apiClient.listSessions()).rejects.toBeInstanceOf(RateLimitedError)
      expect(mockFetch).toHaveBeenCalledTimes(1)

      // Writes are a separate bucket and still go out.
      mockFetch.mockResolvedValueOnce(mockJsonResponse({ message: 'ok' }))
      await apiClient.deleteSession('s-1')
      expect(mockFetch).toHaveBeenCalledTimes(2)

      // After the window, reads go out again.
      vi.advanceTimersByTime(30_001)
      mockFetch.mockResolvedValueOnce(mockJsonResponse([]))
      await expect(apiClient.listSessions()).resolves.toEqual([])
      expect(mockFetch).toHaveBeenCalledTimes(3)
    })

    it('falls back to a default cool-down when Retry-After is missing', async () => {
      mockFetch.mockResolvedValue(tooMany())

      const error = await apiClient.listSessions().catch(e => e)

      expect(error).toBeInstanceOf(RateLimitedError)
      expect(error.retryAfterMs).toBe(10_000)
    })

    it('resolveCorrelation surfaces 429 as RateLimitedError instead of silently returning null', async () => {
      mockFetch.mockResolvedValue(tooMany('5'))

      await expect(apiClient.resolveCorrelation('tok')).rejects.toBeInstanceOf(RateLimitedError)
    })

    it('resolveCorrelation surfaces server errors instead of mapping them to "not bound yet"', async () => {
      mockFetch.mockResolvedValue(mockJsonResponse({}, 503))

      const error = await apiClient.resolveCorrelation('tok').catch(e => e)

      expect(error).toBeInstanceOf(ApiError)
      expect(error.status).toBe(503)
    })
  })
})
