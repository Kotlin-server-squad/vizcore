import { useMemo } from 'react'
import { projectWaits } from '@/lib/projections/project-waits'
import { resourceNames } from '@/lib/selection'
import type { HeaderSummaryProps } from '../types'
import { summarise } from './summary'

/**
 * B's line under the session title: how the run is going and what is waiting, in one or two plain
 * sentences (sketch K-B-Calm). It reads only the events and facts the header already holds. Not a live
 * region: the outcome pill already announces the outcome, and a sentence that changes with every event
 * would be noise.
 */
export function CalmSummary({ events, ...facts }: HeaderSummaryProps) {
  const waits = useMemo(() => [...projectWaits(events).values()], [events])
  const names = useMemo(() => resourceNames(events), [events])
  const sentence = summarise({ ...facts, waits, names })
  if (sentence === null) return null
  return (
    <p data-calm="summary" className="text-sm text-default-500">
      {sentence}
    </p>
  )
}

/** The HeaderSummary slot: a leaf of its own, so switching looks remounts nothing that holds state. */
export const calmHeaderSummary = (props: HeaderSummaryProps) => <CalmSummary {...props} />
