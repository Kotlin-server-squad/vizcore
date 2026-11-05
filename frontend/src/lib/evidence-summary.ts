import { CHANNEL_EVENT_KINDS, FLOW_EVENT_KINDS, JOB_EVENT_KINDS, SYNC_EVENT_KINDS, type VizEvent } from '@/types/api'

/** What the session's events show beyond the tree: distinct resources, and job events. */
export interface EvidenceCounts {
  locks: number
  semaphores: number
  channels: number
  flows: number
  jobEvents: number
}

/** Fields the resource events carry; each kind has at most one of them. */
type ResourceIds = { mutexId?: string; semaphoreId?: string; channelId?: string; flowId?: string }

export function countEvidence(events: readonly VizEvent[]): EvidenceCounts {
  const locks = new Set<string>()
  const semaphores = new Set<string>()
  const channels = new Set<string>()
  const flows = new Set<string>()
  let jobEvents = 0
  for (const event of events) {
    const ids = event as unknown as ResourceIds
    if (SYNC_EVENT_KINDS.has(event.kind)) {
      if (ids.mutexId) locks.add(ids.mutexId)
      if (ids.semaphoreId) semaphores.add(ids.semaphoreId)
    } else if (CHANNEL_EVENT_KINDS.has(event.kind)) {
      if (ids.channelId) channels.add(ids.channelId)
    } else if (FLOW_EVENT_KINDS.has(event.kind)) {
      if (ids.flowId) flows.add(ids.flowId)
    } else if (JOB_EVENT_KINDS.has(event.kind)) {
      jobEvents++
    }
  }
  return { locks: locks.size, semaphores: semaphores.size, channels: channels.size, flows: flows.size, jobEvents }
}

/** "1 lock", "2 locks"; empty for zero, so callers can drop it. */
export function plural(n: number, one: string, many = `${one}s`): string {
  return n === 0 ? '' : `${n} ${n === 1 ? one : many}`
}

/** The non-empty parts, comma-separated; null when there are none. */
export function resourceSummary(parts: readonly string[]): string | null {
  const shown = parts.filter(p => p !== '')
  return shown.length > 0 ? shown.join(', ') : null
}

/** The Evidence tab's label names what it holds (S-4): "Evidence · 1 lock, 1 semaphore". */
export function evidenceTabLabel(c: EvidenceCounts): string {
  const held = resourceSummary([
    plural(c.locks, 'lock'),
    plural(c.semaphores, 'semaphore'),
    plural(c.channels, 'channel'),
    plural(c.flows, 'flow'),
  ])
  return held ? `Evidence · ${held}` : 'Evidence'
}
