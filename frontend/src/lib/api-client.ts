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
import { normalizeEvents } from './utils'

const API_BASE_URL = '/api'

class ApiClient {
  private async fetchJson<T>(url: string, options?: RequestInit): Promise<T> {
    const response = await fetch(`${API_BASE_URL}${url}`, {
      ...options,
      headers: {
        'Content-Type': 'application/json',
        ...options?.headers,
      },
    })

    if (!response.ok) {
      const error = await response.json().catch(() => ({ error: 'Unknown error' }))
      throw new Error(error.error || `HTTP ${response.status}`)
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
    return this.fetchJson<SessionSnapshot>(`/sessions/${sessionId}`)
  }

  async deleteSession(sessionId: string): Promise<{ message: string }> {
    return this.fetchJson(`/sessions/${sessionId}`, { method: 'DELETE' })
  }

  // Wire shape: GET /sessions/{id}/events returns the FULL event list as a
  // bare JSON array (SessionRoutes.kt returns store.all() unconditionally).
  // The backend supports no pagination or filtering on this endpoint — do
  // not add query params here without implementing them server-side first.
  async getSessionEvents(sessionId: string): Promise<VizEvent[]> {
    const events = await this.fetchJson<unknown[]>(`/sessions/${sessionId}/events`)
    // Normalize events from backend format (type -> kind)
    return normalizeEvents(events)
  }

  // SSE Stream
  createEventSource(sessionId: string): EventSource {
    return new EventSource(`${API_BASE_URL}/sessions/${sessionId}/stream`)
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
    return this.fetchJson<ThreadActivity>(`/sessions/${sessionId}/threads`)
  }

  async getHierarchy(sessionId: string, scopeId?: string): Promise<HierarchyNode[]> {
    const url = scopeId 
      ? `/sessions/${sessionId}/hierarchy?scopeId=${encodeURIComponent(scopeId)}`
      : `/sessions/${sessionId}/hierarchy`
    return this.fetchJson<HierarchyNode[]>(url)
  }

  async getCoroutineTimeline(sessionId: string, coroutineId: string): Promise<CoroutineTimeline> {
    return this.fetchJson<CoroutineTimeline>(`/sessions/${sessionId}/coroutines/${coroutineId}/timeline`)
  }

  // Validation
  async validateSession(sessionId: string): Promise<ValidationResponse> {
    return this.fetchJson<ValidationResponse>(`/validate/session/${sessionId}`, { method: 'POST' })
  }

  // Wire shape: GET /api/validate/rules returns a BARE array of
  // {name, description} — no wrapper object and no id field
  // (see ValidationRoutes.kt).
  async getValidationRules(): Promise<Array<{ name: string; description: string }>> {
    return this.fetchJson(`/validate/rules`)
  }

  // Comparison
  async compareSessions(sessionA: string, sessionB: string): Promise<SessionComparison> {
    return this.fetchJson<SessionComparison>(
      `/compare?sessionA=${encodeURIComponent(sessionA)}&sessionB=${encodeURIComponent(sessionB)}`,
    )
  }
}

export const apiClient = new ApiClient()

