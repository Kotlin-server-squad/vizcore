import type { ComponentType, ReactNode, Ref } from 'react'

/**
 * The pieces of layout a look may replace where CSS cannot rearrange them.
 * Add a slot only when a look needs one, with Classic's version extracted
 * unchanged into `classic/`. A slot receives rendered nodes and refs, never
 * data to re-derive: every look shares the page's behaviour and data flow.
 */
export interface LookSlots {
  /** The main view and the dock under the header. Classic: a column, the main view above the dock. */
  SessionBody: ComponentType<SessionBodyProps>
}

export interface SessionBodyProps {
  /** The dock measures this element to know how tall it may grow. */
  sharedRef: Ref<HTMLDivElement>
  main: ReactNode
  dock: ReactNode
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
