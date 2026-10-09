import { describe, it, expect } from 'vitest'
import type { ThreadActivity, ThreadEvent } from '@/types/api'
import { resolveRunsOn } from './runs-on'

function event(overrides: Partial<ThreadEvent> = {}): ThreadEvent {
  return {
    coroutineId: 'c-1',
    threadId: 34,
    threadName: 'DefaultDispatcher-worker-1',
    timestamp: 1_000,
    eventType: 'ASSIGNED',
    dispatcherName: 'Dispatchers.Default',
    ...overrides,
  }
}

describe('resolveRunsOn', () => {
  it('names the thread and dispatcher a coroutine was assigned to', () => {
    const activity: ThreadActivity = { '34': [event()] }

    expect(resolveRunsOn(activity, 'c-1')).toEqual({
      threadName: 'DefaultDispatcher-worker-1',
      dispatcherName: 'Dispatchers.Default',
    })
  })

  it('takes the newest assignment when a coroutine has moved threads', () => {
    const activity: ThreadActivity = {
      '34': [event({ timestamp: 1_000 })],
      '80': [
        event({
          threadId: 80,
          threadName: 'DefaultDispatcher-worker-5',
          dispatcherName: 'Dispatchers.IO',
          timestamp: 5_000,
        }),
      ],
    }

    // A coroutine that moved from Default to IO runs on IO now; reporting the
    // first assignment would name a thread it has long since left.
    expect(resolveRunsOn(activity, 'c-1')).toEqual({
      threadName: 'DefaultDispatcher-worker-5',
      dispatcherName: 'Dispatchers.IO',
    })
  })

  it('ignores events belonging to other coroutines', () => {
    const activity: ThreadActivity = {
      '34': [event({ coroutineId: 'c-other', threadName: 'wrong-thread', timestamp: 9_000 })],
      '80': [event({ threadId: 80, threadName: 'right-thread', timestamp: 1_000 })],
    }

    expect(resolveRunsOn(activity, 'c-1')?.threadName).toBe('right-thread')
  })

  it('does not name a thread the coroutine has been released from', () => {
    const activity: ThreadActivity = {
      '34': [
        event({ timestamp: 1_000 }),
        event({ timestamp: 2_000, eventType: 'RELEASED' }),
      ],
    }

    // RELEASED means it is no longer there. Reading the newest event of ANY
    // kind would report the thread it just left as the thread it runs on.
    expect(resolveRunsOn(activity, 'c-1')).toBeNull()
  })

  it('still resolves an earlier assignment on another thread after a release', () => {
    const activity: ThreadActivity = {
      '34': [event({ timestamp: 5_000, eventType: 'RELEASED' })],
      '80': [
        event({ threadId: 80, threadName: 'still-here', timestamp: 6_000 }),
      ],
    }

    expect(resolveRunsOn(activity, 'c-1')?.threadName).toBe('still-here')
  })

  it('returns null for a coroutine with no thread events', () => {
    expect(resolveRunsOn({ '34': [event()] }, 'c-unknown')).toBeNull()
  })

  it('returns null when there is no thread activity at all', () => {
    expect(resolveRunsOn(undefined, 'c-1')).toBeNull()
    expect(resolveRunsOn({}, 'c-1')).toBeNull()
  })

  it('reports a thread even when the dispatcher is not named', () => {
    const activity: ThreadActivity = { '34': [event({ dispatcherName: null })] }

    // Partial data is still worth showing — the card reports "not reported"
    // per field, not per card.
    expect(resolveRunsOn(activity, 'c-1')).toEqual({
      threadName: 'DefaultDispatcher-worker-1',
      dispatcherName: null,
    })
  })
})
