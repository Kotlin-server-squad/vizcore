import data from './will-see.json'
import type { CoroutineState } from '@/types/api'

export interface WillSeeCoroutine {
  label: string
  state: `${CoroutineState}`
  /** 0 for the demo's root coroutine, 1 for its children, and so on. */
  depth: number
}

/** "What you will see" for one demo: the tree at one telling moment. */
export interface WillSee {
  /** When the picture is taken, in seconds from the start; GuideLabelsTest checks it. */
  atSeconds: number
  /** That moment in words, for the caption, e.g. "About 10 s in: …". */
  moment: string
  coroutines: WillSeeCoroutine[]
}

/**
 * The pictures by demo id. JSON, so that `GuideLabelsTest` on the backend can
 * read it too: it runs each demo and checks every label at its depth.
 */
export const WILL_SEE = data as Record<string, WillSee>

export const willSeeFor = (demoId: string): WillSee | undefined => WILL_SEE[demoId]
