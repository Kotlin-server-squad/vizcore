import { sendBatch, type ExperimentEvent } from './api'
import { MAX_EVENTS_PER_BATCH } from './constants'

/** Measurements waiting to be sent, with the anonymous id they belong to. */
let pending: ExperimentEvent[] = []
let pendingId: string | null = null

/** Test hook. */
export function forgetQueue() {
  pending = []
  pendingId = null
}

export function pendingEvents(): readonly ExperimentEvent[] {
  return pending
}

/** Queues one measurement; visible time adds up per look, so a batch stays small. */
export function enqueueEvent(anonId: string, event: ExperimentEvent) {
  pendingId = anonId
  if (event.type === 'time') {
    const same = pending.find(e => e.type === 'time' && e.look === event.look)
    if (same && same.type === 'time') {
      same.ms += event.ms
      return
    }
    pending.push({ ...event })
    return
  }
  pending.push(event)
}

/** Sends what waits, in batches the server takes. [leaving]: the page is being hidden or closed. */
export function flushEvents({ leaving }: { leaving: boolean }) {
  if (pending.length === 0 || pendingId === null) return
  const events = pending
  pending = []
  for (let at = 0; at < events.length; at += MAX_EVENTS_PER_BATCH) {
    sendBatch(pendingId, events.slice(at, at + MAX_EVENTS_PER_BATCH), leaving)
  }
}
