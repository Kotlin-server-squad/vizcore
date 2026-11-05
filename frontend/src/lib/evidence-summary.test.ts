import { describe, it, expect } from 'vitest'
import { countEvidence, evidenceTabLabel, plural, resourceSummary } from './evidence-summary'
import type { VizEvent } from '@/types/api'

const ev = (kind: string, extra: Record<string, unknown> = {}) => ({ seq: 1, tsNanos: 1, sessionId: 's', kind, ...extra }) as unknown as VizEvent

describe('countEvidence', () => {
  it('counts each lock, semaphore, channel and flow once, however many events it has', () => {
    const counts = countEvidence([
      ev('MutexCreated', { mutexId: 'm-1' }),
      ev('MutexLockRequested', { mutexId: 'm-1' }),
      ev('MutexLockAcquired', { mutexId: 'm-2' }),
      ev('SemaphoreCreated', { semaphoreId: 'p-1' }),
      ev('ChannelSendStarted', { channelId: 'ch-1' }),
      ev('FlowValueEmitted', { flowId: 'f-1' }),
      ev('FlowCreated', { flowId: 'f-2' }),
      ev('JobStateChanged'),
      ev('CoroutineCreated', { coroutineId: 'c-1' }),
    ])
    expect(counts).toEqual({ locks: 2, semaphores: 1, channels: 1, flows: 2, jobEvents: 1 })
  })
})

describe('labels', () => {
  it('names what the Evidence tab holds (S-4)', () => {
    expect(evidenceTabLabel({ locks: 1, semaphores: 1, channels: 0, flows: 0, jobEvents: 3 })).toBe(
      'Evidence · 1 lock, 1 semaphore',
    )
    expect(evidenceTabLabel({ locks: 0, semaphores: 0, channels: 2, flows: 3, jobEvents: 0 })).toBe(
      'Evidence · 2 channels, 3 flows',
    )
  })

  it('is just "Evidence" when no resource was captured', () => {
    expect(evidenceTabLabel({ locks: 0, semaphores: 0, channels: 0, flows: 0, jobEvents: 4 })).toBe('Evidence')
  })

  it('summarises a section from its own counts', () => {
    expect(resourceSummary([plural(2, 'lock'), plural(0, 'semaphore')])).toBe('2 locks')
    expect(resourceSummary([plural(0, 'lock')])).toBeNull()
  })
})
