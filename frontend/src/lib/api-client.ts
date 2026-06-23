import type {
  SessionInfo,
  SessionSnapshot,
  CreateSessionResponse,
  ScenarioCompletion,
  Scenario,
  VizEvent,
  ScenarioConfigRequest,
  ScenarioExecutionResponse,
  ThreadActivity,
  HierarchyNode,
  CoroutineTimeline,
  ValidationResponse,
  SessionComparison,
} from '@/types/api'
import type {
  CreateShareResponse,
  ShareExpiry,
  ShareSummary,
  SharedSessionResult,
} from '@/types/share'
import { normalizeEvents } from './utils'
import { getToken, clearToken } from './auth-store'
import { navigateToLogin } from './navigation'

const API_BASE_URL = '/api'

/**
 * Thrown by `login()` when the token endpoint returns 401 (wrong credentials).
 * The `/login` form branches on this type to show the "Incorrect username or
 * password." copy, vs. the network/server copy for any other failure.
 */
export class LoginAuthError extends Error {
  constructor() {
    super('Invalid credentials')
    this.name = 'LoginAuthError'
  }
}

/** `POST /api/auth/token` 200 response (Plan 02: {token, expiresAt}). */
export interface LoginResponse {
  token: string
  expiresAt: string
}

class ApiClient {
  private async fetchJson<T>(url: string, options?: RequestInit): Promise<T> {
    // Attach the JWT Bearer only when a token exists. When auth is off the
    // token is null and NO Authorization header is added — the request looks
    // exactly like today's anonymous call (auth-off invisibility, D-07/D-08).
    const token = getToken()
    const authHeaders: Record<string, string> = token
      ? { Authorization: `Bearer ${token}` }
      : {}

    const response = await fetch(`${API_BASE_URL}${url}`, {
      ...options,
      headers: {
        'Content-Type': 'application/json',
        ...authHeaders,
        ...options?.headers,
      },
    })

    if (!response.ok) {
      // A 401 means the JWT is missing/expired/invalid on a configured server.
      // Clear the stale token and route to /login (D-05); the error still
      // propagates so callers' error states render. When auth is off the
      // backend fails open and never returns 401, so this branch never fires
      // and /login is never reached (D-07).
      if (response.status === 401) {
        clearToken()
        navigateToLogin()
      }
      const error = await response.json().catch(() => ({ error: 'Unknown error' }))
      throw new Error(error.error || `HTTP ${response.status}`)
    }

    // 204 No Content has no body — calling response.json() on it throws
    // "Unexpected end of JSON input", which made a successful empty-body mutation
    // (DELETE /shares/{token} → 204) reject. That surfaced revoke as a false
    // failure: an error toast plus a stale row, even though the backend revoked
    // the link (F4). Treat No Content as an undefined result.
    if (response.status === 204) {
      return undefined as T
    }

    return response.json()
  }

  // Auth
  //
  // Posts credentials to the ALWAYS-public token endpoint (Plan 02). This does
  // NOT go through fetchJson because a 401 here means "wrong credentials" — it
  // must NOT trigger the global clearToken + navigateToLogin interception (the
  // user is already on /login). A 401 throws a typed LoginAuthError; any other
  // failure throws a generic Error so the form shows the network/server copy.
  // Credentials are never logged.
  async login(username: string, password: string): Promise<LoginResponse> {
    const response = await fetch(`${API_BASE_URL}/auth/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    })

    if (response.status === 401) {
      throw new LoginAuthError()
    }
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }
    return response.json()
  }

  // Session Management
  async createSession(name?: string): Promise<CreateSessionResponse> {
    const url = name ? `/sessions?name=${encodeURIComponent(name)}` : '/sessions'
    return this.fetchJson<CreateSessionResponse>(url, { method: 'POST' })
  }

  async listSessions(): Promise<SessionInfo[]> {
    return this.fetchJson<SessionInfo[]>('/sessions')
  }

  async getSession(sessionId: string): Promise<SessionSnapshot> {
    return this.fetchJson<SessionSnapshot>(`/sessions/${encodeURIComponent(sessionId)}`)
  }

  async deleteSession(sessionId: string): Promise<{ message: string }> {
    return this.fetchJson(`/sessions/${encodeURIComponent(sessionId)}`, { method: 'DELETE' })
  }

  // Wire shape: GET /sessions/{id}/events returns the FULL event list as a
  // bare JSON array (SessionRoutes.kt returns store.all() unconditionally).
  // The backend supports no pagination or filtering on this endpoint — do
  // not add query params here without implementing them server-side first.
  async getSessionEvents(sessionId: string): Promise<VizEvent[]> {
    const events = await this.fetchJson<unknown[]>(`/sessions/${encodeURIComponent(sessionId)}/events`)
    // Normalize events from backend format (type -> kind)
    return normalizeEvents(events)
  }

  // SSE Stream
  //
  // The browser EventSource cannot set an Authorization header (Pitfall 2), so
  // the authenticated live stream carries the JWT as a `?token=<jwt>` query
  // param — the LOCKED cross-plan contract the backend jwt provider reads
  // (SSE_TOKEN_QUERY_PARAM, Plan 02). When no token is set the URL is
  // unchanged (auth-off).
  createEventSource(sessionId: string): EventSource {
    const base = `${API_BASE_URL}/sessions/${encodeURIComponent(sessionId)}/stream`
    const token = getToken()
    const url = token ? `${base}?token=${encodeURIComponent(token)}` : base
    return new EventSource(url)
  }

  // Scenarios
  async listScenarios(): Promise<{ scenarios: Scenario[] }> {
    return this.fetchJson<{ scenarios: Scenario[] }>('/scenarios')
  }

  async runScenario(
    scenarioId: string,
    sessionId?: string,
    params?: Record<string, string>
  ): Promise<ScenarioCompletion> {
    const queryParams = new URLSearchParams()
    if (sessionId) queryParams.set('sessionId', sessionId)
    if (params) {
      Object.entries(params).forEach(([key, value]) => queryParams.set(key, value))
    }

    const query = queryParams.toString()
    const url = `/scenarios/${scenarioId}${query ? `?${query}` : ''}`

    return this.fetchJson<ScenarioCompletion>(url, { method: 'POST' })
  }

  async runCustomScenario(
    config: ScenarioConfigRequest
  ): Promise<ScenarioExecutionResponse> {
    return this.fetchJson<ScenarioExecutionResponse>('/scenarios/custom', {
      method: 'POST',
      body: JSON.stringify(config),
    })
  }

  // Thread Activity & Hierarchy
  // Wire shape: Map<threadId, ThreadEvent[]> (see ProjectionService.getThreadActivity).
  // The derived lane/dispatcher view model is built client-side via
  // buildThreadLanes in src/lib/thread-lanes.ts.
  async getThreadActivity(sessionId: string): Promise<ThreadActivity> {
    return this.fetchJson<ThreadActivity>(`/sessions/${encodeURIComponent(sessionId)}/threads`)
  }

  async getHierarchy(sessionId: string, scopeId?: string): Promise<HierarchyNode[]> {
    const url = scopeId 
      ? `/sessions/${encodeURIComponent(sessionId)}/hierarchy?scopeId=${encodeURIComponent(scopeId)}`
      : `/sessions/${encodeURIComponent(sessionId)}/hierarchy`
    return this.fetchJson<HierarchyNode[]>(url)
  }

  async getCoroutineTimeline(sessionId: string, coroutineId: string): Promise<CoroutineTimeline> {
    return this.fetchJson<CoroutineTimeline>(`/sessions/${encodeURIComponent(sessionId)}/coroutines/${encodeURIComponent(coroutineId)}/timeline`)
  }

  // Validation
  async validateSession(sessionId: string): Promise<ValidationResponse> {
    return this.fetchJson<ValidationResponse>(`/validate/session/${encodeURIComponent(sessionId)}`, { method: 'POST' })
  }

  // Wire shape: GET /api/validate/rules returns a BARE array of
  // {name, description} — no wrapper object and no id field
  // (see ValidationRoutes.kt).
  async getValidationRules(): Promise<Array<{ name: string; description: string }>> {
    return this.fetchJson(`/validate/rules`)
  }

  // Comparison
  async compareSessions(a: string, b: string): Promise<SessionComparison> {
    return this.fetchJson<SessionComparison>(
      `/sessions/compare?a=${encodeURIComponent(a)}&b=${encodeURIComponent(b)}`,
    )
  }

  // Sharing (typed surface consumed by Plan 06's sharing UI — the api-client is
  // the single /api choke point, so the share methods live here too).

  // Mint a read-only share link for a session (owner only). `expiresIn` is the
  // ADR-019 code 1d/7d/30d/never (D-11). Backend returns {token, url, expiresAt}.
  async createShare(sessionId: string, expiresIn: ShareExpiry): Promise<CreateShareResponse> {
    return this.fetchJson<CreateShareResponse>(
      `/sessions/${encodeURIComponent(sessionId)}/share`,
      { method: 'POST', body: JSON.stringify({ expiresIn }) },
    )
  }

  // List the active share links for a session (owner only).
  async listShares(sessionId: string): Promise<ShareSummary[]> {
    return this.fetchJson<ShareSummary[]>(`/sessions/${encodeURIComponent(sessionId)}/shares`)
  }

  // Revoke a share link by token (owner only). Revoke deletes the row, so a
  // revoked token is thereafter indistinguishable from an unknown one (404).
  async revokeShare(sessionId: string, token: string): Promise<void> {
    await this.fetchJson<unknown>(
      `/sessions/${encodeURIComponent(sessionId)}/shares/${encodeURIComponent(token)}`,
      { method: 'DELETE' },
    )
  }

  /**
   * Server capability flags read from /health. `sharingEnabled` is false in
   * memory mode (storage.type=memory), where the share routes are absent — the
   * UI uses it to gate the Share affordance rather than offering an action that
   * 404s.
   */
  async getCapabilities(): Promise<{ sharingEnabled: boolean }> {
    const health = await this.fetchJson<{ sharingEnabled?: boolean }>('/health')
    return { sharingEnabled: health.sharingEnabled ?? false }
  }

  // PUBLIC shared-session read — the token IS the credential, so NO Bearer is
  // attached (a raw fetch, not fetchJson, so a 401-clear never fires here).
  // The 410/404/429 status matrix (D-12, ADR-019) is mapped to a typed result
  // the shared view (Plan 06) branches on instead of catching raw errors.
  async getSharedSession(token: string): Promise<SharedSessionResult> {
    const response = await fetch(
      `${API_BASE_URL}/shared/${encodeURIComponent(token)}`,
      { headers: { 'Content-Type': 'application/json' } },
    )

    if (response.ok) {
      const data = await response.json()
      return { status: 'ok', data }
    }
    if (response.status === 410) return { status: 'expired' }
    if (response.status === 429) return { status: 'rate-limited' }
    // 404 (unknown OR revoked) and any other non-ok status fall through to
    // not-found — the public view has no credential to retry with.
    return { status: 'not-found' }
  }
}

export const apiClient = new ApiClient()

