import { BASICS_GUIDES } from './basics'
import { CHANNELS_GUIDES } from './channels'
import { COMBINED_GUIDES } from './combined'
import { FLOW_GUIDES } from './flow'
import { MUTEX_GUIDES } from './mutex'
import { PATTERNS_GUIDES } from './patterns'
import { REAL_LIFE_GUIDES } from './real-life'
import { SEMAPHORE_GUIDES } from './semaphore'
import type { DemoGuide } from './types'

/** "How it works" guides by demo id. Every demo in the catalog has one; a demo without one would still have its card and Run. */
export const GUIDES: Record<string, DemoGuide> = Object.fromEntries(
  [
    ...REAL_LIFE_GUIDES,
    ...BASICS_GUIDES,
    ...CHANNELS_GUIDES,
    ...FLOW_GUIDES,
    ...PATTERNS_GUIDES,
    ...MUTEX_GUIDES,
    ...SEMAPHORE_GUIDES,
    ...COMBINED_GUIDES,
  ].map((guide) => [guide.demoId, guide]),
)
