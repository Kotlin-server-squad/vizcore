import type { AntiPatternType } from '@/hooks/use-anti-patterns'

/** The "How it works" content for one demo, keyed by its catalog id. */
export interface DemoGuide {
  /** Catalog id, e.g. 'nested'. */
  demoId: string
  level: 'beginner' | 'intermediate' | 'advanced'
  tags: string[]
  story: string
  /** Kotlin, with // 1, // 2 … markers matching `steps`. */
  code: string
  steps: { title: string; text: string }[]
  keyIdea: string
  mistakeId?: MistakeId
}

/** The detector's kinds, e.g. 'LEAKED_COROUTINE'. */
export type MistakeId = AntiPatternType

export interface Mistake {
  id: MistakeId
  title: string
  whatGoesWrong: string
  fix: string
  /** Whether the visualizer's detector reports this kind today. */
  detected: boolean
}
