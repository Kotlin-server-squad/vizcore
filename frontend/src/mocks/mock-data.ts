/**
 * Mock Data Generators for Frontend Development
 * 
 * These utilities generate realistic mock data for the new backend APIs
 * while the backend implementation is in progress.
 */

import {
  CoroutineState,
} from '@/types/api'
import type {
  HierarchyNode,
  HierarchyNodeTree,
  ThreadActivity,
  ThreadEvent,
  CoroutineTimeline,
  TimelineEvent,
  SuspensionPoint,
} from '@/types/api'

// ============================================================================
// HIERARCHY MOCK DATA
// ============================================================================

export function generateMockHierarchyNode(
  id: string,
  parentId: string | null,
  state: CoroutineState = CoroutineState.COMPLETED,
  options?: {
    name?: string
    scopeId?: string
    childCount?: number
    dispatcherName?: string
  }
): HierarchyNode {
  const now = Date.now() * 1_000_000  // Convert to nanos
  const duration = Math.random() * 1000 * 1_000_000  // 0-1000ms
  
  return {
    id,
    parentId,
    children: options?.childCount 
      ? Array.from({ length: options.childCount }, (_, i) => `${id}-child-${i}`)
      : [],
    name: options?.name || `coroutine-${id}`,
    scopeId: options?.scopeId || 'scope-root',
    state,
    createdAtNanos: now,
    completedAtNanos: state === CoroutineState.COMPLETED || state === CoroutineState.FAILED || state === CoroutineState.CANCELLED
      ? now + duration
      : null,
    currentThreadId: state === CoroutineState.ACTIVE ? Math.floor(Math.random() * 4) + 1 : null,
    currentThreadName: state === CoroutineState.ACTIVE ? `DefaultDispatcher-worker-${Math.floor(Math.random() * 4) + 1}` : null,
    dispatcherId: options?.dispatcherName ? `dispatcher-${options.dispatcherName}` : 'dispatcher-Default',
    dispatcherName: options?.dispatcherName || 'Default',
    jobId: `job-${id}`,
    activeTime: duration * 0.7,  // 70% active
    suspendedTime: duration * 0.3,  // 30% suspended
    suspensionPoints: state === CoroutineState.SUSPENDED ? [generateMockSuspensionPoint()] : []
  }
}

export function generateMockHierarchyTree(depth: number = 3, breadth: number = 2): HierarchyNodeTree {
  function buildTree(
    id: string,
    parentId: string | null,
    currentDepth: number,
    maxDepth: number
  ): HierarchyNodeTree {
    const hasChildren = currentDepth < maxDepth
    const childCount = hasChildren ? breadth : 0
    const state: CoroutineState = currentDepth === 0 ? CoroutineState.COMPLETED : (Math.random() > 0.7 ? CoroutineState.ACTIVE : CoroutineState.COMPLETED)
    
    const node = generateMockHierarchyNode(id, parentId, state, {
      name: `${currentDepth === 0 ? 'root' : `level-${currentDepth}`}-${id}`,
      childCount,
      dispatcherName: Math.random() > 0.5 ? 'Default' : 'IO'
    })

    const children = hasChildren
      ? Array.from({ length: childCount }, (_, i) => 
          buildTree(`${id}-${i}`, id, currentDepth + 1, maxDepth)
        )
      : []

    return { ...node, children }
  }

  return buildTree('coro-root', null, 0, depth)
}

// ============================================================================
// SUSPENSION POINT MOCK DATA
// ============================================================================

export function generateMockSuspensionPoint(): SuspensionPoint {
  const reasons = ['delay', 'withContext', 'join', 'await', 'mutex.lock', 'channel.send']
  const functions = [
    'processData',
    'fetchUser',
    'computeResult',
    'handleRequest',
    'syncDatabase'
  ]
  const files = [
    'UserService.kt',
    'DataProcessor.kt',
    'ApiHandler.kt',
    'DatabaseSync.kt'
  ]

  return {
    function: functions[Math.floor(Math.random() * functions.length)] ?? 'unknown',
    fileName: files[Math.floor(Math.random() * files.length)],
    lineNumber: Math.floor(Math.random() * 200) + 10,
    reason: reasons[Math.floor(Math.random() * reasons.length)] ?? 'unknown'
  }
}

// ============================================================================
// THREAD ACTIVITY MOCK DATA
// ============================================================================

/**
 * Generate thread activity in the REAL wire shape served by
 * GET /sessions/{id}/threads: Map<threadId, ThreadEvent[]>
 * (see backend ProjectionService.getThreadActivity).
 *
 * Keys are stringified thread ids; values are ThreadEvent arrays with paired
 * ASSIGNED/RELEASED entries, monotonic nano timestamps, threadName
 * `worker-{id}`, and dispatcherName alternating 'Default'/'IO'.
 *
 * Serving this shape through MSW guarantees the mock matches the backend
 * serializer output — the regression trap that let a fictional
 * {threads, dispatcherInfo} shape ship green (REVIEW CR-02) is closed.
 */
export function generateMockThreadActivityWire(
  threadCount: number = 4,
  eventsPerThread: number = 4
): ThreadActivity {
  const dispatchers = ['Default', 'IO']
  const activity: ThreadActivity = {}
  const baseTime = Date.now() * 1_000_000  // nanos

  for (let i = 1; i <= threadCount; i++) {
    const dispatcherName = dispatchers[i % 2] ?? 'Default'
    const threadName = `worker-${i}`
    const events: ThreadEvent[] = []

    let currentTime = baseTime + i * 1_000_000  // stagger threads by 1ms
    for (let j = 0; j < eventsPerThread; j++) {
      const pairIndex = Math.floor(j / 2)
      events.push({
        coroutineId: `coro-${i}-${pairIndex}`,
        threadId: i,
        threadName,
        timestamp: currentTime,
        eventType: j % 2 === 0 ? 'ASSIGNED' : 'RELEASED',
        dispatcherName,
      })
      currentTime += 50_000_000  // 50ms between events (monotonic)
    }

    activity[String(i)] = events
  }

  return activity
}

// ============================================================================
// TIMELINE MOCK DATA
// ============================================================================

export function generateMockTimelineEvent(
  seq: number,
  kind: TimelineEvent['kind'],
  baseTime: number
): TimelineEvent {
  const event: TimelineEvent = {
    seq,
    tsNanos: baseTime + seq * 10_000_000,  // 10ms between events
    kind,
  }

  if (kind === 'coroutine.started' || kind === 'coroutine.resumed') {
    const threadId = Math.floor(Math.random() * 4) + 1
    event.threadName = `DefaultDispatcher-worker-${threadId}`
    event.dispatcherName = 'Default'
  }

  if (kind === 'coroutine.suspended') {
    event.suspensionPoint = generateMockSuspensionPoint()
  }

  return event
}

export function generateMockCoroutineTimeline(
  coroutineId: string,
  _eventCount: number = 10
): CoroutineTimeline {
  const baseTime = Date.now() * 1_000_000
  const events: TimelineEvent[] = []
  
  const eventSequence: Array<TimelineEvent['kind']> = [
    'coroutine.created',
    'coroutine.started',
    'coroutine.suspended',
    'coroutine.resumed',
    'coroutine.completed'
  ]

  eventSequence.forEach((kind, i) => {
    events.push(generateMockTimelineEvent(i, kind, baseTime))
  })

  const lastEvent = events[events.length - 1]
  const firstEvent = events[0]
  const totalDuration = lastEvent && firstEvent ? lastEvent.tsNanos - firstEvent.tsNanos : 0
  const suspendedDuration = events
    .filter(e => e.kind === 'coroutine.suspended')
    .reduce((sum, e, i) => {
      const nextEvent = events[i + 1]
      return sum + (nextEvent ? nextEvent.tsNanos - e.tsNanos : 0)
    }, 0)

  return {
    coroutineId,
    name: `coroutine-${coroutineId}`,
    state: CoroutineState.COMPLETED,
    parentId: null,
    childrenIds: [],
    totalDuration,
    activeDuration: totalDuration - suspendedDuration,
    suspendedDuration,
    events
  }
}

// ============================================================================
// COMPLETE MOCK SCENARIOS
// ============================================================================

/**
 * Generate a complete mock scenario with hierarchy, threads, and timelines
 */
export function generateCompleteScenario(options?: {
  hierarchyDepth?: number
  hierarchyBreadth?: number
  threadCount?: number
  segmentsPerThread?: number
}) {
  const hierarchyTree = generateMockHierarchyTree(
    options?.hierarchyDepth ?? 3,
    options?.hierarchyBreadth ?? 2
  )
  
  // Wire shape: Map<threadId, ThreadEvent[]> — exactly what the backend
  // serves. Each "segment" is an ASSIGNED/RELEASED pair (2 events).
  const threadActivity = generateMockThreadActivityWire(
    options?.threadCount ?? 4,
    (options?.segmentsPerThread ?? 5) * 2
  )

  // Flatten hierarchy to get all coroutine IDs
  const allCoroutines: HierarchyNode[] = []
  function flattenTree(node: HierarchyNodeTree) {
    allCoroutines.push(node)
    node.children.forEach(flattenTree)
  }
  flattenTree(hierarchyTree)

  // Generate timeline for first few coroutines
  const timelines = allCoroutines.slice(0, 3).map(node =>
    generateMockCoroutineTimeline(node.id, 10)
  )

  return {
    hierarchy: hierarchyTree,
    hierarchyFlat: allCoroutines,
    threadActivity,
    timelines
  }
}

// ============================================================================
// UTILITIES
// ============================================================================

/**
 * Convert flat hierarchy list to tree structure
 */
export function hierarchyListToTree(nodes: HierarchyNode[]): HierarchyNodeTree[] {
  const nodeMap = new Map<string, HierarchyNodeTree>()
  
  // Create map with empty children arrays
  nodes.forEach(node => {
    nodeMap.set(node.id, { ...node, children: [] })
  })

  // Build tree structure
  const roots: HierarchyNodeTree[] = []
  nodes.forEach(node => {
    const treeNode = nodeMap.get(node.id)!
    if (node.parentId && nodeMap.has(node.parentId)) {
      nodeMap.get(node.parentId)!.children.push(treeNode)
    } else {
      roots.push(treeNode)
    }
  })

  return roots
}

/**
 * Flatten tree structure to list
 */
export function hierarchyTreeToList(tree: HierarchyNodeTree | HierarchyNodeTree[]): HierarchyNode[] {
  const result: HierarchyNode[] = []
  const trees = Array.isArray(tree) ? tree : [tree]

  function traverse(node: HierarchyNodeTree) {
    const { children, ...nodeData } = node
    result.push({
      ...nodeData,
      children: children.map(c => c.id)
    })
    children.forEach(traverse)
  }

  trees.forEach(traverse)
  return result
}

