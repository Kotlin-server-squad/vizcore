import type { EventCategories } from '@/hooks/use-event-categories'
import { deriveSessionKind } from './session-kind'

/**
 * How much of their own code the developer changed, which is what decides how
 * much vizcore can show them.
 *
 *   demo         — a canned scenario inside vizcore. Full fidelity, zero setup,
 *                  none of the user's code involved.
 *   attached     — the agent/DebugProbes path against a real app. Coroutine
 *                  lifecycle plus source attribution, no code change.
 *   instrumented — the real app with wrapper types swapped in. Contention,
 *                  backpressure, channels, select.
 */
export type Rung = 'demo' | 'attached' | 'instrumented'

export const RUNG_LABEL: Record<Rung, string> = {
  demo: 'DEMO',
  attached: 'ATTACHED',
  instrumented: 'INSTRUMENTED',
}

/**
 * Wrapper-only evidence. Channel, Flow and Sync event families are emitted
 * exclusively by `InstrumentedChannel`, `InstrumentedFlow`, `VizMutex`,
 * `VizSemaphore`, `VizSelect` and `VizActor` — the DebugProbes attach path
 * cannot synthesize them. Their presence on a real session therefore proves the
 * developer instrumented their own code.
 *
 * `hasJobs` is deliberately excluded: job events are not confirmed wrapper-only,
 * and over-reporting the rung would promise panels the session cannot fill.
 */
function hasWrapperEvidence(categories: EventCategories): boolean {
  return categories.hasChannels || categories.hasFlowOps || categories.hasSyncPrimitives
}

/**
 * Pure classifier. A demo session stays on the demo rung whatever its events
 * contain — a canned scenario uses the wrappers internally, which says nothing
 * about the user's own code.
 */
export function deriveRung(sessionId: string, categories: EventCategories): Rung {
  if (deriveSessionKind({ sessionId, coroutineCount: 0 }) === 'demo') return 'demo'
  return hasWrapperEvidence(categories) ? 'instrumented' : 'attached'
}
