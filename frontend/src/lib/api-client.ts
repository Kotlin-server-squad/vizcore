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
  PaginatedEventsRequest,
  PaginatedEventsResponse,
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

  async getSessionEvents(sessionId: string, options?: {
    sinceStep?: number
    limit?: number
    filter?: string
  }): Promise<VizEvent[]> {
    const params = new URLSearchParams()
    if (options?.sinceStep !== undefined) params.set('sinceStep', options.sinceStep.toString())
    if (options?.limit) params.set('limit', options.limit.toString())
    if (options?.filter) params.set('filter', options.filter)
    
    const query = params.toString()
    const url = `/sessions/${sessionId}/events${query ? `?${query}` : ''}`
    const events = await this.fetchJson<any[]>(url)
    // Normalize events from backend format (type -> kind)
    return normalizeEvents(events)
  }

  async getSessionEventsPaginated(options: PaginatedEventsRequest): Promise<PaginatedEventsResponse> {
    const params = new URLSearchParams()
    if (options.sinceStep !== undefined) params.set('sinceStep', options.sinceStep.toString())
    if (options.limit) params.set('limit', options.limit.toString())
    if (options.filter) {
      // Convert filter object to query string (simple implementation)
      const filterStr = Object.entries(options.filter)
        .map(([key, value]) => `${key}:${Array.isArray(value) ? value.join(',') : value}`)
        .join(' AND ')
      params.set('filter', filterStr)
    }
    
    const query = params.toString()
    const url = `/sessions/${options.sessionId}/events${query ? `?${query}` : ''}`
    const response = await this.fetchJson<any>(url)
    // Normalize events from backend format (type -> kind)
    return {
      ...response,
      events: normalizeEvents(response.events || [])
    }
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

  async getValidationRules(): Promise<{ rules: Array<{ id: string; name: string; description: string }> }> {
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

