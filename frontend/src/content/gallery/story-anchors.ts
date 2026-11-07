import data from './story-anchors.json'
import { GUIDES } from './guides'
import type { CoroutineState } from '@/types/api'

/** The lifecycle events an anchor may name, by their wire names (spec "Story anchors"). */
export const ANCHOR_EVENTS = [
  'CoroutineCreated',
  'CoroutineStarted',
  'CoroutineSuspended',
  'CoroutineResumed',
  'CoroutineBodyCompleted',
  'CoroutineCompleted',
  'CoroutineCancelled',
  'CoroutineFailed',
] as const

export type AnchorEvent = (typeof ANCHOR_EVENTS)[number]

/** A guide step's moment: the [occurrence]th [event] of the coroutine labelled [coroutine]. */
export interface StoryAnchor {
  event: AnchorEvent
  coroutine: string
  occurrence: number
}

/** A question asked before Reveal, with its two or three answers and the right one. */
export interface StoryPrediction {
  question: string
  answers: string[]
  /** Index into [answers]. */
  correct: number
}

/** What story-anchors.json holds for one guide step. */
export interface StoryStep {
  anchor?: StoryAnchor
  /** Coroutine label → the state the tree shows at the anchor. */
  expect?: Record<string, `${CoroutineState}`>
  prediction?: StoryPrediction
}

/** One chapter of a demo's story: its guide step and that step's anchor. */
export interface StoryChapter extends StoryStep {
  index: number
  title: string
  text: string
}

/**
 * The anchors by demo id: one entry per guide step, in step order. JSON, so
 * that `StoryAnchorsTest` on the backend reads the same file and proves every
 * anchor against a real run of its demo.
 */
export const STORY_ANCHORS = data as unknown as Record<string, StoryStep[]>

/** The demo's story, or null when it has no guide or no anchors. */
export function storyFor(demoId: string): StoryChapter[] | null {
  const guide = GUIDES[demoId]
  const steps = STORY_ANCHORS[demoId]
  if (!guide || !steps || steps.length !== guide.steps.length) return null
  return guide.steps.map((step, index) => ({ index, title: step.title, text: step.text, ...steps[index] }))
}
