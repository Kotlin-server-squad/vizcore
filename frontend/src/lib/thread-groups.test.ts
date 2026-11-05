import { describe, it, expect } from 'vitest'
import { earliestThreadEvent, groupThreadsByName, threadSummary } from './thread-groups'
import type { ThreadActivity, ThreadEvent } from '@/types/api'

const e = (threadId: number, threadName: string, coroutineId: string, timestamp: number, eventType: ThreadEvent['eventType'] = 'ASSIGNED', dispatcherName: string | null = 'Default'): ThreadEvent =>
  ({ threadId, threadName, coroutineId, timestamp, eventType, dispatcherName })

const activity: ThreadActivity = {
  '57': [e(57, 'worker-1', 'c-1', 1_000), e(57, 'worker-1', 'c-1', 2_000, 'RELEASED'), e(57, 'worker-1', 'c-2', 3_000)],
  // A pool thread that died and came back under the same name: a second JVM thread.
  '91': [e(91, 'worker-1', 'c-3', 9_000)],
  '80': [e(80, 'worker-2', 'c-2', 4_000, 'ASSIGNED', 'IO')],
}

describe('groupThreadsByName', () => {
  it('groups by thread name with JVM threads, coroutines seen, assignments and the last assignment', () => {
    expect(groupThreadsByName(activity)).toEqual([
      { name: 'worker-1', jvmThreads: 2, coroutinesSeen: 3, assignments: 3, lastAssignedNanos: 9_000 },
      { name: 'worker-2', jvmThreads: 1, coroutinesSeen: 1, assignments: 1, lastAssignedNanos: 4_000 },
    ])
  })

  it('names a thread with no name by its id', () => {
    expect(groupThreadsByName({ '7': [e(7, '', 'c-1', 1)] })[0]!.name).toBe('Thread 7')
  })

  it('is empty without activity', () => {
    expect(groupThreadsByName(undefined)).toEqual([])
  })
})

describe('threadSummary', () => {
  it('reads names and JVM threads', () => {
    expect(threadSummary(groupThreadsByName(activity))).toBe('2 names (3 JVM threads)')
  })

  it('says none captured when empty', () => {
    expect(threadSummary([])).toBe('none captured')
  })
})

describe('earliestThreadEvent', () => {
  it('is the first thread event, or null', () => {
    expect(earliestThreadEvent(activity)).toBe(1_000)
    expect(earliestThreadEvent({})).toBeNull()
  })
})
