import type { ReactElement, ReactNode, Ref } from 'react'
import type { ActivityLine } from '@/lib/activity'
import type { StateCounts } from '@/lib/state-counts'
import type { CoroutineNode } from '@/types/api'
import type { RunOutcomeFacts } from '@/lib/run-outcome'
import type { VizEvent } from '@/types/api'

/**
 * The pieces of layout a look may replace where CSS cannot rearrange them.
 * Add a slot only when a look needs one, with Classic's version extracted
 * unchanged into `classic/`. A slot receives rendered nodes and refs, never
 * data to re-derive: every look shares the page's behaviour and data flow.
 *
 * A slot is a render function, called in place (`slot(props)`, not
 * `<Slot />`), so switching looks does not swap a component type and remount
 * what it holds: the graph keeps its pan and zoom, Evidence its open sections,
 * and no panel re-subscribes. For that to hold, every look's version must
 * return the same shape: for SessionBody, one `div` whose first two children
 * are `main` and then `dock`. Attributes, classes and extra children after
 * those two may differ.
 */
export interface LookSlots {
  /** The main view and the dock under the header. Classic: a column, the main view above the dock. */
  SessionBody: (props: SessionBodyProps) => ReactElement
  /**
   * A line under the session title, from the facts the header already holds
   * (no request, no other data). Classic: none. Its result is a leaf, so it
   * may be a component of the look's own.
   */
  HeaderSummary: (props: HeaderSummaryProps) => ReactNode
}

export interface HeaderSummaryProps {
  /** A demo run's outcome; null for an attached app. */
  outcome: RunOutcomeFacts | null
  /** From the first to the last event the page holds. */
  runSpanNanos: number | null
  /** Events were dropped, so the span is a floor. */
  spanIsFloor: boolean
  replayActive: boolean
  replayAtNanos: number | null
  /** The events the page shows: live, paused or up to the replay cursor. */
  events: readonly VizEvent[]
}

export interface SessionBodyProps {
  /** The dock measures this element to know how tall it may grow. */
  sharedRef: Ref<HTMLDivElement>
  main: ReactNode
  dock: ReactNode
  /** What the page already knows, for a look that summarises it (C's "Right now"). Classic ignores it. */
  overview?: SessionOverview
}

/** Values the page has already computed; a look only presents them. */
export interface SessionOverview {
  /** The state bar's counts, over the whole snapshot. */
  counts: StateCounts
  /** The page's coroutines (replay-aware), for their names and states. */
  coroutines: readonly CoroutineNode[]
  /** Each coroutine's "waits for / holds" line (S-6), keyed by coroutine id. */
  activity: ReadonlyMap<string, ActivityLine>
}

export interface LookDefinition<Id extends string = string> {
  id: Id
  /** As the ⋯ menu shows it. */
  label: string
  /** A look on trial, to be kept or deleted. */
  trial: boolean
  /** The layout this look replaces; any slot left out is Classic's. */
  slots?: Partial<LookSlots>
}
