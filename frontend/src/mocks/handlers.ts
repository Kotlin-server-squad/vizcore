import { http, HttpResponse } from 'msw'
import { CoroutineState } from '@/types/api'
import type { SessionInfo, SessionSnapshot, Scenario } from '@/types/api'
import {
  generateCompleteScenario,
  generateMockCoroutineTimeline,
  hierarchyTreeToList,
} from './mock-data'

// Mock data
const mockSessions: SessionInfo[] = [
  { sessionId: 'session-1', coroutineCount: 5 },
  { sessionId: 'session-2', coroutineCount: 12 },
]

const mockScenarios: Scenario[] = [
  // ========== REALISTIC SCENARIOS (Featured) ==========
  {
    id: 'order-processing',
    name: '🛒 Order Processing',
    description: 'E-commerce flow: validation → inventory check → payment → database → notifications (parallel). Use ?fail=true to simulate payment failure.',
    endpoint: '/api/scenarios/order-processing',
    category: 'realistic',
    duration: '~15-20 seconds',
  },
  {
    id: 'user-registration',
    name: '👤 User Registration',
    description: 'Complete registration: validation → DB check → create user → parallel setup (profile, settings, avatar) → notifications. Use ?failEmail=true to simulate email retry.',
    endpoint: '/api/scenarios/user-registration',
    category: 'realistic',
    duration: '~18-25 seconds',
  },
  {
    id: 'report-generation',
    name: '📊 Report Generation',
    description: 'Data pipeline: parallel API fetches → data aggregation → PDF generation → parallel delivery (S3, email, Slack). Use ?timeout=true to simulate API timeout.',
    endpoint: '/api/scenarios/report-generation',
    category: 'realistic',
    duration: '~25-35 seconds',
  },
  // ========== BASIC SCENARIOS ==========
  {
    id: 'nested',
    name: 'Nested Coroutines',
    description: 'Demonstrates parent-child relationships and structured concurrency',
    endpoint: '/api/scenarios/nested',
    category: 'basic',
  },
  {
    id: 'parallel',
    name: 'Parallel Execution',
    description: 'Multiple coroutines running in parallel',
    endpoint: '/api/scenarios/parallel',
    category: 'basic',
  },
  {
    id: 'cancellation',
    name: 'Cancellation Propagation',
    description: 'Shows how cancellation propagates through coroutine hierarchy',
    endpoint: '/api/scenarios/cancellation',
    category: 'basic',
  },
  {
    id: 'exception',
    name: 'Exception Handling',
    description: 'Demonstrates exception propagation and handling',
    endpoint: '/api/scenarios/exception',
    category: 'basic',
  },
]

// Generate mock scenarios for different sessions
const mockScenarioData = new Map<string, ReturnType<typeof generateCompleteScenario>>()

function getOrCreateScenarioData(sessionId: string) {
  if (!mockScenarioData.has(sessionId)) {
    mockScenarioData.set(sessionId, generateCompleteScenario({
      hierarchyDepth: 3,
      hierarchyBreadth: 2,
      threadCount: 4,
      segmentsPerThread: 6
    }))
  }
  return mockScenarioData.get(sessionId)!
}

export const handlers = [
  // List sessions
  http.get('/api/sessions', () => {
    return HttpResponse.json(mockSessions)
  }),

  // Get session
  http.get('/api/sessions/:sessionId', ({ params }) => {
    const { sessionId } = params
    
    const mockSnapshot: SessionSnapshot = {
      sessionId: sessionId as string,
      coroutineCount: 3,
      eventCount: 9,
      coroutines: [
        {
          id: 'coro-1',
          jobId: 'job-1',
          parentId: null,
          scopeId: 'scope-1',
          label: 'parent',
          state: CoroutineState.COMPLETED,
        },
        {
          id: 'coro-2',
          jobId: 'job-2',
          parentId: 'coro-1',
          scopeId: 'scope-1',
          label: 'child-1',
          state: CoroutineState.COMPLETED,
        },
        {
          id: 'coro-3',
          jobId: 'job-3',
          parentId: 'coro-2',
          scopeId: 'scope-1',
          label: 'child-1-1',
          state: CoroutineState.COMPLETED,
        },
      ],
    }

    return HttpResponse.json(mockSnapshot)
  }),

  // List scenarios
  http.get('/api/scenarios', () => {
    return HttpResponse.json({ scenarios: mockScenarios })
  }),

  // Create session
  http.post('/api/sessions', () => {
    return HttpResponse.json({
      sessionId: `session-${Date.now()}`,
      message: 'Session created successfully',
    })
  }),

  // Delete session
  http.delete('/api/sessions/:sessionId', () => {
    return HttpResponse.json({ message: 'Session closed' })
  }),

  // Run scenario
  http.post('/api/scenarios/:scenarioId', ({ params }) => {
    const sessionId = `session-${Date.now()}`
    // Pre-generate scenario data for this session
    getOrCreateScenarioData(sessionId)
    
    return HttpResponse.json({
      success: true,
      sessionId,
      message: `Scenario ${params.scenarioId} completed`,
      coroutineCount: 5,
      eventCount: 15,
    })
  }),

  // ============================================================================
  // NEW ENDPOINTS - Hierarchy & Thread Activity
  // ============================================================================

  // Get session hierarchy (returns tree structure)
  http.get('/api/sessions/:sessionId/hierarchy', ({ params, request }) => {
    const { sessionId } = params
    const url = new URL(request.url)
    const scopeId = url.searchParams.get('scopeId')
    
    const scenarioData = getOrCreateScenarioData(sessionId as string)
    let hierarchy = hierarchyTreeToList(scenarioData.hierarchy)
    
    // Filter by scopeId if provided
    if (scopeId) {
      hierarchy = hierarchy.filter(node => node.scopeId === scopeId)
    }

    // Return flat list (backend can return nested tree or flat list)
    // For now, returning flat list which is easier to work with
    return HttpResponse.json(hierarchy)
  }),

  // Get thread activity for session
  http.get('/api/sessions/:sessionId/threads', ({ params }) => {
    const { sessionId } = params
    const scenarioData = getOrCreateScenarioData(sessionId as string)
    
    return HttpResponse.json(scenarioData.threadActivity)
  }),

  // Get timeline for specific coroutine
  http.get('/api/sessions/:sessionId/coroutines/:coroutineId/timeline', ({ params }) => {
    const { coroutineId } = params
    const timeline = generateMockCoroutineTimeline(coroutineId as string, 12)
    
    return HttpResponse.json(timeline)
  }),

  // Get session events.
  // Real wire shape (IN-11): the backend returns the FULL event list as a
  // BARE JSON array (SessionRoutes.kt returns store.all() unconditionally) —
  // no pagination wrapper, no query params. Keep this mock byte-compatible
  // with production so apiClient.getSessionEvents behaves identically in
  // dev-mode MSW and against the real backend.
  http.get('/api/sessions/:sessionId/events', () => {
    return HttpResponse.json([])
  }),
]

